-- 後台使用者列表（GET /api/v1/users）用的索引

-- keyword 以 lower(欄位) LIKE '%keyword%' 比對，前後都有萬用字元，一般 B-tree 索引用不上
-- pg_trgm 的 GIN 索引支援這種模糊比對；索引的運算式必須和查詢的 lower(...) 完全一致才會被使用
-- pg_trgm 從 PostgreSQL 13 起是 trusted extension，資料庫擁有者不需 superuser 也能建立
-- 注意：keyword 少於 3 個字元時無法組出 trigram，仍會退回較慢的掃描
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_users_email_trgm
    ON user_service.users USING gin (lower(email) gin_trgm_ops);

CREATE INDEX idx_users_display_name_trgm
    ON user_service.users USING gin (lower(display_name) gin_trgm_ops);

-- 排序：ORDER BY 主要欄位, id LIMIT n，有對應的索引就能直接依序讀前 n 筆，不必先把整張表排序
-- 預設排序是 created_at DESC；B-tree 可以反向掃描，不需要另外建 DESC 索引
-- 依 email 排序已有 uq_users_email 的唯一索引可用
CREATE INDEX idx_users_created_at_id
    ON user_service.users (created_at, id);

CREATE INDEX idx_users_display_name_id
    ON user_service.users (display_name, id);
