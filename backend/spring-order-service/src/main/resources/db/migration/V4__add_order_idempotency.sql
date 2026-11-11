-- 建單的冪等鍵（SEC-10）：同一個使用者用同一個 Idempotency-Key 重送，回同一張訂單，不會建立第二張、也不會再預扣庫存一次
-- idempotency_hash 是請求內容（商品與數量）的指紋：同一個 key 搭配不同的內容要拒絕，不能默默回傳另一份內容的訂單
-- 沒帶 key 的訂單兩個欄位都是 NULL（舊訂單、其他來源）
ALTER TABLE order_service.orders
    ADD COLUMN idempotency_key  VARCHAR(64),
    ADD COLUMN idempotency_hash VARCHAR(64),
    ADD CONSTRAINT chk_orders_idempotency_pair
        CHECK ((idempotency_key IS NULL) = (idempotency_hash IS NULL));

-- 唯一性限定在同一個使用者：不同使用者剛好用到同樣的 key 不會互相影響，也不能靠猜 key 取得別人的訂單
-- 併發的重送由這個唯一約束擋下，輸的那個請求會釋放自己預扣的庫存並回傳贏的那一張
CREATE UNIQUE INDEX uq_orders_user_idempotency_key
    ON order_service.orders (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
