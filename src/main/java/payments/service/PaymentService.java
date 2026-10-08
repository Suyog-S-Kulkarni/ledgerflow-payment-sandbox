package payments.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import payments.api.CreatePaymentRequest;
import payments.api.PaymentResponse;
import payments.domain.Payment;
import payments.domain.PaymentRules;
import payments.domain.PaymentStatus;
import payments.exception.IdempotencyConflictException;
import payments.exception.PaymentNotFoundException;
import payments.exception.PaymentStateConflictException;
import payments.ledger.service.LedgerPostingService;
import payments.repository.PaymentRepository;
import payments.repository.PaymentWriter;

import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentWriter paymentWriter;
    private final LedgerPostingService ledgerPostingService;

    public PaymentService(
            PaymentRepository paymentRepository,
            PaymentWriter paymentWriter,
            LedgerPostingService ledgerPostingService
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentWriter = paymentWriter;
        this.ledgerPostingService = ledgerPostingService;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CreationResult create(
            UUID merchantId,
            String idempotencyKey,
            CreatePaymentRequest request
    ) {
        String requestHash = PaymentRules.fingerprint(
                request.amountMinor(),
                request.currency(),
                request.orderReference()
        );

        Optional<Payment> existing = paymentRepository
                .findByMerchantIdAndIdempotencyKey(
                        merchantId,
                        idempotencyKey
                );

        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        UUID paymentId;

        try {
            paymentId = paymentWriter.insert(
                    merchantId,
                    idempotencyKey,
                    request.amountMinor(),
                    request.currency(),
                    request.orderReference()
            );
        } catch (DuplicateKeyException exception) {
            Payment concurrentPayment = paymentRepository
                    .findByMerchantIdAndIdempotencyKey(
                            merchantId,
                            idempotencyKey
                    )
                    .orElseThrow(() -> exception);

            return replay(concurrentPayment, requestHash);
        }

        Payment payment = findOwnedPayment(
                merchantId,
                paymentId
        );

        return new CreationResult(
                PaymentResponse.from(payment),
                true
        );
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(
            UUID merchantId,
            UUID paymentId
    ) {
        Payment payment = findOwnedPayment(
                merchantId,
                paymentId
        );

        return PaymentResponse.from(payment);
    }

    @Transactional
    public PaymentResponse applyOutcome(
            UUID merchantId,
            UUID paymentId,
            PaymentStatus outcome
    ) {
        if (outcome == null || outcome == PaymentStatus.CREATED) {
            throw new IllegalArgumentException(
                    "Outcome must be SUCCEEDED or FAILED"
            );
        }

        Payment payment = paymentRepository
                .findForUpdate(merchantId, paymentId)
                .orElseThrow(PaymentNotFoundException::new);

        try {
            payment.applyOutcome(outcome);
        } catch (IllegalStateException exception) {
            throw new PaymentStateConflictException(exception);
        }

        // Outside the catch block intentionally:
        // ledger inconsistencies are server failures,
        // not ordinary payment-state conflicts.
        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            ledgerPostingService.postSuccessfulPayment(payment);
        }

        return PaymentResponse.from(payment);
    }

    private Payment findOwnedPayment(
            UUID merchantId,
            UUID paymentId
    ) {
        return paymentRepository
                .findByMerchantIdAndId(merchantId, paymentId)
                .orElseThrow(PaymentNotFoundException::new);
    }

    private CreationResult replay(
            Payment payment,
            String requestHash
    ) {
        if (!payment.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException();
        }

        return new CreationResult(
                PaymentResponse.from(payment),
                false
        );
    }

    public record CreationResult(
            PaymentResponse payment,
            boolean created
    ) {
    }
}