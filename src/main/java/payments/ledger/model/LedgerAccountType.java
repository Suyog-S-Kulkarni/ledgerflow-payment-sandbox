package payments.ledger.model;

/**
 * Accounting classification of a ledger account.
 *
 * The normal balance direction identifies which entry
 * direction generally increases the account's balance.
 */
public enum LedgerAccountType {

    ASSET(EntryDirection.DEBIT),
    LIABILITY(EntryDirection.CREDIT),
    EQUITY(EntryDirection.CREDIT),
    REVENUE(EntryDirection.CREDIT),
    EXPENSE(EntryDirection.DEBIT);

    private final EntryDirection normalBalanceDirection;

    LedgerAccountType(EntryDirection normalBalanceDirection) {
        this.normalBalanceDirection = normalBalanceDirection;
    }

    public EntryDirection normalBalanceDirection() {
        return normalBalanceDirection;
    }
}