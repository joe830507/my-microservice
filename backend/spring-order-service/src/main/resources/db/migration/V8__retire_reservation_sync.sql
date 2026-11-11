-- 尚未同步的舊狀態保留為 outbox 事件；已存在的事件不再重建。
WITH pending AS (
    SELECT o.*, uuidv7() AS event_id,
        CASE WHEN reservation_status = 'CONFIRMED' THEN 'OrderPaid' ELSE 'OrderCancelled' END AS event_type
    FROM order_service.orders o
    WHERE NOT reservation_synced AND reservation_status IN ('CONFIRMED', 'RELEASED')
), missing AS (
    SELECT p.* FROM pending p WHERE NOT EXISTS (
        SELECT 1 FROM order_service.outbox_event e
        WHERE e.aggregate_id = p.id::text AND e.event_type = p.event_type)
)
INSERT INTO order_service.outbox_event(event_id, topic, event_type, aggregate_id, occurred_at, payload)
SELECT event_id, 'order.events', event_type, id::text, updated_at,
    jsonb_build_object('eventId', event_id, 'type', event_type, 'version', 1,
        'occurredAt', updated_at, 'aggregateId', id, 'payload', jsonb_build_object('orderId', id))
FROM missing;

ALTER TABLE order_service.orders DROP COLUMN reservation_synced;
