-- id 由 payment-service 產生（UUIDv7）；amount 一律取自 order-service
CREATE TABLE payment_service.payment (
    id                  UUID           PRIMARY KEY,
    order_id            UUID           NOT NULL,
    user_id             UUID           NOT NULL,
    amount              NUMERIC(14,2)  NOT NULL,
    status              VARCHAR(20)    NOT NULL,
    -- 只有 SUCCEEDED 才有值：PENDING = 還沒通知 order-service 成功，由排程重試
    order_notification  VARCHAR(20),
    completed_at        TIMESTAMPTZ,
    version             BIGINT         NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by          UUID           NOT NULL,
    updated_by          UUID           NOT NULL,

    CONSTRAINT chk_payment_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_payment_order_notification
        CHECK (order_notification IS NULL OR order_notification IN ('PENDING', 'DONE', 'REJECTED')),
    -- 成功的付款一定要有通知狀態，其他狀態一定沒有
    CONSTRAINT chk_payment_notification_only_when_succeeded
        CHECK ((status = 'SUCCEEDED') = (order_notification IS NOT NULL)),
    CONSTRAINT chk_payment_amount CHECK (amount >= 0)
);

-- 同一張訂單同時只能有一筆進行中或成功的付款；FAILED 不算，可以重新付款
-- 兩個請求同時建立付款時，後到的被擋下
CREATE UNIQUE INDEX uq_payment_order_active ON payment_service.payment (order_id)
    WHERE status IN ('PENDING', 'SUCCEEDED');

-- 查某張訂單的付款
CREATE INDEX idx_payment_order ON payment_service.payment (order_id);

-- 重試通知 order-service 的排程只看還沒通知成功的；正常情況幾乎沒有，用部分索引保持很小
CREATE INDEX idx_payment_notification_pending ON payment_service.payment (updated_at)
    WHERE order_notification = 'PENDING';
