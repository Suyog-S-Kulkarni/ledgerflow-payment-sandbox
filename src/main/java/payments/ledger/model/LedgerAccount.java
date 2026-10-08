package payments.ledger.model;

import java.time.Instant;
import java.util.UUID;

public record LedgerAccount(
        UUID id,
        UUID merchantId,
        LedgerAccountCode accountCode,
        String currency,
        Instant createdAt
) {
}