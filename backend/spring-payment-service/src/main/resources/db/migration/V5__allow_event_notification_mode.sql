ALTER TABLE payment_service.payment DROP CONSTRAINT chk_payment_order_notification;
ALTER TABLE payment_service.payment ADD CONSTRAINT chk_payment_order_notification CHECK (order_notification IS NULL OR order_notification IN ('PENDING', 'DONE', 'REJECTED', 'NOT_REQUIRED'));
