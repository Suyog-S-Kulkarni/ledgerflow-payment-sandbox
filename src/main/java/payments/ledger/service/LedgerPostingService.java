package payments.ledger.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import payments.domain.Payment;
import payments.domain.PaymentStatus;
import payments.ledger.model.EntryDirection;
import payments.ledger.model.LedgerAccount;
import payments.ledger.model.LedgerAccountCode;
import payments.ledger.model.LedgerEntry;
import payments.ledger.model.LedgerTransaction;
import payments.ledger.repository.LedgerRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
public class LedgerPostingService {

    private final LedgerRepository ledgerRepository;

    public LedgerPostingService(
            LedgerRepository ledgerRepository
    ) {
        this.ledgerRepository = ledgerRepository;
    }

    /**
     * Caller must hold the payment write lock.
     *
     * This method joins the caller's transaction. It must never
     * commit independently of the payment outcome.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void postSuccessfulPayment(Payment payment) {
        validatePayment(payment);

        Optional<LedgerTransaction> existing =
                ledgerRepository.findByPaymentForUpdate(
                        payment.getMerchantId(),
                        payment.getId()
                );

        if (existing.isPresent()) {
            validateExistingPosting(existing.get(), payment);
            return;
        }

        Instant postedAt = Instant.now()
                .truncatedTo(ChronoUnit.MICROS);

        // Always acquire account locks in this order.
        LedgerAccount receivable =
                ledgerRepository.ensureAccount(
                        payment.getMerchantId(),
                        LedgerAccountCode.PROCESSOR_RECEIVABLE,
                        payment.getCurrency(),
                        postedAt
                );

        LedgerAccount payable =
                ledgerRepository.ensureAccount(
                        payment.getMerchantId(),
                        LedgerAccountCode.MERCHANT_PAYABLE,
                        payment.getCurrency(),
                        postedAt
                );

        UUID transactionId = UUID.randomUUID();

        ledgerRepository.insertTransaction(
                transactionId,
                payment.getMerchantId(),
                payment.getId(),
                payment.getAmountMinor(),
                payment.getCurrency(),
                postedAt
        );

        ledgerRepository.insertEntry(
                transactionId,
                payment.getMerchantId(),
                receivable,
                EntryDirection.DEBIT,
                payment.getAmountMinor()
        );

        ledgerRepository.insertEntry(
                transactionId,
                payment.getMerchantId(),
                payable,
                EntryDirection.CREDIT,
                payment.getAmountMinor()
        );
    }

    private void validatePayment(Payment payment) {
        if (payment == null) {
            throw new IllegalArgumentException(
                    "Payment is required"
            );
        }

        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw new IllegalStateException(
                    "Only successful payments can be posted"
            );
        }

        if (payment.getAmountMinor() < 1
                || payment.getAmountMinor() > 100_000_000L) {
            throw new IllegalStateException(
                    "Payment amount is outside the supported range"
            );
        }

        if (!"INR".equals(payment.getCurrency())) {
            throw new IllegalStateException(
                    "Only INR ledger postings are supported"
            );
        }
    }

    private void validateExistingPosting(
            LedgerTransaction transaction,
            Payment payment
    ) {
        boolean headerMatches =
                transaction.merchantId().equals(
                        payment.getMerchantId()
                )
                        && transaction.paymentId().equals(payment.getId())
                        && transaction.amountMinor()
                        == payment.getAmountMinor()
                        && transaction.currency().equals(
                        payment.getCurrency()
                )
                        && transaction.postingType().equals(
                        "PAYMENT_SUCCEEDED"
                );

        boolean hasDebit = transaction.entries().stream()
                .anyMatch(entry -> matches(
                        entry,
                        LedgerAccountCode.PROCESSOR_RECEIVABLE,
                        EntryDirection.DEBIT,
                        payment.getAmountMinor()
                ));

        boolean hasCredit = transaction.entries().stream()
                .anyMatch(entry -> matches(
                        entry,
                        LedgerAccountCode.MERCHANT_PAYABLE,
                        EntryDirection.CREDIT,
                        payment.getAmountMinor()
                ));

        if (!headerMatches
                || transaction.entries().size() != 2
                || !hasDebit
                || !hasCredit) {
            throw new IllegalStateException(
                    "Existing ledger posting is inconsistent"
            );
        }
    }

    private boolean matches(
            LedgerEntry entry,
            LedgerAccountCode code,
            EntryDirection direction,
            long amountMinor
    ) {
        return entry.accountCode() == code
                && entry.direction() == direction
                && entry.amountMinor() == amountMinor;
    }
}