package payments.ledger.model;

import java.math.BigDecimal;
import java.util.UUID;

public record LedgerBalance(
        UUID accountId,
        LedgerAccountCode accountCode,
        LedgerAccountType accountType,
        String currency,
        BigDecimal debitTotalMinor,
        BigDecimal creditTotalMinor,
        BigDecimal balanceMinor
) {
}