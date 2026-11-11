# vue-customer Step 12：Playwright E2E

> 屬於 [vue-customer 實作計畫](README.md)

對著**真的後端**，在**真的瀏覽器（Chrome）**裡操作商店前台：瀏覽 → 加入購物車 → 註冊 → 結帳 → 付款成功 → 我的訂單看到已付款；搭配後台出貨後，前台看到已出貨。檔案在 `frontend/vue-customer/`：`playwright.config.ts`、`e2e/`、`tsconfig.e2e.json`。

## 怎麼跑

```bash
# 1. 先啟動整套後端（Gateway :8080 與各服務、資料庫、物件儲存）
# 2. 管理員帳密用環境變數傳入，不寫進 repo（本機開發用的值在 backend/spring-user-service/secrets/dev.env）
#    E2E 用管理員只是為了準備與清理資料（建立 E2E 專用商品、調整庫存、出貨），不是測試的對象
cd frontend/vue-customer
export E2E_ADMIN_EMAIL=...  E2E_ADMIN_PASSWORD=...
npm run test:e2e
```

- 預設自己啟動 Vite dev server（**:5184**，`--strictPort`），`/api` 由 Vite proxy 轉給 Gateway；用專屬連接埠，不和平常開發的 :5174 衝突
- 預設用本機安裝的 Chrome（`channel: 'chrome'`），不用另外下載瀏覽器；CI 上設 `E2E_CHANNEL=` 改用 Playwright 自己的 Chromium（先 `npx playwright install chromium`）
- **對著 nginx 映像檔跑（有 CSP 與安全標頭的版本）**：

  ```bash
  docker run -d --name vc-e2e -p 8090:8080 -e API_GATEWAY_URL=http://host.docker.internal:8080 vue-customer:0.1.0
  E2E_BASE_URL=http://localhost:8090 npm run test:e2e
  ```

  這時 `e2e/support/test.ts` 的檢查才有意義：每個測試結束時，頁面不能有 CSP 違規或未捕捉的錯誤

## 測試內容（10 個）

| 檔案 | 測試 |
| ---- | ---- |
| `purchase-flow.spec.ts` | **計畫的主流程**：搜尋 → 商品詳情 → 加入 2 件 → 購物車（總計 NT$ 1,600）→ 前往結帳，沒登入被導到登入頁 → 改去註冊（`redirect` 保留）→ 註冊成功回到結帳頁 → 送出訂單（購物車清空）→ 付款頁（金額與倒數）→ 付款成功 → 我的訂單顯示已付款 → 庫存少 2 件 → **後台出貨（API）後，前台訂單詳情顯示已出貨**且沒有取消按鈕 |
| `browse.spec.ts` | 搜尋（卡片的名稱、價格、折扣 `-20%`、主圖載入）；分類頁；分類或商品不存在顯示 404；商品詳情（庫存、數量超過上限被拉回、加入購物車後件數更新、重新整理仍在）；**手機寬度 390px 沒有水平捲軸**（首頁、搜尋、詳情、登入） |
| `cart-edge.spec.ts` | 結帳時庫存不夠：訊息指出是哪個商品、購物車不變、按鈕可再按；購物車裡的商品被下架：顯示「此商品已下架」、不洩漏名稱與價格、不能結帳 |
| `orders.spec.ts` | 待付款的訂單：列表有「去付款」，詳情有商品快照與倒數，取消要先確認（返回不取消），取消後狀態與庫存釋放；別人的訂單 id 顯示 404 |

## 設計決定與遇到的問題

- **自給自足，不動真實的商品目錄**：`global-setup.ts` 每次執行前確保 E2E 專用的分類（`e2e-playwright`）與商品（`E2E-SHOP-001`，800 元、原價 1000、有主圖、庫存 20）存在且上架，執行後（teardown）**商品下架、分類停用**，商店看不到。兩者每次共用同一個，不會越建越多；庫存也只動這件商品，不會消耗真實目錄的庫存
- **登入限流**：Gateway 的登入限流依來源 IP（約每分鐘 10 次，每 6 秒補 1 次）。每次執行約 6 次登入（管理員 1 次、固定顧客 4 次、註冊 1 次），API 登入遇到 429 會等 7 秒重試
- **refresh token 是單次使用的**：每個需要登入狀態的測試，各自用 API 登入拿自己的 refresh token，放進瀏覽器的 localStorage（`startSignedIn`），不共用（在後台 E2E 實際踩過，見 [vue-management Step 12](../vue-management/step-12-e2e.md)）。注入腳本只在 localStorage 還沒有時才設定，否則重新整理會把輪替過的新 token 蓋回舊的
- 購物車的預設內容用同樣的方式預先放進 localStorage（`startWithCart`），例外情況的測試不必每次先逛一圈
- **用 API 準備資料、用畫面驗證行為**：改庫存、下架商品、出貨、建立待付款訂單都走 API；測試斷言的是畫面上的結果
- 第一次跑 10 個測試通過 9 個，失敗的是我的選擇器太寬（頁面上有兩個含「購物車」的連結），不是應用程式的問題；**E2E 沒有發現前台的缺陷**

## 驗證

- Vite dev server 上連續 **3 次**全部通過（10 個測試，每次約 19 秒）
- **對著 nginx 映像檔（嚴格 CSP）通過**：10 個測試全過、沒有 CSP 違規。這是商店前台 CSP 第一次在真實瀏覽器驗證：商品圖片來自物件儲存（`img-src` 允許 `http://localhost:8333`）、API 同源、建單到付款整條流程都正常。CSP 偵測本身是否有效，已在後台 E2E 用故意違規確認過（同一份 nginx 設定與偵測規則）
- 上面的情境是對**真實後端**驗證：庫存不足的錯誤訊息格式（`out of stock: SKU.` 換成商品名稱）、下架商品在 `/products/batch` 只回 `id` 與 `status`（SEC-11 的修正）、取消訂單後庫存釋放、付款成功後訂單輪詢到 `PAID`、別人的訂單回 404
- 既有檢查不受影響：332 個單元測試、build、oxlint、eslint、`vue-tsc --build`（含 e2e）、prettier 全過

## 測試留下的資料

- 商店看不到任何東西（已確認：分類 4 個、商品 25 件，沒有 E2E 字樣）
- 每次執行會多一個顧客帳號與一張訂單：`e2e-shopper-<時間>@example.com`（註冊是計畫要測的流程，所以每次要用新的 Email）。後台的使用者與訂單列表看得到，已付款、已出貨，沒有影響商店。商品與訂單沒有刪除 API，要清除只能直接操作資料庫
- 固定顧客帳號 `e2e-customer@example.com`（不會增加）、E2E 專用商品與分類（不會增加）

## 還沒做的

- 沒有放進 CI（需要整套後端與資料庫）；Playwright 的報告與追蹤檔（`test-results/`）、`e2e/.auth/` 已加進 `.gitignore`
- 付款失敗後重新付款、付款逾時、登入限流提示、帳號頁、登出所有裝置沒有 E2E（單元測試有涵蓋）
- 只用 Chrome 一個瀏覽器；手機只驗證了寬度 390px 沒有溢出，沒有在真實手機上看過
