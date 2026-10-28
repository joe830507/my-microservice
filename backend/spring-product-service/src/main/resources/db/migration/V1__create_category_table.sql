CREATE TABLE product_service.category (
    id          UUID          PRIMARY KEY,
    parent_id   UUID          REFERENCES product_service.category(id) ON DELETE RESTRICT,
    name        VARCHAR(100)  NOT NULL,
    slug        VARCHAR(100)  NOT NULL UNIQUE,
    description TEXT,
    sort_order  INT           NOT NULL DEFAULT 0,
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    version     BIGINT        NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by  UUID          NOT NULL,
    updated_by  UUID          NOT NULL,

    CONSTRAINT chk_category_not_self_parent CHECK (parent_id <> id),
    CONSTRAINT uq_category_parent_name UNIQUE NULLS NOT DISTINCT (parent_id, name)
);

CREATE INDEX idx_category_parent ON product_service.category(parent_id);