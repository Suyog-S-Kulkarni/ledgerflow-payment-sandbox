package payments.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import payments.domain.PaymentStatus;
import payments.exception.PaymentNotFoundException;
import payments.exception.PaymentStateConflictException;
import payments.ledger.model.EntryDirection;
import payments.ledger.model.LedgerAccountCode;
import payments.ledger.model.LedgerTransaction;
import payments.ledger.service.LedgerQueryService;
import payments.repository.PaymentWriter;
import payments.service.PaymentService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=${LEDGERFLOW_TEST_DB_URL}",
        "spring.datasource.username=${LEDGERFLOW_TEST_DB_USER}",
        "spring.datasource.password=${LEDGERFLOW_TEST_DB_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/mysql"
})
class LedgerIntegrationTest {

    @Autowired
    private PaymentWriter paymentWriter;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private LedgerQueryService ledgerQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // Replaces the real decoder for these service/database tests.
    // Keycloak does not need to be running for this test class.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() {
        String merchant = merchantId.toString();

        jdbcTemplate.update(
                "DELETE FROM ledger_entries WHERE merchant_id = ?",
                merchant
        );

        jdbcTemplate.update(
                "DELETE FROM ledger_transactions WHERE merchant_id = ?",
                merchant
        );

        jdbcTemplate.update(
                "DELETE FROM ledger_accounts WHERE merchant_id = ?",
                merchant
        );

        jdbcTemplate.update(
                "DELETE FROM payments WHERE merchant_id = ?",
                merchant
        );
    }

    @Test
    void successCreatesBalancedPosting() {
        UUID paymentId = createPayment();

        paymentService.applyOutcome(
                merchantId,
                paymentId,
                PaymentStatus.SUCCEEDED
        );

        assertThat(
                paymentService.get(merchantId, paymentId).status()
        ).isEqualTo(PaymentStatus.SUCCEEDED);

        LedgerTransaction ledger =
                ledgerQueryService.getPaymentLedger(
                        merchantId,
                        paymentId
                );

        assertThat(ledger.amountMinor()).isEqualTo(12550L);
        assertThat(ledger.currency()).isEqualTo("INR");
        assertThat(ledger.entries()).hasSize(2);

        assertThat(ledger.entries()).anySatisfy(entry -> {
            assertThat(entry.accountCode())
                    .isEqualTo(
                            LedgerAccountCode.PROCESSOR_RECEIVABLE
                    );

            assertThat(entry.direction())
                    .isEqualTo(EntryDirection.DEBIT);

            assertThat(entry.amountMinor())
                    .isEqualTo(12550L);
        });

        assertThat(ledger.entries()).anySatisfy(entry -> {
            assertThat(entry.accountCode())
                    .isEqualTo(
                            LedgerAccountCode.MERCHANT_PAYABLE
                    );

            assertThat(entry.direction())
                    .isEqualTo(EntryDirection.CREDIT);

            assertThat(entry.amountMinor())
                    .isEqualTo(12550L);
        });

        assertThat(ledgerQueryService.getBalances(merchantId))
                .hasSize(2)
                .allSatisfy(balance ->
                        assertThat(balance.balanceMinor())
                                .isEqualByComparingTo("12550")
                );
    }

    @Test
    void repeatedSuccessDoesNotDuplicatePosting() {
        UUID paymentId = createPayment();

        paymentService.applyOutcome(
                merchantId,
                paymentId,
                PaymentStatus.SUCCEEDED
        );

        UUID firstPostingId = ledgerQueryService
                .getPaymentLedger(merchantId, paymentId)
                .id();

        paymentService.applyOutcome(
                merchantId,
                paymentId,
                PaymentStatus.SUCCEEDED
        );

        UUID secondPostingId = ledgerQueryService
                .getPaymentLedger(merchantId, paymentId)
                .id();

        assertThat(secondPostingId).isEqualTo(firstPostingId);
        assertThat(transactionCount()).isEqualTo(1L);
        assertThat(entryCount()).isEqualTo(2L);
    }

    @Test
    void failedPaymentHasNoPostingAndCannotBecomeSuccessful() {
        UUID paymentId = createPayment();

        paymentService.applyOutcome(
                merchantId,
                paymentId,
                PaymentStatus.FAILED
        );

        assertThat(transactionCount()).isZero();
        assertThat(entryCount()).isZero();

        assertThatThrownBy(() ->
                paymentService.applyOutcome(
                        merchantId,
                        paymentId,
                        PaymentStatus.SUCCEEDED
                )
        ).isInstanceOf(PaymentStateConflictException.class);

        assertThat(
                paymentService.get(merchantId, paymentId).status()
        ).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void anotherMerchantCannotReadPosting() {
        UUID paymentId = createPayment();

        paymentService.applyOutcome(
                merchantId,
                paymentId,
                PaymentStatus.SUCCEEDED
        );

        UUID otherMerchant = UUID.randomUUID();

        assertThatThrownBy(() ->
                ledgerQueryService.getPaymentLedger(
                        otherMerchant,
                        paymentId
                )
        ).isInstanceOf(PaymentNotFoundException.class);

        assertThat(
                ledgerQueryService.getBalances(otherMerchant)
        ).isEmpty();
    }

    @Test
    void paymentAndLedgerRollBackTogether() {
        UUID paymentId = createPayment();

        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        assertThatThrownBy(() ->
                transaction.executeWithoutResult(status -> {
                    paymentService.applyOutcome(
                            merchantId,
                            paymentId,
                            PaymentStatus.SUCCEEDED
                    );

                    assertThat(transactionCount()).isEqualTo(1L);
                    assertThat(entryCount()).isEqualTo(2L);

                    throw new IllegalStateException(
                            "Force transaction rollback"
                    );
                })
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Force transaction rollback");

        assertThat(
                paymentService.get(merchantId, paymentId).status()
        ).isEqualTo(PaymentStatus.CREATED);

        assertThat(transactionCount()).isZero();
        assertThat(entryCount()).isZero();

        Long accountCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM ledger_accounts
                WHERE merchant_id = ?
                """,
                Long.class,
                merchantId.toString()
        );

        assertThat(accountCount).isZero();
    }

    @Test
    void concurrentSuccessRequestsCreateOnePosting()
            throws Exception {

        UUID paymentId = createPayment();
        int workers = 4;

        ExecutorService executor =
                Executors.newFixedThreadPool(workers);

        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();

                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Start signal timed out"
                        );
                    }

                    paymentService.applyOutcome(
                            merchantId,
                            paymentId,
                            PaymentStatus.SUCCEEDED
                    );

                    return null;
                }));
            }

            assertThat(
                    ready.await(10, TimeUnit.SECONDS)
            ).isTrue();

            start.countDown();

            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }

        } finally {
            start.countDown();
            executor.shutdownNow();

            if (!executor.awaitTermination(
                    30,
                    TimeUnit.SECONDS
            )) {
                throw new IllegalStateException(
                        "Concurrent test workers did not stop"
                );
            }
        }

        assertThat(transactionCount()).isEqualTo(1L);
        assertThat(entryCount()).isEqualTo(2L);

        assertThat(
                paymentService.get(merchantId, paymentId).status()
        ).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void olderSuccessfulPaymentGetsPostingWhenSuccessIsRepeated() {
        UUID paymentId = createPayment();

        // Simulate a payment that succeeded before ledger deployment.
        jdbcTemplate.update(
                """
                UPDATE payments
                SET status = 'SUCCEEDED'
                WHERE id = ?
                """,
                paymentId.toString()
        );

        assertThat(transactionCount()).isZero();

        paymentService.applyOutcome(
                merchantId,
                paymentId,
                PaymentStatus.SUCCEEDED
        );

        assertThat(transactionCount()).isEqualTo(1L);
        assertThat(entryCount()).isEqualTo(2L);
    }

    private UUID createPayment() {
        String suffix = UUID.randomUUID().toString();

        return paymentWriter.insert(
                merchantId,
                "ledger-" + suffix,
                12550L,
                "INR",
                "order-" + suffix
        );
    }

    private long transactionCount() {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM ledger_transactions
                WHERE merchant_id = ?
                """,
                Long.class,
                merchantId.toString()
        );

        return count == null ? 0L : count;
    }

    private long entryCount() {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM ledger_entries
                WHERE merchant_id = ?
                """,
                Long.class,
                merchantId.toString()
        );

        return count == null ? 0L : count;
    }
}