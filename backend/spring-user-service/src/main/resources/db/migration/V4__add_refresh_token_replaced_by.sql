-- 輪替時記錄換成哪一個新 token，用來判斷重複使用的寬限期：
--   - 只有「因輪替而撤銷」的 token 有值；登出、帳號停用、重複使用偵測撤銷的都是 NULL，不給寬限
--   - 寬限期內還要確認新 token 仍有效，避免整組被撤銷後，舊 token 又把登入狀態救回來
-- 被指向的 token 刪除時（之後的過期資料清除）設為 NULL，不連帶刪除
ALTER TABLE user_service.refresh_token
    ADD COLUMN replaced_by UUID REFERENCES user_service.refresh_token(id) ON DELETE SET NULL;
