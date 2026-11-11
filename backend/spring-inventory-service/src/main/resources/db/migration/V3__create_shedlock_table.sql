-- 排程的分散式鎖（ShedLock，階段 B4）：多個副本時每個排程每一輪只有一個副本執行
-- 欄位名稱與型別是 ShedLock 規定的；lock_until 到期前其他副本拿不到鎖，副本當機時鎖會在 lockAtMostFor 之後自動失效
CREATE TABLE inventory_service.shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
