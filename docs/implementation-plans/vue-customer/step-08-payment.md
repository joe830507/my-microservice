# vue-customer Step 8：付款（模擬金流）

> 屬於 [vue-customer 實作計畫](README.md)

- `/orders/:id/pay`（`OrderPayView`，`requiresAuth`）。流程邏輯放 `features/payments/usePayFlow.ts`（composable），畫面只負責依 `phase` 顯示；`api.ts` 有 `createPayment`、`getPayment`、`mockCallback`，`orders/api.ts` 加了 `getOrder`
- **進頁面**：先 `GET /orders/{id}` 再決定顯示什麼：`PAID`／`SHIPPED`／`COMPLETED` → 已付款（不建立付款）；`CANCELLED` → 已取消；不存在或別人的訂單（`ORDER_NOT_FOUND`、400）→ 404；`PENDING_PAYMENT` → `POST /payments`
- **重新整理不會重複建立**：後端對同一張訂單已有進行中（PENDING）的付款時回 200 與同一筆，前端每次進頁面都呼叫、用回傳的 id
- **剩餘時間**：建單 `createdAt` + 30 分鐘（對應 order-service 的 `payment-timeout`），每秒更新，`mm:ss`。用的是使用者電腦的時鐘，只用來提示與停用按鈕，真正的期限以後端為準。到期後停用付款按鈕、提示付款期限已到，並重新查一次訂單（若已被取消就切到已取消）
- **付款成功／失敗**：兩個按鈕呼叫 `mock-callback`（`SUCCESS`／`FAILED`）；處理中按鈕停用，連點只送一次
  - `SUCCEEDED`：輪詢 `GET /orders/{id}` 到 `PAID`（付款通知 order-service 是非同步重試，可能晚幾秒）。間隔 1 秒、最多 10 次（`pollConfig`，測試會改小）。到上限仍不是 `PAID` → 「付款處理中，請稍後到我的訂單查看」，附「再查一次」
  - `FAILED`：顯示付款失敗，「重新付款」再呼叫 `POST /payments`（上一筆已是終點，後端會建立新的），之後的回呼用新的付款 id
  - 輪詢時訂單變成 `CANCELLED`（付款成功時剛好逾時被取消）：說明已付的款項會退回（雛型階段後端只記錄，還沒有真的退款）
  - 離開頁面（元件卸載）就停止輪詢與計時器
- **回呼失敗時，以後端記錄的付款狀態為準**，不自己猜結果：回呼丟錯（409 `PAYMENT_ALREADY_COMPLETED`、沒收到回應等）時改查 `GET /payments/{id}`——已 `SUCCEEDED` 就繼續確認訂單、已 `FAILED` 就顯示失敗、仍 `PENDING` 才回到可以再按並顯示錯誤。避免「錢其實付成功了，畫面卻說失敗，顧客再付一次」
- **建立付款的錯誤**：`ORDER_ALREADY_PAID`（上次付完重新整理）→ 不能再付，輪詢到 `PAID`；`ORDER_NOT_PAYABLE`（訂單剛被取消）→ 重新查訂單決定顯示；`ORDER_SERVICE_UNAVAILABLE` 等 → 顯示錯誤與重試
- 付款頁、我的訂單的佔位頁：付款頁換成真的內容；`/orders` 還是佔位（Step 9）

## 完成內容

**測試**（`npm run test:unit`，全部 286 個）：
- `OrderPayView.spec.ts`：
  - 等待付款：金額與商品、剩餘時間、建立付款只帶 `orderId`、期限已到停用按鈕且不送回呼
  - 付款成功：輪詢到 `PAID`、到上限顯示處理中並可再查一次、付款成功時訂單已取消、處理中連點只送一次
  - 付款失敗：重新付款建立新的付款並用新 id 回呼
  - 回呼失敗：409 但其實已成功、沒收到回應且仍 `PENDING`（回到可再按）、沒收到回應但其實已失敗
  - 進頁面時的訂單狀態：`PAID`／`SHIPPED`／`COMPLETED`、`CANCELLED`、不存在、查詢失敗與重試
  - 建立付款的錯誤：`ORDER_ALREADY_PAID`、`ORDER_NOT_PAYABLE`、`ORDER_SERVICE_UNAVAILABLE`、重新整理（200 同一筆）
  - 離開頁面後停止輪詢
- `format.spec.ts`：倒數時間格式（進位、負數、30 分鐘）

`npm run build`、oxlint、eslint 通過。

**測試遇到的問題**：頁面沒有卸載時計時器與輪詢會繼續跑，打到下一個測試的 mock，造成「離開頁面後停止輪詢」偶發失敗；測試改成每個測試結束後卸載所有掛載的頁面。

**尚未用真的後端驗證**：測試用 MSW 模擬，還沒有從頭到尾走過一次真的 `POST /orders` → `POST /payments` → `mock-callback` → 訂單變 `PAID`。特別要確認 payment-service 通知 order-service 的非同步延遲通常多久（決定輪詢 10 秒夠不夠），以及剩餘時間的 30 分鐘和 order-service 實際設定一致。
