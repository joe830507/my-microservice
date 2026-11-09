-- 一個 SKU 一筆；sku 長度和 product-service 一致
-- available、reserved 的 CHECK 是最後防線：程式出錯也不會扣成負數
CREATE TABLE inventory_service.stock (
    sku         VARCHAR(64)   PRIMARY KEY,
    available   INTEGER       NOT NULL DEFAULT 0,
    reserved    INTEGER       NOT NULL DEFAULT 0,
    version     BIGINT        NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by  UUID          NOT NULL,
    updated_by  UUID          NOT NULL,

    CONSTRAINT chk_stock_available CHECK (available >= 0),
    CONSTRAINT chk_stock_reserved CHECK (reserved >= 0)
);

-- 一張訂單一筆；order_id 由 order-service 指定，也是冪等鍵
CREATE TABLE inventory_service.reservation (
    order_id    UUID          PRIMARY KEY,
    status      VARCHAR(20)   NOT NULL,
    version     BIGINT        NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by  UUID          NOT NULL,
    updated_by  UUID          NOT NULL,

    CONSTRAINT chk_reservation_status CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED'))
);

-- 預扣明細；同一張訂單的同一個 SKU 只有一筆（重複的 SKU 在預扣時先合併）
CREATE TABLE inventory_service.reservation_item (
    order_id  UUID         NOT NULL REFERENCES inventory_service.reservation(order_id) ON DELETE CASCADE,
    sku       VARCHAR(64)  NOT NULL REFERENCES inventory_service.stock(sku),
    qty       INTEGER      NOT NULL,

    CONSTRAINT pk_reservation_item PRIMARY KEY (order_id, sku),
    CONSTRAINT chk_reservation_item_qty CHECK (qty > 0)
);

-- 依 SKU 查有哪些預扣（排查庫存、之後的逾時釋放）；主鍵以 order_id 開頭，幫不上這種查詢
CREATE INDEX idx_reservation_item_sku ON inventory_service.reservation_item (sku);
