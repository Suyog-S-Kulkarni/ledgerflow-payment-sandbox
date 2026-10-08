package payments.ledger.model;

public enum EntryDirection {

    DEBIT,
    CREDIT;

    public EntryDirection opposite() {
        return switch (this) {
            case DEBIT -> CREDIT;
            case CREDIT -> DEBIT;
        };
    }
}