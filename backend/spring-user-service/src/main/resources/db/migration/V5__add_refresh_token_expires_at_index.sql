-- 排程清除過期 refresh token（RefreshTokenCleanupJob）用：DELETE ... WHERE expires_at < ? 不必掃整張表
CREATE INDEX idx_refresh_token_expires_at ON user_service.refresh_token (expires_at);
