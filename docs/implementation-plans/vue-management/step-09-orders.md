# vue-management Step 9：訂單管理

> 屬於 [vue-management 實作計畫](README.md)

- 列表（依狀態篩選、分頁）、詳情（商品快照、金額、狀態、各階段時間）、取消、出貨、完成

**測試**：按鈕依狀態顯示（`PENDING_PAYMENT` 取消、`PAID` 出貨、`SHIPPED` 完成）；失敗時顯示錯誤碼。

## 後端的對應（實作前確認過程式碼）

| API | 說明 |
| --- | ---- |
| `GET /orders` | ADMIN 看全部，可用 `status`、`userId` 篩選；`page`（從 0 開始）、`size`（1–100）；固定最新的在前，不能換排序。項目只有摘要（`id`、`userId`、`status`、`totalAmount`、`createdAt`） |
| `GET /orders/{id}` | 明細：商品快照（下單當下的名稱、單價）、數量、小計，以及 `createdAt`、`paidAt`、`shippedAt`、`completedAt`、`cancelledAt` |
| `POST /orders/{id}/cancel` | 只限 `PENDING_PAYMENT`，會釋放預扣的庫存；本人或 ADMIN |
| `POST /orders/{id}/ship` | 只限 `PAID`；限 ADMIN（Step 0 補上） |
| `POST /orders/{id}/complete` | 只限 `SHIPPED`；限 ADMIN（Step 0 補上） |

- 三個操作重複呼叫（已經是目標狀態）都回 200，回傳更新後的訂單
- 已付款之後不能取消：雛型沒有退款流程

## 完成內容（2026-11-10）

| 檔案 | 內容 |
| ---- | ---- |
| `src/features/orders/types.ts` | 訂單、明細、分頁、查詢參數的型別 |
| `src/features/orders/api.ts` | `getOrders`、`getOrder`、`changeOrderStatus` |
| `src/features/orders/query.ts` | URL query ↔ 列表條件 ↔ API 參數；`userId` 檢查 UUID 格式 |
| `src/features/orders/status.ts` | 狀態文字、標籤顏色、`availableAction`、確認訊息、錯誤訊息 |
| `src/features/orders/OrderListView.vue` | 狀態、使用者 ID 篩選；分頁；點使用者 ID 只看他的訂單 |
| `src/features/orders/OrderDetailView.vue` | 訂單資訊、各階段時間（只顯示發生過的）、商品快照；依狀態顯示一個操作按鈕 |
| `src/utils/format.ts` | 從 `features/products/format.ts` 搬過來，加上 `formatDateTime`；商品、庫存、訂單共用 |
| `src/router/index.ts` | `/orders`、`/orders/:id`，放在「訂單管理」父路由底下 |

### 設計

- **列表條件放在 URL query**：和商品列表一樣用 `useRouteQuery`；`userId` 不是 UUID 時搜尋前就提示，網址上不合法的值改回預設（送到後端會回 400）
- **點使用者 ID 篩選**：列表上的使用者 ID 是連結，點了只看這個使用者的訂單
- **每個狀態最多一個操作**：`availableAction` 決定（待付款 → 取消、已付款 → 出貨、已出貨 → 完成，其他沒有）；用 `v-if` 只顯示能做的那一個
- **三個操作都先確認**：都不能復原；取消的確認訊息提醒會釋放預扣的庫存
- **操作成功後用回應更新**：回應就是完整的訂單，不必再查一次
- **失敗時顯示原因**：例如使用者剛好付款，取消會回 `ORDER_NOT_CANCELLABLE`，提示狀態可能剛改變、請重新整理；詳情頁有「重新整理」按鈕
- **列表只顯示使用者 ID**：order-service 只有 `userId`；Step 10 做好使用者列表後，可以考慮對應成 email

錯誤代碼對應的訊息：

| 代碼 | 訊息 |
| ---- | ---- |
| `ORDER_NOT_FOUND` | 訂單不存在 |
| `ORDER_NOT_CANCELLABLE` | 只有待付款的訂單可以取消，訂單狀態可能剛改變，請重新整理 |
| `ORDER_ALREADY_CANCELLED` | 訂單已經取消 |
| `ORDER_NOT_SHIPPABLE` | 只有已付款的訂單可以出貨，請重新整理 |
| `ORDER_NOT_COMPLETABLE` | 只有已出貨的訂單可以完成，請重新整理 |
| `INVENTORY_SERVICE_UNAVAILABLE` | 庫存服務暫時無法使用，請稍後再試（取消時要釋放庫存） |

### 測試

| 測試檔 | 案例 |
| ------ | ---- |
| `features/orders/__tests__/query.spec.ts` | 16 個：URL 解析（預設、合法、不合法）、轉成 URL 與 API 參數、五種狀態可執行的操作、各錯誤代碼的訊息 |
| `features/orders/__tests__/OrderListView.spec.ts` | 10 個：預設參數、URL 條件轉成 API 參數並帶入表單、列表內容、搜尋使用者、使用者 ID 格式不對、點使用者 ID 篩選、換頁、清除條件、讀取失敗、進入詳情 |
| `features/orders/__tests__/OrderDetailView.spec.ts` | 14 個：訂單資訊與商品快照、只顯示發生過的階段時間、訂單不存在；五種狀態顯示的按鈕；取消、出貨、完成成功後更新、取消確認視窗與按取消、取消失敗（`ORDER_NOT_CANCELLABLE`）、出貨失敗（`ORDER_NOT_SHIPPABLE`） |

### 遇到的問題

- **oxlint 的 `no-conditional-expect`**：按鈕的測試一開始用 `if` 分成「有按鈕」「沒按鈕」兩種斷言，被擋下。改成每個狀態直接比對按鈕文字的陣列（沒有按鈕就是空陣列）

## 最終檢查

vitest 243 個通過（新增 40 個）、`npm run build`（含 `vue-tsc`）通過、eslint、oxlint、prettier 都沒有問題。
