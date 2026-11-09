-- ReservationExpiryJob 找建立超過一段時間仍是 RESERVED 的預扣
-- 只有 RESERVED 需要掃描（確認、釋放後就不會再變），用部分索引保持很小
CREATE INDEX idx_reservation_reserved_created ON inventory_service.reservation (created_at)
    WHERE status = 'RESERVED';
