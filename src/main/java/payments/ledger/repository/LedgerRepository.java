package payments.ledger.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import payments.ledger.model.EntryDirection;
import payments.ledger.model.LedgerAccount;
import payments.ledger.model.LedgerAccountCode;
import payments.ledger.model.LedgerBalance;
import payments.ledger.model.LedgerEntry;
import payments.ledger.model.LedgerTransaction;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class LedgerRepository {

    private static final String PAYMENT_POSTING =
            "PAYMENT_SUCCEEDED";

    private static final String SELECT_TRANSACTION = """
            SELECT id,
                   merchant_id,
                   payment_id,
                   posting_type,
                   amount_minor,
                   currency,
                   posted_at
            FROM ledger_transactions
            WHERE merchant_id = ?
              AND payment_id = ?
              AND posting_type = 'PAYMENT_SUCCEEDED'
            """;

    private final JdbcTemplate jdbcTemplate;

    public LedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public LedgerAccount ensureAccount(
            UUID merchantId,
            LedgerAccountCode accountCode,
            String currency,
            Instant createdAt
    ) {
        UUID proposedId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO ledger_accounts (
                    id,
                    merchant_id,
                    account_code,
                    currency,
                    created_at
                )
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """,
                proposedId.toString(),
                merchantId.toString(),
                accountCode.name(),
                currency,
                utc(createdAt)
        );

        return jdbcTemplate.queryForObject("""
                SELECT id,
                       merchant_id,
                       account_code,
                       currency,
                       created_at
                FROM ledger_accounts
                WHERE merchant_id = ?
                  AND account_code = ?
                  AND currency = ?
                """,
                (rs, rowNum) -> new LedgerAccount(
                        uuid(rs, "id"),
                        uuid(rs, "merchant_id"),
                        LedgerAccountCode.valueOf(
                                rs.getString("account_code")
                        ),
                        rs.getString("currency"),
                        instant(rs, "created_at")
                ),
                merchantId.toString(),
                accountCode.name(),
                currency
        );
    }

    public Optional<LedgerTransaction> findByPayment(
            UUID merchantId,
            UUID paymentId
    ) {
        return findByPayment(merchantId, paymentId, false);
    }

    public Optional<LedgerTransaction> findByPaymentForUpdate(
            UUID merchantId,
            UUID paymentId
    ) {
        return findByPayment(merchantId, paymentId, true);
    }

    private Optional<LedgerTransaction> findByPayment(
            UUID merchantId,
            UUID paymentId,
            boolean forUpdate
    ) {
        String sql = SELECT_TRANSACTION
                + (forUpdate ? " FOR UPDATE" : "");

        List<LedgerTransaction> transactions = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new LedgerTransaction(
                        uuid(rs, "id"),
                        uuid(rs, "merchant_id"),
                        uuid(rs, "payment_id"),
                        rs.getString("posting_type"),
                        rs.getLong("amount_minor"),
                        rs.getString("currency"),
                        instant(rs, "posted_at"),
                        List.of()
                ),
                merchantId.toString(),
                paymentId.toString()
        );

        if (transactions.isEmpty()) {
            return Optional.empty();
        }

        LedgerTransaction transaction = transactions.getFirst();

        List<LedgerEntry> entries = jdbcTemplate.query("""
                SELECT id,
                       account_id,
                       account_code,
                       direction,
                       amount_minor
                FROM ledger_entries
                WHERE merchant_id = ?
                  AND transaction_id = ?
                ORDER BY CASE
                    WHEN direction = 'DEBIT' THEN 0
                    ELSE 1
                END
                """,
                (rs, rowNum) -> new LedgerEntry(
                        uuid(rs, "id"),
                        uuid(rs, "account_id"),
                        LedgerAccountCode.valueOf(
                                rs.getString("account_code")
                        ),
                        EntryDirection.valueOf(
                                rs.getString("direction")
                        ),
                        rs.getLong("amount_minor")
                ),
                merchantId.toString(),
                transaction.id().toString()
        );

        return Optional.of(new LedgerTransaction(
                transaction.id(),
                transaction.merchantId(),
                transaction.paymentId(),
                transaction.postingType(),
                transaction.amountMinor(),
                transaction.currency(),
                transaction.postedAt(),
                entries
        ));
    }

    public void insertTransaction(
            UUID transactionId,
            UUID merchantId,
            UUID paymentId,
            long amountMinor,
            String currency,
            Instant postedAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO ledger_transactions (
                    id,
                    merchant_id,
                    payment_id,
                    posting_type,
                    amount_minor,
                    currency,
                    posted_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                transactionId.toString(),
                merchantId.toString(),
                paymentId.toString(),
                PAYMENT_POSTING,
                amountMinor,
                currency,
                utc(postedAt)
        );
    }

    public void insertEntry(
            UUID transactionId,
            UUID merchantId,
            LedgerAccount account,
            EntryDirection direction,
            long amountMinor
    ) {
        jdbcTemplate.update("""
                INSERT INTO ledger_entries (
                    id,
                    transaction_id,
                    merchant_id,
                    account_id,
                    account_code,
                    direction,
                    amount_minor,
                    currency
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                transactionId.toString(),
                merchantId.toString(),
                account.id().toString(),
                account.accountCode().name(),
                direction.name(),
                amountMinor,
                account.currency()
        );
    }

    public List<LedgerBalance> findBalances(UUID merchantId) {
        return jdbcTemplate.query("""
                SELECT a.id,
                       a.account_code,
                       a.currency,
                       COALESCE(
                           SUM(
                               CASE WHEN e.direction = 'DEBIT'
                                    THEN e.amount_minor
                                    ELSE 0
                               END
                           ),
                           0
                       ) AS debit_total,
                       COALESCE(
                           SUM(
                               CASE WHEN e.direction = 'CREDIT'
                                    THEN e.amount_minor
                                    ELSE 0
                               END
                           ),
                           0
                       ) AS credit_total
                FROM ledger_accounts a
                LEFT JOIN ledger_entries e
                  ON e.account_id = a.id
                 AND e.merchant_id = a.merchant_id
                 AND e.currency = a.currency
                WHERE a.merchant_id = ?
                GROUP BY a.id, a.account_code, a.currency
                ORDER BY a.account_code
                """,
                (rs, rowNum) -> {
                    LedgerAccountCode code =
                            LedgerAccountCode.valueOf(
                                    rs.getString("account_code")
                            );

                    BigDecimal debits =
                            rs.getBigDecimal("debit_total");

                    BigDecimal credits =
                            rs.getBigDecimal("credit_total");

                    BigDecimal balance =
                            code.normalBalanceDirection()
                                    == EntryDirection.DEBIT
                                    ? debits.subtract(credits)
                                    : credits.subtract(debits);

                    return new LedgerBalance(
                            uuid(rs, "id"),
                            code,
                            code.accountType(),
                            rs.getString("currency"),
                            debits,
                            credits,
                            balance
                    );
                },
                merchantId.toString()
        );
    }

    private static UUID uuid(
            ResultSet rs,
            String column
    ) throws SQLException {
        return UUID.fromString(rs.getString(column));
    }

    private static Instant instant(
            ResultSet rs,
            String column
    ) throws SQLException {
        return rs.getObject(column, LocalDateTime.class)
                .toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}