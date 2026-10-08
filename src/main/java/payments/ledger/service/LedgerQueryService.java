package payments.ledger.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import payments.exception.PaymentNotFoundException;
import payments.ledger.model.LedgerBalance;
import payments.ledger.model.LedgerTransaction;
import payments.ledger.repository.LedgerRepository;
import payments.repository.PaymentRepository;

import java.util.List;
import java.util.UUID;

@Service
public class LedgerQueryService {

    private final PaymentRepository paymentRepository;
    private final LedgerRepository ledgerRepository;

    public LedgerQueryService(
            PaymentRepository paymentRepository,
            LedgerRepository ledgerRepository
    ) {
        this.paymentRepository = paymentRepository;
        this.ledgerRepository = ledgerRepository;
    }

    @Transactional(readOnly = true)
    public LedgerTransaction getPaymentLedger(
            UUID merchantId,
            UUID paymentId
    ) {
        paymentRepository
                .findByMerchantIdAndId(merchantId, paymentId)
                .orElseThrow(PaymentNotFoundException::new);

        return ledgerRepository
                .findByPayment(merchantId, paymentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No ledger posting exists for this payment"
                ));
    }

    @Transactional(readOnly = true)
    public List<LedgerBalance> getBalances(UUID merchantId) {
        return ledgerRepository.findBalances(merchantId);
    }
}