CREATE TABLE product_service.product (
    id              UUID PRIMARY KEY,
    sku             VARCHAR(64)    NOT NULL UNIQUE,
    name            VARCHAR(255)   NOT NULL,
    description     TEXT,
    category_id     UUID           REFERENCES product_service.category(id) ON DELETE RESTRICT,
    brand           VARCHAR(100),
    price           NUMERIC(12,2)  NOT NULL CHECK (price >= 0),
    original_price  NUMERIC(12,2),
    status          VARCHAR(20)    NOT NULL DEFAULT 'DRAFT',
    version         BIGINT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by      UUID           NOT NULL,
    updated_by      UUID           NOT NULL
);

CREATE INDEX idx_product_category ON product_service.product(category_id);
CREATE INDEX idx_product_status   ON product_service.product(status);