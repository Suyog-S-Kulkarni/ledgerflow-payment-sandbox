package payments.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import payments.security.MerchantIdentity;
import payments.service.PaymentService;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @AuthenticationPrincipal Jwt jwt,

            @RequestHeader("Idempotency-Key")
            @Pattern(
                    regexp = "[A-Za-z0-9_-]{1,100}",
                    message = "Idempotency-Key must contain 1 to 100 "
                            + "letters, digits, underscores, or hyphens"
            )
            String idempotencyKey,

            @Valid @RequestBody CreatePaymentRequest request
    ) {
        UUID merchantId = MerchantIdentity.from(jwt);

        PaymentService.CreationResult result = paymentService.create(
                merchantId,
                idempotencyKey,
                request
        );

        if (result.created()) {
            URI location = URI.create(
                    "/api/payments/" + result.payment().id()
            );

            return ResponseEntity.created(location)
                    .header("Idempotency-Replayed", "false")
                    .body(result.payment());
        }

        return ResponseEntity.ok()
                .header("Idempotency-Replayed", "true")
                .body(result.payment());
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("paymentId") UUID paymentId
    ) {
        UUID merchantId = MerchantIdentity.from(jwt);

        return paymentService.get(merchantId, paymentId);
    }

    @PostMapping("/{paymentId}/simulate")
    public PaymentResponse simulate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("paymentId") UUID paymentId,
            @Valid @RequestBody SimulateOutcomeRequest request
    ) {
        UUID merchantId = MerchantIdentity.from(jwt);

        return paymentService.applyOutcome(
                merchantId,
                paymentId,
                request.outcome()
        );
    }
}