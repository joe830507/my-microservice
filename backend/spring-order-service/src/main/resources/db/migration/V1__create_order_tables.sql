-- order 是 PostgreSQL 保留字，資料表改用 orders
-- id 由 order-service 產生（UUIDv7），也是 inventory 預扣的冪等鍵
CREATE TABLE order_service.orders (
    id                  UUID           PRIMARY KEY,
    user_id             UUID           NOT NULL,
    status              VARCHAR(20)    NOT NULL,
    -- inventory 那邊應該要是什麼狀態；reservation_synced = false 代表還沒成功通知 inventory，由排程重試
    reservation_status  VARCHAR(20)    NOT NULL,
    reservation_synced  BOOLEAN        NOT NULL,
    total_amount        NUMERIC(14,2)  NOT NULL,
    paid_at             TIMESTAMPTZ,
    cancelled_at        TIMESTAMPTZ,
    version             BIGINT         NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by          UUID           NOT NULL,
    updated_by          UUID           NOT NULL,

    CONSTRAINT chk_orders_status
        CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'SHIPPED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT chk_orders_reservation_status
        CHECK (reservation_status IN ('RESERVED', 'CONFIRMED', 'RELEASED')),
    CONSTRAINT chk_orders_total_amount CHECK (total_amount >= 0)
);

-- 商品名稱、價格是下單當下的快照；長度、精度和 product-service 一致
CREATE TABLE order_service.order_item (
    order_id      UUID           NOT NULL REFERENCES order_service.orders(id) ON DELETE CASCADE,
    product_id    UUID           NOT NULL,
    sku           VARCHAR(64)    NOT NULL,
    product_name  VARCHAR(255)   NOT NULL,
    unit_price    NUMERIC(12,2)  NOT NULL,
    qty           INTEGER        NOT NULL,
    subtotal      NUMERIC(14,2)  NOT NULL,

    CONSTRAINT pk_order_item PRIMARY KEY (order_id, product_id),
    CONSTRAINT chk_order_item_unit_price CHECK (unit_price >= 0),
    CONSTRAINT chk_order_item_qty CHECK (qty > 0),
    CONSTRAINT chk_order_item_subtotal CHECK (subtotal >= 0)
);

-- 使用者自己的訂單列表（最新的在前）；id 當 tie-breaker
CREATE INDEX idx_orders_user_created ON order_service.orders (user_id, created_at DESC, id DESC);

-- 後台列表依狀態篩選，以及付款逾時排程找 PENDING_PAYMENT 且建立超過一定時間的訂單
CREATE INDEX idx_orders_status_created ON order_service.orders (status, created_at);

-- 補做庫存同步的排程只看還沒同步的；正常情況幾乎沒有，用部分索引保持很小
CREATE INDEX idx_orders_reservation_unsynced ON order_service.orders (updated_at)
    WHERE reservation_synced = false;
