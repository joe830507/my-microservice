# payment-service 實作步驟

> 建立日期：2026-11-09
> 分支：`feature/payment-service`
> 對應：[microservice-architecture.md](../../architecture/microservice-architecture.md)「payment-service」、「服務互動：下單流程」
> 狀態：Step 1–6 全部完成，並實際啟動驗證完整下單流程（2026-11-09）

## 進度

| Step | 內容 | 狀態 |
|---|---|---|
| 1 | 建立專案、DB 與資料表 | 完成 |
| 2 | `POST /api/v1/payments` 建立付款（向 order-service 取金額） | 完成 |
| 3 | `GET /api/v1/payments/{id}` 查付款狀態 | 完成 |
| 4 | `POST /api/v1/payments/{id}/mock-callback` 模擬金流回呼，成功時通知 order-service | 完成 |
| 5 | 排程：重試通知 order-service 失敗的付款 | 完成 |
| 6 | api-gateway 加上 `/api/v1/payments/**` 路由 | 完成 |

每一步都附整合測試，測試通過後單獨 commit。呼叫 order-service 的部分在測試裡用 WireMock 模擬。

---

## Step 1：建立專案、DB 與資料表

| 項目 | 值 |
|---|---|
| Group / Artifact | `com.mymicroservice` / `payment-service` |
| Package | `com.mymicroservice.payment` |
| Boot | 4.1.1，依賴 common；啟動類別加 `@Import(RequireRolesGuard.class)` |
| port | 8085 |
| DB | `payment-db`（postgres:18.6，本機 port 5436），資料庫 `payment_db`，schema `payment_service` |
| 依賴 | 和 order-service 相同（含 `spring-boot-starter-restclient`、測試用 WireMock） |

```
payment
  id                   UUID PK（UUIDv7）
  order_id             UUID
  user_id              UUID            付款的人，必須是訂單的擁有者
  amount               NUMERIC(14,2)   一律取自 order-service，不接受前端傳入
  status               PENDING | SUCCEEDED | FAILED
  order_notification   PENDING | DONE | REJECTED，只有 SUCCEEDED 才有值
  completed_at
  + 稽核欄位、version

唯一約束：(order_id) WHERE status IN ('PENDING', 'SUCCEEDED')
```

- **同一張訂單同時只能有一筆進行中或成功的付款**：架構文件寫「只能有一筆成功的付款」，這裡連 `PENDING` 一起限制，兩個請求同時建立付款時，後到的會被 DB 擋下；失敗（`FAILED`）的不算，可以重新付款
- `order_notification`：付款成功後要通知 order-service（`/internal/v1/orders/{id}/paid`）。先記成 `PENDING`，通知成功改 `DONE`；order-service 回訂單已取消改 `REJECTED`（要退款，雛型階段只記錄）；連不上維持 `PENDING`，由 Step 5 重試

**測試**：context 啟動、Flyway、health、DB 約束（含部分唯一索引）。

**已完成**：`PaymentServiceApplication`、實體 `Payment`、`V1__create_payment_table.sql`、compose 的 `payment-db`；測試 `PaymentServiceApplicationIntegrationTests`（6）：同一張訂單只能有一筆進行中或成功的付款、失敗後可以重新付款、成功的付款一定要有通知狀態。

---

## Step 2：建立付款

`POST /api/v1/payments`，body `{orderId}`

| 順序 | 動作 | 失敗時 |
|---|---|---|
| 1 | 呼叫 order-service `GET /internal/v1/orders/{id}` | 不存在或不是自己的訂單 → 404 `ORDER_NOT_FOUND`（不透露別人的訂單存在）；order 連不上 → 503 `ORDER_SERVICE_UNAVAILABLE` |
| 2 | 訂單必須是 `PENDING_PAYMENT` | 否則 409 `ORDER_NOT_PAYABLE` |
| 3 | 已經有 `PENDING` 的付款 → 回那一筆（200）；已經有 `SUCCEEDED` → 409 `ORDER_ALREADY_PAID` | |
| 4 | 建立 `PENDING` 付款，金額取自訂單 | 同時建立被唯一約束擋下 → 409 `DATA_CONFLICT`，重試即可拿到已建立的那筆 |

回應 201：`{id, orderId, amount, status, createdAt, completedAt}`。

**已完成**：`PaymentController.createPayment`、`OrderClient`、`RestClientConfig`（固定 HTTP/1.1）；測試 `PaymentControllerCreateIntegrationTests`（16）。body 裡的 `amount` 會被忽略；order-service 逾時或連不上回 503。

## Step 3：查付款

`GET /api/v1/payments/{id}`：本人或 ADMIN；別人的回 404。

**已完成**：`PaymentController.getPayment`；測試 `PaymentControllerGetIntegrationTests`（7）。

## Step 4：模擬金流回呼

`POST /api/v1/payments/{id}/mock-callback`，body `{result: SUCCESS | FAILED}`

真實的金流回呼由第三方伺服器呼叫、用簽章驗證；雛型階段由本人（或 ADMIN）呼叫，模擬付款結果。

| 目前狀態 | `SUCCESS` | `FAILED` |
|---|---|---|
| `PENDING` | 改成 `SUCCEEDED`，commit 後通知 order-service | 改成 `FAILED` |
| `SUCCEEDED` | 200（冪等） | 409 `PAYMENT_ALREADY_COMPLETED` |
| `FAILED` | 409 `PAYMENT_ALREADY_COMPLETED` | 200（冪等） |

- 狀態轉換用條件式 UPDATE（`WHERE status = 'PENDING'`），重複或同時回呼只有一個生效
- 通知 order-service 的結果：200 → `DONE`；409 訂單已取消 → `REJECTED`（記 error log，之後要退款）；連不上、5xx → 維持 `PENDING`
- 通知失敗不影響回呼的回應

**已完成**：`PaymentController.mockCallback`、`PaymentRepository.completeIfPending`、`markOrderNotification`、`OrderClient.notifyPaid`；測試 `PaymentControllerMockCallbackIntegrationTests`（15）。

- 狀態改完並 commit 後才通知 order-service；同樣的成功回呼再送一次，還沒通知成功的會重試
- order-service 回 404 也記成 `REJECTED`（訂單不存在，同樣要退款）
- 併發測試：成功與失敗的回呼同時送達，只有一個生效

## Step 5：排程

`order_notification = PENDING` 且最後更新超過 1 分鐘的付款，重新通知 order-service（order-service 那邊是冪等的）。

**已完成**：`OrderNotificationRetryJob`（`app.order-notification-retry`，每分鐘）；測試 `OrderNotificationRetryJobIntegrationTests`（5）。測試時預設停用 cron（`src/test/resources/application.properties`）。

## Step 6：Gateway 路由

`/api/v1/payments/**` → payment-service（`PAYMENT_SERVICE_URI`，預設 `http://localhost:8085`），全部需要 JWT。

**已完成**：api-gateway 的 `payment-service` 路由；`GatewayRoutingIntegrationTests`（+2）、`GatewayJwtIntegrationTests`（+2），api-gateway 共 99 個測試。原本拿 `/api/v1/payments` 當「不在路由表」例子的測試改用 `/api/v1/shipments`。

---

## 實際啟動驗證：完整下單流程（2026-11-09）

六個服務都用真的程式啟動，全部經過 Gateway 呼叫：

| 情境 | 結果 |
|---|---|
| 下單 2 件（庫存 5） | 庫存 3 / 預扣 2 |
| 別人替這張訂單付款 | 404 `ORDER_NOT_FOUND` |
| 建立付款（body 帶 `amount: 0.01`） | `PENDING`，金額 399.98（取自訂單，忽略 body） |
| 再建立一次 | 200，回同一筆付款 |
| 回呼 `SUCCESS` | 付款 `SUCCEEDED`；訂單 `PAID`；庫存 3 / 0（預扣轉成正式扣除） |
| 成功後回呼 `FAILED`；再付款；取消訂單 | 409 `PAYMENT_ALREADY_COMPLETED`；409 `ORDER_ALREADY_PAID`；409 `ORDER_NOT_CANCELLABLE` |
| 另一張訂單回呼 `FAILED` | 付款 `FAILED`，訂單仍是 `PENDING_PAYMENT` |
| 重新建立付款並回呼 `SUCCESS` | 新的付款 id；訂單 `PAID`；庫存 1 / 0 |
| 已取消的訂單建立付款 | 409 `ORDER_NOT_PAYABLE` |
| 不帶 token 查付款 | 401 |

---

## 之後再處理

- 真實金流：回呼改由第三方呼叫並驗證簽章，`mock-callback` 移除
- 退款流程（`REJECTED` 的付款）
- PENDING 付款逾時：訂單被取消後，留著的 `PENDING` 付款改成 `FAILED`
- Kafka 導入後，付款成功改成發 `PaymentSucceeded` 事件
