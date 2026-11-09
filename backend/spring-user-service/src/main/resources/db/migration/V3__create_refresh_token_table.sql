-- refresh token：隨機字串（opaque），可在伺服器端撤銷
-- 只存 SHA-256 雜湊值，DB 外洩時拿不到可用的 token
CREATE TABLE user_service.refresh_token (
    id           UUID          PRIMARY KEY,
    -- 使用者被刪除時一併刪除，不會留下孤兒 token
    user_id      UUID          NOT NULL REFERENCES user_service.users(id) ON DELETE CASCADE,
    -- SHA-256 的 hex，固定 64 字元
    token_hash   VARCHAR(64)   NOT NULL,
    expires_at   TIMESTAMPTZ   NOT NULL,
    -- NULL 代表仍有效；輪替、重複使用偵測、停用帳號時填入
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- 依雜湊值查詢；唯一索引同時防止極小機率的碰撞
    CONSTRAINT uq_refresh_token_token_hash UNIQUE (token_hash)
);

-- 撤銷某位使用者所有 token（重複使用偵測、停用帳號）時使用
CREATE INDEX idx_refresh_token_user_id ON user_service.refresh_token (user_id);
