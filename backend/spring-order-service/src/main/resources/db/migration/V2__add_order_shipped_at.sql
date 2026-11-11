-- 後台出貨：PAID → SHIPPED 的時間
ALTER TABLE order_service.orders ADD COLUMN shipped_at TIMESTAMPTZ;
