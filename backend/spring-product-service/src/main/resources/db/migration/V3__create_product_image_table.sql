CREATE TABLE product_service.product_image (
    id          UUID          PRIMARY KEY,
    product_id  UUID          NOT NULL REFERENCES product_service.product(id) ON DELETE CASCADE,
    url         VARCHAR(500)  NOT NULL,
    alt_text    VARCHAR(255),
    sort_order  INT           NOT NULL DEFAULT 0,
    is_primary  BOOLEAN       NOT NULL DEFAULT FALSE,
    version     BIGINT        NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by  UUID          NOT NULL,
    updated_by  UUID          NOT NULL
);

CREATE INDEX idx_product_image_product ON product_service.product_image(product_id);

CREATE UNIQUE INDEX uq_product_image_one_primary
    ON product_service.product_image(product_id)
    WHERE is_primary;