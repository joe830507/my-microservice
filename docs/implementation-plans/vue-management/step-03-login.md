# vue-management Step 3：登入

> 屬於 [vue-management 實作計畫](README.md)

- 登入頁、`useAuthStore`（token、目前使用者）
- request 攔截器帶 `Authorization: Bearer`；response 攔截器遇到 401 → refresh → 重送原請求
- 登出：呼叫 `/auth/logout` 後清除狀態
- 路由守衛：未登入 → 登入頁（帶 redirect）；非 ADMIN → 拒絕登入

**測試**：同時兩個請求收到 401 只發一次 refresh，兩個都成功重送；refresh 失敗導回登入頁；非 ADMIN 登入被拒；登入限流（429）顯示提示。

## 完成內容（2026-11-10）

| 檔案 | 內容 |
| ---- | ---- |
| `src/features/auth/types.ts` | 對應 user-service 的 `LoginReqDto`、`TokenResDto`、`GetMeResDto` |
| `src/features/auth/api.ts` | `login`、`refresh`、`logout`、`getMe`；前三個帶 `skipAuth` |
| `src/features/auth/store.ts` | `useAuthStore`：`login`、`logout`、`refreshAccessToken`、`restoreSession` |
| `src/features/auth/interceptors.ts` | `installAuthInterceptors`：帶 token；401 → refresh → 重送一次 |
| `src/features/auth/messages.ts` | 依錯誤代碼顯示登入失敗訊息；`safeRedirect` 只接受站內路徑 |
| `src/features/auth/LoginView.vue` | 登入頁（Element Plus 表單、欄位驗證、錯誤提示） |
| `src/router/index.ts` | `/login` 路由（`meta.public`）、`installAuthGuard` |
| `src/api/http.ts` | 新增請求設定 `skipAuth`、`authRetried`（module augmentation） |
| `src/main.ts` | 註冊順序：pinia → auth 攔截器 → router |
| `src/views/HomeView.vue` | 暫時顯示使用者名稱與登出按鈕；Step 4 移到頂部列 |

後端的對應（實作前確認過程式碼）：

| 情況 | 來源 | 回應 |
| ---- | ---- | ---- |
| access token 缺少、無效或過期 | Gateway `ProblemDetailAuthenticationEntryPoint` | 401 `UNAUTHENTICATED` |
| 帳密錯誤 | user-service | 401 `INVALID_CREDENTIALS` |
| 帳號停用 | user-service | 403 `USER_DISABLED` |
| 同一個 email 失敗太多次 | user-service | 429 `TOO_MANY_LOGIN_ATTEMPTS` |
| 同一個 IP 登入太頻繁 | Gateway `ClientIpRateLimit` | 429 `TOO_MANY_REQUESTS` |
| refresh token 失效或被重複使用 | user-service | 401 `REFRESH_TOKEN_INVALID` |

### 設計

- **token 存放**：access token 只放 Pinia（記憶體）；refresh token 另外寫進 localStorage（key `vue-management.refreshToken`），讀寫都包 try/catch，無痕模式讀寫失敗時只是重新整理後要重新登入
- **登入流程**：`/auth/login` → 保存 token → `/users/me` → 不是 `ADMIN` 就呼叫 `/auth/logout` 撤銷剛拿到的 refresh token，丟出 `NOT_ADMIN`；`/users/me` 失敗也一樣登出，不留下半登入的狀態
- **refresh 同時只發一次**：store 保留進行中的 refresh Promise，同時收到 401 的請求共用它
- **請求送出後 token 已被換新**：攔截器比對這個請求帶的 token 和 store 目前的 token，不同就直接用新 token 重送，不再 refresh（refresh token 每次都會輪替，多 refresh 一次只是浪費）
- **只重送一次**：重送的請求帶 `authRetried`，再收到 401 就直接失敗，避免無限循環
- **skipAuth**：登入、refresh、登出本身不帶 token，收到 401 也不觸發 refresh（帳密錯誤的 401 不能被當成 token 過期）
- **refresh 失敗的處理**：
  - 後端回 4xx（`REFRESH_TOKEN_INVALID`）：清除登入狀態，呼叫 `onSessionExpired` 導回登入頁並帶 redirect
  - 網路中斷、5xx：保留 refresh token，原請求失敗，不導回登入頁；refresh token 可能還有效，之後的請求可以再試
- **重新整理頁面**：access token 不見了，路由守衛呼叫 `restoreSession()`，用 refresh token 換新後再取 `/users/me`；同時被觸發多次也只恢復一次
- **登出**：先清除本機狀態再呼叫 `/auth/logout`，伺服器端撤銷失敗不影響本機登出
- **redirect 只接受站內路徑**：`safeRedirect` 拒絕外部網址、`//`、`/\`、相對路徑與 `/login` 本身，避免開放重新導向
- **攔截器的順序**：`http.ts` 的錯誤轉換最先註冊，auth 的 response 攔截器之後註冊，收到的已經是 `ApiError`；原本的請求設定從 `ApiError.cause`（AxiosError）的 `config` 取得
- **避免循環 import**：`http.ts` 不 import auth；攔截器由 `main.ts` 呼叫 `installAuthInterceptors` 掛上，回傳移除函式，測試時每個案例各自掛上、移除
- 目錄結構：計畫原本寫 `src/app/`，實際沿用 create-vue 產生的 `src/router/`，README 的目錄結構已更新

### 測試

| 測試檔 | 案例 |
| ------ | ---- |
| `features/auth/__tests__/interceptors.spec.ts` | 9 個：帶 Bearer token、`skipAuth` 不帶、同時兩個 401 只 refresh 一次且都重送成功、token 已被換新時不 refresh、refresh 被拒絕 → 清除並導回登入頁、沒有 refresh token → 導回登入頁、refresh 網路中斷 → 保留登入狀態、重送後仍 401 不再重試、403 不觸發 refresh |
| `features/auth/__tests__/store.spec.ts` | 10 個：ADMIN 登入成功、非 ADMIN 被拒並撤銷 refresh token、帳密錯誤不觸發 refresh、登入限流 429、恢復登入狀態（同時兩次只 refresh 一次）、沒有 refresh token、refresh token 失效、恢復後不是 ADMIN、登出、`/auth/logout` 失敗仍清除 |
| `features/auth/__tests__/messages.spec.ts` | 16 個：各錯誤代碼的訊息、`safeRedirect` 接受與拒絕的路徑 |
| `features/auth/__tests__/LoginView.spec.ts` | 6 個：成功後回到 redirect、外部 redirect 回首頁、429 提示、非 ADMIN 提示、帳密錯誤提示、欄位沒填不發請求 |
| `router/__tests__/guard.spec.ts` | 5 個：未登入導到登入頁並帶 redirect、登入頁不需登入、有 refresh token 時恢復登入、refresh token 失效導到登入頁、已登入進登入頁回首頁 |

- MSW 都設 `onUnhandledRequest: 'error'`：「不應該發出 refresh」之類的案例，不註冊 handler 就能驗證沒有多發請求
- 共用的假資料在 `features/auth/__tests__/fixtures.ts`（`tokens(n)`、`me(roles)`、`problem(status, code)`）
- `App.spec.ts` 的冒煙測試加上 pinia：首頁會用到 auth store

### 遇到的問題

- **`problem()` 的回傳型別**：一開始標成 `HttpResponse<never>` 再轉型，`vue-tsc` 報型別不相容（vitest 不做型別檢查所以測試照樣通過）。拿掉標註，讓 TypeScript 推斷即可
- **還沒接真正的後端測試**：寫完時 Gateway 沒有啟動，登入流程只用 MSW 驗證過；之後啟動 compose 時要用 `npm run dev` 實際登入一次（含重新整理頁面後恢復登入、登出）

## 最終檢查

vitest 73 個通過（新增 46 個）、`npm run build`（含 `vue-tsc`）通過、eslint、oxlint、prettier 都沒有問題。
