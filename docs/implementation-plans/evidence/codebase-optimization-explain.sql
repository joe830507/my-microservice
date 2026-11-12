CREATE SCHEMA IF NOT EXISTS product_service; SET search_path TO product_service;
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
-- 圖片改存物件儲存的 object key（products/{productId}/{uuid}.{ext}），對外網址在回應時組
-- 換 CDN 網域或換成 AWS S3 時不用改資料
--
-- 原本的 url 都是手動填的外部網址，物件儲存裡沒有對應的檔案，無法轉換，直接刪除（只有開發資料）
-- 上架中的商品必須有主圖：刪掉圖片後改成 INACTIVE，重新上傳主圖後再上架
UPDATE product_service.product SET status = 'INACTIVE'
WHERE status = 'ACTIVE'
  AND EXISTS (SELECT 1 FROM product_service.product_image i WHERE i.product_id = product.id);

DELETE FROM product_service.product_image;

ALTER TABLE product_service.product_image DROP COLUMN url;
ALTER TABLE product_service.product_image ADD COLUMN object_key VARCHAR(500) NOT NULL;

-- 同一個檔案不能登記兩次
ALTER TABLE product_service.product_image ADD CONSTRAINT uq_product_image_object_key UNIQUE (object_key);

CREATE TABLE outbox_event (
    event_id UUID PRIMARY KEY,
    topic VARCHAR(120) NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    aggregate_id VARCHAR(120) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    traceparent VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_event_pending ON outbox_event (occurred_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_event_aggregate_pending ON outbox_event (topic, aggregate_id, occurred_at, event_id) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_event_published ON outbox_event (published_at) WHERE published_at IS NOT NULL;

ALTER TABLE outbox_event ADD COLUMN sequence_id BIGSERIAL;
CREATE INDEX idx_outbox_pending_sequence ON outbox_event (sequence_id) WHERE published_at IS NULL;

INSERT INTO category(id,name,slug,created_by,updated_by)
SELECT md5('category-'||g)::uuid, 'Category '||g, 'category-'||g, md5('audit')::uuid, md5('audit')::uuid FROM generate_series(1,100) g;
INSERT INTO product(id,sku,name,category_id,price,status,created_at,created_by,updated_by)
SELECT md5('product-'||g)::uuid, 'SKU-'||g, 'Product '||g, md5('category-'||(1+g%100))::uuid,
100, CASE WHEN g%3=0 THEN 'DRAFT' ELSE 'ACTIVE' END, now()-(g||' seconds')::interval,
md5('audit')::uuid,md5('audit')::uuid FROM generate_series(1,50000) g;
INSERT INTO outbox_event(event_id,topic,event_type,aggregate_id,occurred_at,payload,published_at)
SELECT md5('event-'||g)::uuid,'product.events','ProductUpdated','product-'||(g%1000),now(),'{}'::jsonb,
CASE WHEN g%5=0 THEN NULL ELSE now() END FROM generate_series(1,50000) g;
ANALYZE;
\echo BEFORE_PRODUCT_GLOBAL
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM product WHERE status='ACTIVE' ORDER BY created_at DESC,id LIMIT 20;
\echo BEFORE_PRODUCT_CATEGORY
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM product WHERE status='ACTIVE' AND category_id=md5('category-1')::uuid ORDER BY created_at DESC,id LIMIT 20;
\echo BEFORE_OUTBOX
EXPLAIN (ANALYZE, BUFFERS) SELECT e.event_id,e.topic,e.aggregate_id,e.payload::text,e.traceparent FROM outbox_event e
WHERE e.published_at IS NULL AND NOT EXISTS (SELECT 1 FROM outbox_event older WHERE older.topic=e.topic
AND older.aggregate_id=e.aggregate_id AND older.published_at IS NULL AND older.sequence_id<e.sequence_id)
ORDER BY e.sequence_id LIMIT 100 FOR UPDATE SKIP LOCKED;
DROP INDEX IF EXISTS idx_outbox_event_pending;
DROP INDEX IF EXISTS idx_outbox_event_aggregate_pending;
CREATE INDEX idx_outbox_event_aggregate_pending_seq
    ON outbox_event (topic, aggregate_id, sequence_id) WHERE published_at IS NULL;
CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
CREATE INDEX idx_product_status_created ON product (status, created_at DESC, id);
CREATE INDEX idx_product_category_status_created ON product (category_id, status, created_at DESC, id);
DROP INDEX IF EXISTS idx_product_status;
ANALYZE;
\echo AFTER_PRODUCT_GLOBAL
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM product WHERE status='ACTIVE' ORDER BY created_at DESC,id LIMIT 20;
\echo AFTER_PRODUCT_CATEGORY
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM product WHERE status='ACTIVE' AND category_id=md5('category-1')::uuid ORDER BY created_at DESC,id LIMIT 20;
\echo AFTER_OUTBOX
EXPLAIN (ANALYZE, BUFFERS) SELECT e.event_id,e.topic,e.aggregate_id,e.payload::text,e.traceparent FROM outbox_event e
WHERE e.published_at IS NULL AND NOT EXISTS (SELECT 1 FROM outbox_event older WHERE older.topic=e.topic
AND older.aggregate_id=e.aggregate_id AND older.published_at IS NULL AND older.sequence_id<e.sequence_id)
ORDER BY e.sequence_id LIMIT 100 FOR UPDATE SKIP LOCKED;
