# vue-management Step 12：Playwright E2E

> 屬於 [vue-management 實作計畫](README.md)

對著**真的後端**，在**真的瀏覽器（Chrome）**裡操作後台：登入 → 建立商品 → 上傳主圖 → 上架。檔案在 `frontend/vue-management/`：`playwright.config.ts`、`e2e/`、`tsconfig.e2e.json`。

## 怎麼跑

```bash
# 1. 先啟動整套後端（Gateway :8080 與各服務、資料庫、物件儲存）
# 2. 管理員帳密用環境變數傳入，不寫進 repo（本機開發用的值在 backend/spring-user-service/secrets/dev.env）
cd frontend/vue-management
export E2E_ADMIN_EMAIL=...  E2E_ADMIN_PASSWORD=...
npm run test:e2e
```

- 預設自己啟動 Vite dev server（**:5183**，`--strictPort`），`/api` 由 Vite proxy 轉給 Gateway。用專屬連接埠是因為平常開發的 :5173 可能被別的程式佔用（這次就遇到佔用的程式回 404），不要誤連
- 預設用本機安裝的 Chrome（`channel: 'chrome'`），不用另外下載瀏覽器；CI 上設 `E2E_CHANNEL=` 改用 Playwright 自己的 Chromium（先 `npx playwright install chromium`）
- **對著 nginx 映像檔跑（有 CSP 與安全標頭的版本）**：

  ```bash
  docker run -d --name vm-e2e -p 8092:8080 -e API_GATEWAY_URL=http://host.docker.internal:8080 vue-management:0.1.0
  E2E_BASE_URL=http://localhost:8092 npm run test:e2e
  ```

  這時 `e2e/support/test.ts` 的檢查才有意義：每個測試結束時，頁面不能有 CSP 違規（`Content Security Policy`、`Refused to ...`）或未捕捉的錯誤

## 測試內容

| 檔案 | 測試 |
| ---- | ---- |
| `auth.spec.ts` | 沒有登入進入後台頁面 → 導到登入頁並帶 `redirect`；帳密錯誤顯示「Email 或密碼錯誤」；管理員登入後回到原本要去的頁面；一般顧客登入被拒（「此帳號沒有後台管理權限」）；重新整理後用 refresh token 恢復登入 |
| `product-flow.spec.ts` | **計畫的主流程**：建立商品（草稿，分類用樹狀選單選）→ 還沒有主圖就上架被擋（「上架前要先設定主圖」）→ 上傳主圖（瀏覽器直接上傳到物件儲存，圖片真的載入）→ 上架 → 後端確認 `ACTIVE`、商店（匿名）看得到且有主圖網址 → 下架後商店看不到 |

## 設計決定與遇到的問題

- **登入限流**：Gateway 的登入限流依來源 IP（約每分鐘 10 次，每 6 秒補 1 次）。一開始每個測試各登入一次，連跑幾次就會 429，失敗訊息還五花八門（有時是找不到元素、有時是訊息不對）。現在一次執行約 5 次登入；API 登入遇到 429 會等 7 秒重試
- **refresh token 是單次使用的**（每次 refresh 都會換新、舊的立即失效）。一度想用一個 setup 專案登入一次、存 `storageState` 給所有測試共用，結果「重新整理後仍然登入」的測試先用掉那個 token，主流程再用就失效、被導回登入頁。改成：每個需要登入狀態的測試，各自用 API 登入拿到自己的 refresh token，放進瀏覽器的 localStorage（`startSignedIn`）。注入的腳本每次頁面載入都會執行，所以只在 localStorage 還沒有時才設定，否則重新整理會把已經輪替的新 token 蓋回舊的
- **等頁面載入完再填表單**：`/products/new` 在分類等資料載入後會重設表單，測試填得比載入快，SKU 欄位會被清空。真人來不及打字，所以不是應用程式的缺陷，測試等 `networkidle` 並確認欄位值有保留
- **不留垃圾資料**（商品沒有刪除 API）：
  - E2E 專用分類 `e2e-playwright` 每次共用同一個，測試開始時確保存在且啟用，結束後**停用**，商店的分類列不會出現
  - 商品結束時**下架**（不是封存，封存不可逆），商店看不到；但後台商品列表每次執行會多一件「E2E 自動化測試商品」（狀態：已下架），可以用狀態篩選過濾
  - 一般顧客帳號固定 `e2e-customer@example.com`（已註冊回 409 當成成功），不會每次多一個
- 型別檢查：`tsconfig.e2e.json`（含 DOM 型別，因為 `page.evaluate` 的回呼在瀏覽器裡執行）加進 `vue-tsc --build` 的參考；原本 `tsconfig.node.json` 只涵蓋 `playwright.config.*`，不包含 `e2e/`

## 驗證

- Vite dev server 上連續 **3 次**全部通過（6 個測試，每次約 15 秒）
- **對著 nginx 映像檔（嚴格 CSP）通過**：6 個測試全過、沒有 CSP 違規。包含 Element Plus 的樹狀選單與訊息提示、**圖片直傳物件儲存（`connect-src`）與圖片顯示（`img-src`）**。這是 [vue-management Step 11](step-11-deployment.md) 留下的「CSP 沒有在瀏覽器裡驗證」缺口
- 確認 CSP 偵測有效（不是偵測不到才看起來沒違規）：故意在頁面塞入內嵌腳本與外部圖片，瀏覽器確實擋下並回報 `Executing inline script violates the following Content Security Policy directive 'script-src 'self''`，訊息符合偵測的規則（確認後這個探測測試已刪除）
- 既有檢查不受影響：261 個單元測試、build、oxlint、eslint、`vue-tsc --build`（含 e2e）、prettier 全過

## 還沒做的

- 沒有放進 CI（需要整套後端與資料庫）；Playwright 的報告與追蹤檔（`test-results/`）已加進 `.gitignore`
- 只涵蓋計畫的主流程與登入；分類、庫存、訂單、使用者列表的畫面沒有 E2E（單元測試與先前對真實後端的 API 檢查有涵蓋）
- 只用 Chrome 一個瀏覽器
