package payments.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import payments.api.CreatePaymentRequest;
import payments.domain.PaymentStatus;
import payments.service.PaymentService;
import payments.exception.IdempotencyConflictException;
import payments.exception.PaymentNotFoundException;
import payments.exception.PaymentStateConflictException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PaymentServiceIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID merchantId;
    private boolean testDatabaseVerified;

    @BeforeEach
    void setUp() {
        String databaseName = jdbcTemplate.queryForObject(
                "SELECT DATABASE()",
                String.class
        );

        assertThat(databaseName)
                .as("Payment tests must use the dedicated test database")
                .isEqualTo("ledgerflow_test");

        testDatabaseVerified = true;
        merchantId = UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() {
        if (testDatabaseVerified && merchantId != null) {
            String merchantIdValue = merchantId.toString();

            jdbcTemplate.update(
                    "DELETE FROM ledger_entries WHERE merchant_id = ?",
                    merchantIdValue
            );

            jdbcTemplate.update(
                    "DELETE FROM ledger_transactions WHERE merchant_id = ?",
                    merchantIdValue
            );

            jdbcTemplate.update(
                    "DELETE FROM ledger_accounts WHERE merchant_id = ?",
                    merchantIdValue
            );

            jdbcTemplate.update(
                    "DELETE FROM payments WHERE merchant_id = ?",
                    merchantIdValue
            );
        }
    }

    @Test
    void sameRequestAndIdempotencyKeyReturnOnePayment() {
        // Arrange: prepare one payment request.
        String idempotencyKey = "integration-order-1001";

        CreatePaymentRequest request = new CreatePaymentRequest(
                12_550L,
                "INR",
                "ORDER-1001"
        );

        // Act: create the payment, then repeat the same request.
        PaymentService.CreationResult first = paymentService.create(
                merchantId,
                idempotencyKey,
                request
        );

        PaymentService.CreationResult replay = paymentService.create(
                merchantId,
                idempotencyKey,
                request
        );

        // Assert: the first call creates a payment.
        assertThat(first.created()).isTrue();
        assertThat(first.payment().id()).isNotNull();
        assertThat(first.payment().amountMinor()).isEqualTo(12_550L);
        assertThat(first.payment().currency()).isEqualTo("INR");
        assertThat(first.payment().orderReference())
                .isEqualTo("ORDER-1001");
        assertThat(first.payment().status())
                .isEqualTo(PaymentStatus.CREATED);

        // The repeated call returns the existing payment.
        assertThat(replay.created()).isFalse();
        assertThat(replay.payment()).isEqualTo(first.payment());

        // Verify independently that MySQL contains only one row.
        Long paymentCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM payments
                WHERE merchant_id = ?
                  AND idempotency_key = ?
                """,
                Long.class,
                merchantId.toString(),
                idempotencyKey
        );

        assertThat(paymentCount).isEqualTo(1L);

        // Verify that the payment can also be retrieved.
        assertThat(paymentService.get(
                merchantId,
                first.payment().id()
        )).isEqualTo(first.payment());
    }

    @Test
    void sameKeyWithDifferentAmountRejectsRequestAndPreservesOriginalPayment() {
        // Arrange: create the original payment for ₹125.50.
        String idempotencyKey = "integration-order-conflict";

        CreatePaymentRequest originalRequest = new CreatePaymentRequest(
                12_550L,
                "INR",
                "ORDER-CONFLICT-1001"
        );

        PaymentService.CreationResult original = paymentService.create(
                merchantId,
                idempotencyKey,
                originalRequest
        );

        // Change only the amount to ₹200.00.
        CreatePaymentRequest changedRequest = new CreatePaymentRequest(
                20_000L,
                "INR",
                "ORDER-CONFLICT-1001"
        );

        // Act and assert: the same key cannot represent a different request.
        assertThatThrownBy(() -> paymentService.create(
                merchantId,
                idempotencyKey,
                changedRequest
        )).isInstanceOf(IdempotencyConflictException.class);

        // Verify that every field of the original payment is unchanged.
        assertThat(paymentService.get(
                merchantId,
                original.payment().id()
        )).isEqualTo(original.payment());

        // Verify the stored amount independently through SQL.
        Long storedAmount = jdbcTemplate.queryForObject(
                """
                SELECT amount_minor
                FROM payments
                WHERE merchant_id = ?
                  AND id = ?
                """,
                Long.class,
                merchantId.toString(),
                original.payment().id().toString()
        );

        assertThat(storedAmount).isEqualTo(12_550L);

        // The rejected request must not create another payment.
        Long paymentCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM payments
                WHERE merchant_id = ?
                  AND idempotency_key = ?
                """,
                Long.class,
                merchantId.toString(),
                idempotencyKey
        );

        assertThat(paymentCount).isEqualTo(1L);
    }

    @Test
    void anotherMerchantCannotReadPayment() {
        // Arrange: create a payment owned by this test's merchant.
        CreatePaymentRequest request = new CreatePaymentRequest(
                12_550L,
                "INR",
                "ORDER-ISOLATION-READ"
        );

        PaymentService.CreationResult original = paymentService.create(
                merchantId,
                "isolation-read-key",
                request
        );

        UUID anotherMerchantId = UUID.randomUUID();

        // Another merchant knows the ID but must not receive the payment.
        assertThatThrownBy(() -> paymentService.get(
                anotherMerchantId,
                original.payment().id()
        )).isInstanceOf(PaymentNotFoundException.class);

        // The owner can still retrieve the original payment.
        assertThat(paymentService.get(
                merchantId,
                original.payment().id()
        )).isEqualTo(original.payment());
    }

    @Test
    void anotherMerchantCannotChangePaymentOutcome() {
        // Arrange: create a payment owned by this test's merchant.
        CreatePaymentRequest request = new CreatePaymentRequest(
                12_550L,
                "INR",
                "ORDER-ISOLATION-UPDATE"
        );

        PaymentService.CreationResult original = paymentService.create(
                merchantId,
                "isolation-update-key",
                request
        );

        UUID anotherMerchantId = UUID.randomUUID();

        // Another merchant must not be able to mark it successful.
        assertThatThrownBy(() -> paymentService.applyOutcome(
                anotherMerchantId,
                original.payment().id(),
                PaymentStatus.SUCCEEDED
        )).isInstanceOf(PaymentNotFoundException.class);

        // Verify that the original payment is completely unchanged.
        assertThat(paymentService.get(
                merchantId,
                original.payment().id()
        )).isEqualTo(original.payment());

        // Independently verify the persisted status.
        String storedStatus = jdbcTemplate.queryForObject(
                """
                SELECT status
                FROM payments
                WHERE merchant_id = ?
                  AND id = ?
                """,
                String.class,
                merchantId.toString(),
                original.payment().id().toString()
        );

        assertThat(storedStatus).isEqualTo("CREATED");
    }

    @Test
    void successfulOutcomeIsPersistedAndRepeatingItChangesNothing() {
        CreatePaymentRequest request = new CreatePaymentRequest(
                12_550L,
                "INR",
                "ORDER-OUTCOME-SUCCESS"
        );

        PaymentService.CreationResult created = paymentService.create(
                merchantId,
                "outcome-success-key",
                request
        );

        UUID paymentId = created.payment().id();

        // First simulation changes CREATED to SUCCEEDED.
        var successful = paymentService.applyOutcome(
                merchantId,
                paymentId,
                PaymentStatus.SUCCEEDED
        );

        assertThat(successful.status())
                .isEqualTo(PaymentStatus.SUCCEEDED);

        // Verify the committed database state independently.
        String storedStatus = jdbcTemplate.queryForObject(
                """
                SELECT status
                FROM payments
                WHERE merchant_id = ?
                  AND id = ?
                """,
                String.class,
                merchantId.toString(),
                paymentId.toString()
        );

        assertThat(storedStatus).isEqualTo("SUCCEEDED");

        // Repeating the same outcome must not change any response field.
        var repeated = paymentService.applyOutcome(
                merchantId,
                paymentId,
                PaymentStatus.SUCCEEDED
        );

        assertThat(repeated).isEqualTo(successful);

        // Read again to verify persisted values, including updatedAt.
        assertThat(paymentService.get(merchantId, paymentId))
                .isEqualTo(successful);
    }

    @Test
    void conflictingOutcomeIsRejectedAndSuccessfulPaymentIsPreserved() {
        CreatePaymentRequest request = new CreatePaymentRequest(
                12_550L,
                "INR",
                "ORDER-OUTCOME-CONFLICT"
        );

        PaymentService.CreationResult created = paymentService.create(
                merchantId,
                "outcome-conflict-key",
                request
        );

        UUID paymentId = created.payment().id();

        var successful = paymentService.applyOutcome(
                merchantId,
                paymentId,
                PaymentStatus.SUCCEEDED
        );

        // A successful payment cannot subsequently become FAILED.
        assertThatThrownBy(() -> paymentService.applyOutcome(
                merchantId,
                paymentId,
                PaymentStatus.FAILED
        )).isInstanceOf(PaymentStateConflictException.class);

        // Every stored response field must remain unchanged.
        assertThat(paymentService.get(merchantId, paymentId))
                .isEqualTo(successful);

        String storedStatus = jdbcTemplate.queryForObject(
                """
                SELECT status
                FROM payments
                WHERE merchant_id = ?
                  AND id = ?
                """,
                String.class,
                merchantId.toString(),
                paymentId.toString()
        );

        assertThat(storedStatus).isEqualTo("SUCCEEDED");
    }
}