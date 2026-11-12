CREATE INDEX idx_product_status_created ON product (status, created_at DESC, id);
CREATE INDEX idx_product_category_status_created ON product (category_id, status, created_at DESC, id);
DROP INDEX IF EXISTS idx_product_status;
