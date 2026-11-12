-- 舊同步路徑尚未完成的付款先轉成事件，再移除補償欄位。
WITH pending AS (
    SELECT p.*, uuidv7() AS event_id FROM payment_service.payment p
    WHERE p.status = 'SUCCEEDED' AND p.order_notification = 'PENDING'
      AND NOT EXISTS (SELECT 1 FROM payment_service.outbox_event e
          WHERE e.event_type = 'PaymentSucceeded' AND e.payload->'payload'->>'paymentId' = p.id::text)
)
INSERT INTO payment_service.outbox_event(event_id, topic, event_type, aggregate_id, occurred_at, payload)
SELECT event_id, 'payment.events', 'PaymentSucceeded', order_id::text, COALESCE(completed_at, updated_at),
    jsonb_build_object('eventId', event_id, 'type', 'PaymentSucceeded', 'version', 1,
        'occurredAt', COALESCE(completed_at, updated_at), 'aggregateId', order_id,
        'payload', jsonb_build_object('paymentId', id, 'orderId', order_id, 'status', status, 'amount', amount))
FROM pending;

ALTER TABLE payment_service.payment DROP COLUMN order_notification;
