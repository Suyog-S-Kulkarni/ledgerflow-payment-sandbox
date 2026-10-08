package payments.ledger.model;

/**
 * Identifies the business purpose of a ledger account.
 *
 * Each account code has a fixed accounting classification.
 */
public enum LedgerAccountCode {

    PROCESSOR_RECEIVABLE(LedgerAccountType.ASSET),

    MERCHANT_PAYABLE(LedgerAccountType.LIABILITY);

    private final LedgerAccountType accountType;

    LedgerAccountCode(LedgerAccountType accountType) {
        this.accountType = accountType;
    }

    public LedgerAccountType accountType() {
        return accountType;
    }

    public EntryDirection normalBalanceDirection() {
        return accountType.normalBalanceDirection();
    }
}