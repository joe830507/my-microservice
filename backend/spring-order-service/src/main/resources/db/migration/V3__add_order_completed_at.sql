-- 後台完成訂單：SHIPPED → COMPLETED 的時間
ALTER TABLE order_service.orders ADD COLUMN completed_at TIMESTAMPTZ;
