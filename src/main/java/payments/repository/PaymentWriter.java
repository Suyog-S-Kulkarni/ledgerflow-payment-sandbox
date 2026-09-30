package payments.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import payments.domain.PaymentRules;
import payments.domain.PaymentStatus;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Component
public class PaymentWriter {

    private static final String INSERT_PAYMENT = """
            INSERT INTO payments (
                id,
                merchant_id,
                idempotency_key,
                request_hash,
                order_reference,
                amount_minor,
                currency,
                status,
                created_at,
                updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public PaymentWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public UUID insert(
            UUID merchantId,
            String idempotencyKey,
            long amountMinor,
            String currency,
            String orderReference
    ) {
        UUID paymentId = UUID.randomUUID();

        String requestHash = PaymentRules.fingerprint(
                amountMinor,
                currency,
                orderReference
        );

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);

        jdbcTemplate.update(
                INSERT_PAYMENT,
                paymentId.toString(),
                merchantId.toString(),
                idempotencyKey,
                requestHash,
                orderReference,
                amountMinor,
                currency,
                PaymentStatus.CREATED.name(),
                now,
                now
        );

        return paymentId;
    }
}