-- Composite key allows the ledger to reference the exact
-- payment owner, currency and amount.
ALTER TABLE payments
    ADD CONSTRAINT uq_payment_ledger_reference
        UNIQUE (id, merchant_id, currency, amount_minor);


CREATE TABLE ledger_accounts (
                                 id CHAR(36)
                                        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                 merchant_id CHAR(36)
                                        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                 account_code VARCHAR(32) NOT NULL,
                                 currency VARCHAR(3) NOT NULL,
                                 created_at DATETIME(6) NOT NULL,

                                 PRIMARY KEY (id),

                                 CONSTRAINT uq_ledger_account_business_key
                                     UNIQUE (merchant_id, account_code, currency),

                                 CONSTRAINT uq_ledger_account_reference
                                     UNIQUE (id, merchant_id, currency, account_code),

                                 CONSTRAINT chk_ledger_account_code
                                     CHECK (
                                         account_code IN (
                                                          'PROCESSOR_RECEIVABLE',
                                                          'MERCHANT_PAYABLE'
                                             )
                                         ),

                                 CONSTRAINT chk_ledger_account_currency
                                     CHECK (currency = 'INR')
)
    ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_as_cs;


CREATE TABLE ledger_transactions (
                                     id CHAR(36)
                                            CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                     merchant_id CHAR(36)
                                            CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                     payment_id CHAR(36)
                                            CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                     posting_type VARCHAR(32) NOT NULL,
                                     amount_minor BIGINT NOT NULL,
                                     currency VARCHAR(3) NOT NULL,
                                     posted_at DATETIME(6) NOT NULL,

                                     PRIMARY KEY (id),

                                     CONSTRAINT uq_ledger_payment_posting
                                         UNIQUE (payment_id, posting_type),

                                     CONSTRAINT uq_ledger_transaction_reference
                                         UNIQUE (id, merchant_id, currency, amount_minor),

                                     CONSTRAINT fk_ledger_transaction_payment
                                         FOREIGN KEY (
                                                      payment_id,
                                                      merchant_id,
                                                      currency,
                                                      amount_minor
                                             )
                                             REFERENCES payments (
                                                                  id,
                                                                  merchant_id,
                                                                  currency,
                                                                  amount_minor
                                                 ),

                                     CONSTRAINT chk_ledger_posting_type
                                         CHECK (posting_type = 'PAYMENT_SUCCEEDED'),

                                     CONSTRAINT chk_ledger_transaction_amount
                                         CHECK (amount_minor BETWEEN 1 AND 100000000),

                                     CONSTRAINT chk_ledger_transaction_currency
                                         CHECK (currency = 'INR')
)
    ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_as_cs;


CREATE TABLE ledger_entries (
                                id CHAR(36)
                                       CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                transaction_id CHAR(36)
                                       CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                merchant_id CHAR(36)
                                       CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                account_id CHAR(36)
                                       CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                account_code VARCHAR(32) NOT NULL,
                                direction VARCHAR(6) NOT NULL,
                                amount_minor BIGINT NOT NULL,
                                currency VARCHAR(3) NOT NULL,

                                PRIMARY KEY (id),

    -- This milestone permits exactly one debit and one credit
    -- per successful-payment posting.
    -- The service inserts both in one transaction.
                                CONSTRAINT uq_ledger_entry_direction
                                    UNIQUE (transaction_id, direction),

                                CONSTRAINT fk_ledger_entry_transaction
                                    FOREIGN KEY (
                                                 transaction_id,
                                                 merchant_id,
                                                 currency,
                                                 amount_minor
                                        )
                                        REFERENCES ledger_transactions (
                                                                        id,
                                                                        merchant_id,
                                                                        currency,
                                                                        amount_minor
                                            ),

                                CONSTRAINT fk_ledger_entry_account
                                    FOREIGN KEY (
                                                 account_id,
                                                 merchant_id,
                                                 currency,
                                                 account_code
                                        )
                                        REFERENCES ledger_accounts (
                                                                    id,
                                                                    merchant_id,
                                                                    currency,
                                                                    account_code
                                            ),

                                CONSTRAINT chk_ledger_entry_amount
                                    CHECK (amount_minor BETWEEN 1 AND 100000000),

                                CONSTRAINT chk_ledger_entry_currency
                                    CHECK (currency = 'INR'),

                                CONSTRAINT chk_ledger_entry_account_direction
                                    CHECK (
                                        (
                                            account_code = 'PROCESSOR_RECEIVABLE'
                                                AND direction = 'DEBIT'
                                            )
                                            OR
                                        (
                                            account_code = 'MERCHANT_PAYABLE'
                                                AND direction = 'CREDIT'
                                            )
                                        )
)
    ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_as_cs;


CREATE INDEX ix_ledger_transactions_merchant_posted
    ON ledger_transactions (merchant_id, posted_at);

CREATE INDEX ix_ledger_entries_merchant_account
    ON ledger_entries (merchant_id, account_id);