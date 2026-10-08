package payments.ledger.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LedgerTransaction(
        UUID id,
        UUID merchantId,
        UUID paymentId,
        String postingType,
        long amountMinor,
        String currency,
        Instant postedAt,
        List<LedgerEntry> entries
) {

    public LedgerTransaction {
        entries = List.copyOf(entries);
    }
}