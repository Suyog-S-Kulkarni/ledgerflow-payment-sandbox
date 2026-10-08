package payments.ledger.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import payments.ledger.model.LedgerBalance;
import payments.ledger.model.LedgerTransaction;
import payments.ledger.service.LedgerQueryService;
import payments.security.MerchantIdentity;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final LedgerQueryService ledgerQueryService;

    public LedgerController(
            LedgerQueryService ledgerQueryService
    ) {
        this.ledgerQueryService = ledgerQueryService;
    }

    @GetMapping("/payments/{paymentId}")
    public LedgerTransaction getPaymentLedger(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("paymentId") UUID paymentId
    ) {
        UUID merchantId = MerchantIdentity.from(jwt);

        return ledgerQueryService.getPaymentLedger(
                merchantId,
                paymentId
        );
    }

    @GetMapping("/balances")
    public List<LedgerBalance> getBalances(
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID merchantId = MerchantIdentity.from(jwt);

        return ledgerQueryService.getBalances(merchantId);
    }
}