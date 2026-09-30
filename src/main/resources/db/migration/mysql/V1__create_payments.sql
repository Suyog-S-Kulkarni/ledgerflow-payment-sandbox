CREATE TABLE payments (
                          id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                          merchant_id CHAR(36)
                                 CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                          idempotency_key VARCHAR(100) NOT NULL,

                          request_hash VARCHAR(64) NOT NULL,

                          order_reference VARCHAR(64) NOT NULL,

                          amount_minor BIGINT NOT NULL,

                          currency VARCHAR(3) NOT NULL,

                          status VARCHAR(16) NOT NULL,

                          created_at DATETIME(6) NOT NULL,

                          updated_at DATETIME(6) NOT NULL,

                          PRIMARY KEY (id),

                          CONSTRAINT uq_merchant_idempotency
                              UNIQUE (merchant_id, idempotency_key),

                          CONSTRAINT chk_payment_amount
                              CHECK (amount_minor BETWEEN 1 AND 100000000),

                          CONSTRAINT chk_payment_currency
                              CHECK (currency = 'INR'),

                          CONSTRAINT chk_payment_status
                              CHECK (status IN ('CREATED', 'SUCCEEDED', 'FAILED'))
)
    ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_as_cs;

CREATE INDEX ix_payments_merchant_created
    ON payments (merchant_id, created_at);