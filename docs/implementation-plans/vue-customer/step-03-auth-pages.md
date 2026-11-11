# vue-customer Step 3：註冊、登入、登出

> 屬於 [vue-customer 實作計畫](README.md)

- `LoginView`、`RegisterView`：自己寫的表單（不用元件庫），欄位檢查放在 `features/auth/validation.ts`，規則和後端 `RegisterReqDto` 一致（Email 最多 254、密碼 8–72、顯示名稱 1–50）。密碼另外檢查 UTF-8 bytes ≤ 72：bcrypt 只取前 72 bytes，中文字元數合格但 bytes 可能超過（後端的 `@Size` 只算字元數）。登入只檢查有沒有填，不套用新密碼的規則
- 送出中停用按鈕，連點只送出一次
- 錯誤訊息依代碼：登入 `INVALID_CREDENTIALS`、`USER_DISABLED`、`TOO_MANY_LOGIN_ATTEMPTS`、`TOO_MANY_REQUESTS`；註冊 `EMAIL_ALREADY_EXISTS`、`TOO_MANY_REQUESTS`；其他顯示後端的 `detail`
- 註冊：`POST /auth/register`（回 201，沒有 token）→ 自動 `login`。若註冊成功但登入失敗，store 丟出 `REGISTERED_LOGIN_FAILED`，畫面說明「帳號已建立，請到登入頁登入」，避免使用者重複註冊
- `safeRedirect` 沿用後台，另外擋掉 `/register`；登入頁與註冊頁互相連結時保留 `redirect`
- 頂部列：未登入顯示「登入」（帶目前頁面的 `redirect`）、「註冊」；已登入顯示名稱與「登出」。登出後在需要登入的頁面會回首頁；購物車保留
- `main.ts` 啟動時背景呼叫 `restoreSession()`，重新整理後頂部列才會顯示已登入；和路由守衛共用同一次恢復

## 完成內容

**測試**（`npm run test:unit`，全部 102 個）：
- `validation.spec.ts`：各欄位邊界（8／72 字元、72 bytes、50 字元、空白）
- `messages.spec.ts`：登入、註冊錯誤代碼對應；`safeRedirect`
- `LoginView.spec.ts`：成功後回 `redirect`、外部網址回首頁、四種錯誤代碼、欄位驗證不發請求、連點只送一次
- `RegisterView.spec.ts`：送出的 body（去前後空白）、註冊後已登入並回 `redirect`、`EMAIL_ALREADY_EXISTS`、註冊成功但登入失敗、欄位驗證
- `StoreLayout.spec.ts`：未登入／已登入的頂部列、登出清除狀態、在需要登入的頁面登出後回首頁，再進去被導到登入頁

`npm run build`、oxlint、eslint 通過。

**尚未用真的後端驗證**：測試都用 MSW 模擬，還沒啟動 user-service 實際註冊、登入一次。
