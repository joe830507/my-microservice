# order-service 實作步驟

> 建立日期：2026-11-09
> 分支：`feature/order-service`
> 對應：[microservice-architecture.md](../architecture/microservice-architecture.md)「order-service」、「服務互動：下單流程」
> 狀態：Step 1–9 全部完成，並實際啟動驗證（2026-11-09）

## 進度

| Step | 內容 | 狀態 |
|---|---|---|
| 1 | 建立專案、DB 與資料表 | 完成 |
| 2 | `POST /api/v1/orders` 建立訂單（呼叫 product、inventory） | 完成 |
| 3 | `GET /api/v1/orders/{id}` 訂單詳情 | 完成 |
| 4 | `GET /api/v1/orders` 訂單列表 | 完成 |
| 5 | `POST /api/v1/orders/{id}/cancel` 取消訂單 | 完成 |
| 6 | `GET /internal/v1/orders/{id}` 給 payment-service 查訂單 | 完成 |
| 7 | `POST /internal/v1/orders/{id}/paid` 付款成功通知 | 完成 |
| 8 | 排程：付款逾時自動取消、補做失敗的庫存釋放與確認 | 完成 |
| 9 | api-gateway 加上 `/api/v1/orders/**` 路由 | 完成 |

每一步都附整合測試，測試通過後單獨 commit。呼叫 product、inventory 的部分在測試裡用 WireMock 模擬。

---

## Step 1：建立專案、DB 與資料表

| 項目 | 值 |
|---|---|
| Group / Artifact | `com.mymicroservice` / `order-service` |
| Package | `com.mymicroservice.order` |
| 位置 | `backend/spring-order-service`，加入 `backend/pom.xml` 的 modules |
| Boot | 4.1.1，依賴 common |
| port | 8082 |
| DB | `order-db`（postgres:18.6，本機 port 5433），資料庫 `order_db`，schema `order_service` |
| 依賴 | webmvc、data-jpa、flyway、validation、actuator、springdoc、Lombok；測試加 WireMock |

### 資料表

```
orders                          order 是 SQL 保留字
  id                  UUID PK         由 order-service 產生（UUIDv7），也是 inventory 的冪等鍵
  user_id             UUID            下單的人（X-User-Id）
  status              PENDING_PAYMENT | PAID | SHIPPED | COMPLETED | CANCELLED
  reservation_status  RESERVED | CONFIRMED | RELEASED    inventory 那邊應該要是什麼狀態
  reservation_synced  BOOLEAN         inventory 是否已經是 reservation_status 的狀態
  total_amount        NUMERIC(14,2)
  paid_at、cancelled_at
  + 稽核欄位、version

order_item
  order_id      UUID FK → orders
  product_id    UUID
  sku           VARCHAR(64)
  product_name  VARCHAR(255)        快照：商品之後改名、改價，訂單內容不變
  unit_price    NUMERIC(12,2)       快照
  qty           INT > 0
  subtotal      NUMERIC(14,2)
  PK (order_id, product_id)
```

**`reservation_status`、`reservation_synced` 的用途**：訂單狀態改變（取消、付款）後要呼叫 inventory 釋放或確認。先在自己的 DB 記下「應該要變成什麼」並標成未同步，呼叫成功後再標成已同步；呼叫失敗不影響訂單狀態，由 Step 8 的排程重試。這樣 inventory 暫時掛掉時，取消與付款都不會失敗。

索引：`(user_id, created_at DESC)` 給列表；`(status, created_at)` 給逾時排程；`reservation_synced = false` 的部分索引給補做同步。

**測試**：context 啟動、Flyway 套用成功、health、DB 約束。

**已完成**：`OrderServiceApplication`、實體 `Order`、`OrderItem`、`V1__create_order_tables.sql`、compose 的 `order-db`；測試 `OrderServiceApplicationIntegrationTests`（10）：資料表建立、health，以及未知狀態、負數金額、數量 0、不存在的訂單都會被 DB 擋下。

---

## Step 2：建立訂單

`POST /api/v1/orders`，body `{items[{productId, qty}]}`

架構文件原本寫 `items[{sku, qty}]`，改用 `productId`：product-service 的批次查詢（`GET /api/v1/products/batch?ids=`）是用 id 查，前端從商品列表拿到的也是 id。SKU 從查回來的商品資料取得，再拿去預扣庫存。

| 順序 | 動作 | 失敗時 |
|---|---|---|
| 1 | 驗證、合併重複的商品 | 400 |
| 2 | 呼叫 product 批次查詢，取名稱、價格快照 | 商品不存在 → 404 `PRODUCT_NOT_FOUND`；不是 `ACTIVE` → 409 `PRODUCT_NOT_AVAILABLE`（都列出所有有問題的商品）；product 連不上或 5xx → 503 `PRODUCT_SERVICE_UNAVAILABLE` |
| 3 | 產生 `orderId`，呼叫 inventory 預扣 | 庫存不足或沒有庫存紀錄 → 409 `OUT_OF_STOCK`（列出 SKU）；逾時、連不上、5xx → **呼叫釋放**（不知道預扣到底有沒有成功），回 503 `INVENTORY_SERVICE_UNAVAILABLE` |
| 4 | 存訂單：`PENDING_PAYMENT`、`reservation_status = RESERVED` | 存檔失敗 → 呼叫釋放，回 500 |

| 項目 | 做法 |
|---|---|
| 權限 | `@CurrentUserId`，需要登入 |
| 驗證 | `items` 1–50 筆；`qty` 1–99 |
| 金額 | 一律用 product 回傳的價格計算，不接受前端傳金額 |
| 交易 | 呼叫其他服務時不開 DB 交易，只有第 4 步存檔在交易內 |
| HTTP client | `RestClient`；連線逾時 2 秒、讀取逾時 5 秒；下游位置用 `order.services.product-uri`、`inventory-uri` 設定 |
| 回應 | 201，訂單詳情 |

**測試**：成功（快照、金額、預扣的內容）、商品不存在、商品下架、庫存不足、product 掛掉、inventory 逾時時會呼叫釋放、沒有身分 401。

**已完成**：`OrderController.createOrder`、`OrderService`、`ProductClient`、`InventoryClient`、`RestClientConfig`、`UuidV7`；測試 `OrderControllerCreateIntegrationTests`（27）。

- 需要 `spring-boot-starter-restclient`：Boot 4 把 `RestClient.Builder` 的自動設定拆成獨立模組
- **HttpClient 固定 HTTP/1.1**：JDK HttpClient 預設對 `http://` 嘗試升級成 HTTP/2（h2c），下游回 5xx 或逾時時，WireMock 收到的預扣請求 body 是空的；固定 HTTP/1.1 後正常
- inventory 回 `RESERVATION_MISMATCH` 等非預期的 4xx 時回 500（全新的 orderId 不應該發生）
- 預扣成功但訂單存檔失敗時會呼叫釋放；連釋放都失敗只能記 log（訂單不存在，排程補不到），由 inventory 的 `ReservationExpiryJob` 在 1 小時後釋放

---

## Step 3：訂單詳情

`GET /api/v1/orders/{id}`

- 只能看自己的；ADMIN 可以看全部
- 別人的訂單回 **404**（不是 403），不透露這個 id 存在

**已完成**：`OrderController.getOrder`；測試 `OrderControllerGetIntegrationTests`（11）。角色比對區分大小寫（`admin`、`ADMINISTRATOR` 都不算 ADMIN）；回應不含 `reservationStatus` 等內部欄位。

## Step 4：訂單列表

`GET /api/v1/orders?status=&page=&size=`

- 一般使用者只列出自己的；ADMIN 列出全部，可再用 `userId` 篩選
- 分頁格式和 user-service、product-service 一致；最新的在前
- 列表只回摘要（`id, userId, status, totalAmount, createdAt`），不載入明細

**已完成**：`OrderController.getOrders`；測試 `OrderControllerListIntegrationTests`（14）。一般使用者帶別人的 `userId` 會被忽略，仍只看到自己的。

## Step 5：取消訂單

`POST /api/v1/orders/{id}/cancel`

| 目前狀態 | 結果 |
|---|---|
| `PENDING_PAYMENT` | 改成 `CANCELLED`、`reservation_status = RELEASED`，再呼叫 inventory 釋放；呼叫失敗不影響回應（Step 8 重試） |
| `CANCELLED` | 200（冪等） |
| 其他 | 409 `ORDER_NOT_CANCELLABLE` |

- 狀態轉換用條件式 UPDATE，和付款通知同時送達時只有一個成功
- 只有本人或 ADMIN 能取消；別人的訂單回 404

**已完成**：`OrderController.cancelOrder`、`OrderRepository.cancelIfPending`、`markReservationSynced`；測試 `OrderControllerCancelIntegrationTests`（12）。

- 狀態改完並 commit 後才呼叫 inventory 釋放，呼叫期間不佔 DB 連線；成功才標成已同步
- inventory 掛掉時取消照樣成功，`reservation_synced` 維持 false
- 已取消但還沒同步的訂單再取消一次，會重試釋放
- 併發測試：同時取消 5 次全部回 200，訂單只被改一次（version 只加 1）

## Step 6：內部查詢訂單

`GET /internal/v1/orders/{id}`：回傳 `{id, userId, status, totalAmount}`，給 payment-service 取得金額與狀態。

**已完成**：`InternalOrderController`；測試 `InternalOrderGetIntegrationTests`（4）。不需要身分 header，不檢查擁有者，不帶明細。

## Step 7：付款成功通知

`POST /internal/v1/orders/{id}/paid`

| 目前狀態 | 結果 |
|---|---|
| `PENDING_PAYMENT` | 改成 `PAID`、`reservation_status = CONFIRMED`，再呼叫 inventory 確認；呼叫失敗由 Step 8 重試 |
| `PAID` | 200（冪等） |
| `CANCELLED` | 409 `ORDER_ALREADY_CANCELLED`（payment-service 要退款，雛型階段只記錄） |

**已完成**：`InternalOrderController.markPaid`、`OrderRepository.markPaidIfPending`；測試 `InternalOrderPaidIntegrationTests`（9）。

- 和取消的做法一樣：狀態改完並 commit 後才呼叫 inventory 確認；inventory 掛掉時付款照樣記成功，由排程重試
- `SHIPPED`、`COMPLETED` 也視為已付款，回 200
- 併發測試：付款通知與取消同時送達，只有一個成功，訂單狀態與呼叫 inventory 的動作一致
- 已知限制：inventory 回 409（例如預扣已被逾時釋放）時，確認會一直失敗並由排程重試；付款期限 30 分鐘、inventory 逾時釋放 1 小時，正常不會發生

## Step 8：排程

| 工作 | 做法 |
|---|---|
| 付款逾時 | `PENDING_PAYMENT` 超過 30 分鐘 → 自動取消（同 Step 5） |
| 補做庫存同步 | `reservation_synced = false` 的訂單，依 `reservation_status` 重新呼叫釋放或確認（inventory 那邊是冪等的，重複呼叫沒關係） |

**已完成**：`PaymentTimeoutJob`、`ReservationSyncJob`；測試 `OrderJobsIntegrationTests`（8）。

| 設定 | 值 |
|---|---|
| `app.payment-timeout` | 每分鐘；建立超過 30 分鐘仍未付款就取消；**必須比 inventory 的預扣逾時（1 小時）短** |
| `app.reservation-sync` | 每分鐘；最後更新超過 1 分鐘才重試，避免和正在呼叫 inventory 的請求重複 |

- 兩個排程都不需要分散式鎖：取消、標記同步都是條件式 UPDATE，多個實例同時跑結果一樣
- 付款逾時分批處理到全部；補做同步每次只掃一輪，inventory 還沒恢復的留到下次
- 測試時預設停用 cron（`src/test/resources/application.properties`），避免排程在其他測試進行中改到資料

## Step 9：Gateway 路由

`/api/v1/orders/**` → order-service（`ORDER_SERVICE_URI`，預設 `http://localhost:8082`），全部需要 JWT。

**已完成**：api-gateway 的 `order-service` 路由；`GatewayRoutingIntegrationTests`（+3）、`GatewayJwtIntegrationTests`（+2），api-gateway 共 95 個測試。原本拿 `/api/v1/orders` 當「不在路由表」例子的測試改用 `/api/v1/payments`。

---

## 實際啟動驗證（2026-11-09）

Gateway、user、product、inventory、order 五個服務都用真的程式啟動，經過 Gateway 呼叫（付款通知是內部 API，直接呼叫 order-service）：

| 情境 | 結果 |
|---|---|
| 商品還是草稿就下單 | 409 `PRODUCT_NOT_AVAILABLE` |
| 下單 2 件（庫存 5） | 201，快照名稱、單價 199.99、總額 399.98；庫存 3 / 預扣 2 |
| 別人查這張訂單；不帶 token | 404 `ORDER_NOT_FOUND`；401 |
| 取消 | 200 `CANCELLED`；庫存回到 5 / 0 |
| 再下單 2 件，付款通知 | 200 `PAID`；庫存 3 / 0（預扣轉成正式扣除） |
| 經 Gateway 呼叫 `/internal/v1/orders/{id}` | 404 |
| 取消已付款的訂單 | 409 `ORDER_NOT_CANCELLABLE` |
| 下單 4 件（只剩 3） | 409 `OUT_OF_STOCK` |
| 列表 | 本人看到 2 張（最新的在前），別人看到 0 張 |

- 上架商品需要主圖（要走圖片上傳）；驗證時直接在 product_db 把商品改成 `ACTIVE`，並刪掉 Redis 的商品詳情快取

---

## 之後再處理

- 建單的冪等（前端連點兩次會建兩張單）：用 `Idempotency-Key` header
- Resilience4j 的 retry、circuit breaker
- ~~出貨（`SHIPPED`、`COMPLETED`）的後台 API~~：`ship` 已完成，見 [vue-management-implementation-plan.md](vue-management-implementation-plan.md) Step 0
- Kafka 導入後，付款與取消改成事件（見架構文件「之後：事件驅動」）
- product 的批次查詢改到 `/internal/v1/products/batch`
