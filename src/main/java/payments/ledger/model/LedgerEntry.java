package payments.ledger.model;

import java.util.UUID;

public record LedgerEntry(
        UUID id,
        UUID accountId,
        LedgerAccountCode accountCode,
        EntryDirection direction,
        long amountMinor
) {
}