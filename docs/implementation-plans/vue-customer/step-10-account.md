# vue-customer Step 10：帳號頁

> 屬於 [vue-customer 實作計畫](README.md)

- `/account`（`requiresAuth`）：顯示名稱、Email、註冊時間，連到「我的訂單」。頂部列已登入時的顯示名稱改成連到這頁
- 資料來源：先顯示登入時取得的 `auth.user`，進頁面再 `GET /users/me`（`auth.refreshUser`）更新；重新取得失敗就維持目前的資料，不顯示錯誤、不打斷頁面
- **登出所有裝置**（`DELETE /users/me/sessions`，`auth.logoutAll`）：
  - 在頁面內先確認（不用瀏覽器的 `confirm`），處理中按鈕停用、連點只送一次
  - **先讓伺服器撤銷成功，才清除本機狀態**，再回首頁；購物車保留。撤銷失敗時維持登入並說明「其他裝置可能仍然登入」，可以再試——如果先清了本機狀態，使用者會以為其他裝置也登出了，其實還登入著
  - 頁面上說明：已經發出去的 access token 在到期前（後端 `access-token-ttl: 15m`）仍然有效，這是無狀態 JWT 的限制，不是這個功能的 bug
  - 請求要帶 access token（不是 `skipAuth`）；本機的 refresh token 也已被撤銷，所以不再呼叫 `/auth/logout`
- 個人資料修改、改密碼：後端沒有 API，畫面不放（見計畫的「之後再處理」）

## 完成內容

**測試**（`npm run test:unit`，全部 330 個）：
- `AccountView.spec.ts`：未登入導到登入頁並帶 `redirect`、顯示資料、進頁面重新取得並更新、重新取得失敗仍顯示舊資料；登出所有裝置：先確認／返回不送請求、成功（帶 `Bearer` access token、清除狀態與 localStorage、回首頁、購物車保留、再進帳號頁被導到登入頁）、失敗（維持登入、說明其他裝置可能仍登入、可以再試）、連點只送一次
- `StoreLayout.spec.ts`：頂部列名稱連到 `/account`

`npm run build`、oxlint、eslint 通過。

**尚未用真的後端驗證**：測試用 MSW 模擬。要實際確認：`DELETE /users/me/sessions` 經 Gateway 後是否回 204、撤銷後別的裝置用舊 refresh token 是否真的被拒絕。
