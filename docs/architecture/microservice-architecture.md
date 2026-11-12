# 微服務整體架構（雛型階段）

> 設計日期：2026-11-09
> 目標：先把各微服務建立起來，再統一接到 API Gateway
> 狀態：所有服務都已完成，完整下單流程（下單 → 付款 → 確認扣除庫存）已實際驗證

## 已定案的決定

| 項目 | 決定 | 原因 |
|---|---|---|
| 服務發現 / 設定中心 | **Kubernetes 原生**（Service DNS、ConfigMap、Secret） | 業界主流；不另外架 Nacos / Consul / Eureka |
| 路由前綴 | 所有服務統一用 `/api/v1/{resource}` | Gateway 可以直接用 path 分流 |
| 資料庫 | **每個服務各自一個 DB，互相隔離** | 依需求在 `infra/docker-compose.yml` 加入對應的 DB |
| 建置結構 | `backend/pom.xml` 只做**聚合**，各服務仍以 `spring-boot-starter-parent` 為 parent | 一次匯入、一次建置；各服務仍可各自升級 Boot 版本 |
| 共用程式碼 | `backend/common` 模組，**只放技術性的共通元件**，不放業務模型 | 錯誤格式、稽核、身分 header 是跨服務的約定，複製多份必定走樣 |

---

## 服務清單

| 服務 | 職責 | 狀態 |
|---|---|---|
| `spring-api-gateway` | 路由、JWT 驗證、身分傳遞、限流 | 已實作；細節見 [api-gateway-implementation-plan.md](../implementation-plans/backend/api-gateway-implementation-plan.md) |
| `spring-product-service` | 分類、商品、商品圖片 | 已實作（含 Redis 快取、圖片上傳到物件儲存）；讀取公開，但不是 ADMIN 只看得到上架中（`ACTIVE`）的商品（列表、單筆、SKU、圖片；其他狀態回 404），批次查詢照舊帶 `status`；新增、修改、刪除限 ADMIN；後台的分類列表 `GET /api/v1/categories`（含停用的分類）也限 ADMIN |
| `spring-user-service` | 註冊、登入、JWT 簽發、會員與角色 | 已實作 |
| `spring-inventory-service` | 庫存、預扣、確認、釋放 | 已實作；步驟見 [inventory-service-implementation-plan.md](../implementation-plans/backend/inventory-service-implementation-plan.md) |
| `spring-order-service` | 訂單、商品快照、訂單狀態 | 已實作；步驟見 [order-service-implementation-plan.md](../implementation-plans/backend/order-service-implementation-plan.md) |
| `spring-payment-service` | 付款（雛型階段用模擬金流） | 已實作；步驟見 [payment-service-implementation-plan.md](../implementation-plans/backend/payment-service-implementation-plan.md) |
| `spring-notification-service` | 消費使用者、訂單與付款事件，透過 SMTP 發送通知；沒有業務 HTTP API | E1–E2 基礎設施與使用者事件消費完成；E3–E7 尚未開始；步驟見 [notification-implementation-plan.md](../implementation-plans/middleware/notification-implementation-plan.md) |

### 暫不拆分

| 服務 | 原因 |
|---|---|
| cart-service | 先放在前端或 order-service 用 Redis 存，邏輯變複雜再拆 |
| search-service | 依中介軟體評估文件，ES 讀取先放在 product-service，由 MQ 同步 |
| notification-service | E1–E2 基礎設施與使用者事件消費完成；E3–E7 尚未開始 |

---

## 全域慣例

| 項目 | 慣例 |
|---|---|
| 對外 API | `/api/v1/{resource}`，經過 Gateway |
| 內部 API | `/internal/v1/{resource}`，**Gateway 不轉發**，只供服務之間呼叫 |
| 身分傳遞 | Gateway 驗 JWT，往下游帶 `X-User-Id`、`X-User-Roles`；下游服務不自己驗 token |
| 冪等 | 會改變狀態的內部呼叫（預扣、確認、釋放庫存、付款）都用 `orderId` 當冪等鍵 |
| 角色 | 先做 `CUSTOMER`、`ADMIN` 兩種 |

### 本機 port 分配

| 服務 | 應用程式 port | DB port | Redis port |
|---|---|---|---|
| api-gateway | 8080 | — | 6381（登入限流） |
| product-service | 8081 | 5432 | 6379（快取）；物件儲存 8333（商品圖片） |
| order-service | 8082 | 5433 | — |
| inventory-service | 8083 | 5434 | — |
| user-service | 8084 | 5435 | 6380（登入失敗計數） |
| payment-service | 8085 | 5436 | — |
| notification-service（僅 actuator，叢集內） | 8086 | 5437 | — |
| observability（Grafana LGTM，compose） | Grafana 3000、OTLP 4317／4318 | — | — |

> 現有的 `GET /api/v1/products/batch` 給 order-service（建單檢查與快照）和前台購物車（購物車不需登入，訪客也會呼叫）使用。不是 ADMIN 時，非上架中（ACTIVE）的商品只回 `id` 與 `status`，`sku`、`name`、`price` 為 `null`，呼叫端只需要知道「不能買」。之後可以搬到 `/internal/v1/products/batch`，或是對內、對外各留一支；雛型階段先不動。

---

## 後端專案結構

```
backend/
├── pom.xml                    聚合 pom（packaging pom，只有 <modules>）
├── common/                    共用模組（Spring Boot auto-configuration）
├── observability/             可觀測性的共用設定與依賴（指標、追蹤、log），6 個服務都依賴，包含 Gateway
├── spring-product-service/    parent：spring-boot-starter-parent，依賴 common
├── spring-user-service/       同上
├── spring-notification-service/ 同上（通知寄信與事件消費）
├── ...
└── spring-api-gateway/        不依賴 common（reactive、沒有 JPA）
```

### common 放什麼

| 放 | 不放 |
|---|---|
| `AppException`、`GlobalExceptionHandler`（ProblemDetail + `code`） | Entity、DTO 等業務模型 |
| `ErrorCode` interface + 共用錯誤碼 `CommonErrorCode` | 服務間呼叫的請求、回應類別（由呼叫方自己定義） |
| `CommonEntity`（稽核欄位 + `@Version`） | 只有單一服務用到的設定（例如 product 的 `RedisConfig`） |
| `X-User-*` header 常數、讀 header 的 `AuditorAware` | |
| `@CurrentUserId`（注入目前使用者 id）、`@RequireRoles`（角色檢查） | |
| `RequireRolesGuard`：用到 `@RequireRoles` 的服務要在啟動類別加 `@Import(RequireRolesGuard.class)` | |

- 版本固定 `0.0.1-SNAPSHOT`，透過聚合 pom 一起建置，不發佈到 repository
- 單獨建置某個服務：`./mvnw -pl spring-user-service -am package`（`-am` 會先建置 common）
- 改 common 等於所有服務都要重新建置，所以要保持精簡
- **打包服務一律加 `-am`**：沒加的話會用 `~/.m2` 裡可能過期的 common。舊版沒有 `RequireRoles` 時，JVM 會默默忽略 controller 上的 `@RequireRoles`，後台 API 等於沒有權限檢查。`RequireRolesGuard` 讓這種情況在編譯或啟動時就失敗
- 實作步驟見 [backend-common-implementation-plan.md](../implementation-plans/backend/backend-common-implementation-plan.md)

---

## 各服務最小 API

### user-service

| 方法 | 路徑 | 說明 |
|---|---|---|
| POST | `/api/v1/auth/register` | 註冊 |
| POST | `/api/v1/auth/login` | 回傳 access token、refresh token |
| POST | `/api/v1/auth/refresh` | 換新的 access token 與 refresh token（輪替，舊的立即失效） |
| POST | `/api/v1/auth/logout` | 撤銷 refresh token；一律回 204 |
| DELETE | `/api/v1/users/me/sessions` | 登出所有裝置（需要 JWT） |
| GET | `/api/v1/auth/jwks` | 公鑰（JWKS），Gateway 用來驗簽；不需要 JWT |
| GET | `/api/v1/users/me` | 自己的資料 |
| GET | `/api/v1/users` | 後台查使用者列表（ADMIN） |

- JWT 用 **RS256**：user-service 用私鑰簽，Gateway 用 JWKS 驗簽。Gateway 可以直接用 Spring Security 的 `oauth2ResourceServer().jwt()`，不需要共享 secret
- 私鑰放在 K8s Secret，本機開發用設定檔或環境變數

### inventory-service

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/api/v1/inventory/{sku}` | 查庫存 |
| PUT | `/api/v1/inventory/{sku}` | 後台設定或調整庫存（ADMIN） |
| POST | `/internal/v1/inventory/reservations` | 預扣，body 帶 `orderId`、`items[{sku, qty}]`，全部成功或全部失敗 |
| POST | `/internal/v1/inventory/reservations/{orderId}/confirm` | 付款成功後，把預扣轉成正式扣除 |
| POST | `/internal/v1/inventory/reservations/{orderId}/release` | 取消或逾時時釋放預扣 |

- 資料表：`stock(sku, available, reserved, version)`、`reservation(order_id, status)`、`reservation_item(order_id, sku, qty)`；預扣全有全無，狀態放在整張訂單的主檔
- 雛型階段用 DB 樂觀鎖，或 `UPDATE ... WHERE available >= ?`；之後換成 Redis Lua（見中介軟體評估文件「服務邊界提醒」）
- SKU 由後台用 `PUT /inventory/{sku}` 建立，雛型階段不做跨服務驗證；之後改成接收 product 的 `ProductCreated` 事件

### order-service

| 方法 | 路徑 | 說明 |
|---|---|---|
| POST | `/api/v1/orders` | 建立訂單，body 帶 `items[{productId, qty}]`（SKU 由 product 查回） |
| GET | `/api/v1/orders/{id}` | 訂單詳情，只能看自己的 |
| GET | `/api/v1/orders` | 自己的訂單列表；ADMIN 可查全部 |
| POST | `/api/v1/orders/{id}/cancel` | 取消，只限尚未付款的訂單 |
| POST | `/api/v1/orders/{id}/ship` | 後台出貨（ADMIN），只限 `PAID`；已出貨的回 200 |
| POST | `/api/v1/orders/{id}/complete` | 後台完成訂單（ADMIN），只限 `SHIPPED`；已完成的回 200 |
| GET | `/internal/v1/orders/{id}` | 給 payment-service 查金額與狀態 |
| POST | `/internal/v1/orders/{id}/paid` | payment-service 通知付款成功 |

- 狀態：`PENDING_PAYMENT → PAID → SHIPPED → COMPLETED`；取消或逾時變成 `CANCELLED`
- 建單時把商品名稱、價格**快照**存進 `order_item`，不能只存 productId

### payment-service

| 方法 | 路徑 | 說明 |
|---|---|---|
| POST | `/api/v1/payments` | 對某張訂單建立付款，body 帶 `orderId` |
| GET | `/api/v1/payments/{id}` | 查付款狀態 |
| POST | `/api/v1/payments/{id}/mock-callback` | **模擬第三方金流回呼**，可指定成功或失敗 |

- 金額從 order-service 查，不採用前端傳入的值
- 同一張訂單只能有一筆成功的付款（`orderId` 加唯一約束）

### api-gateway 路由

| 路徑 | 目標（K8s Service） |
|---|---|
| `/api/v1/products/**`、`/api/v1/categories/**` | `http://product-service:8081` |
| `/api/v1/orders/**` | `http://order-service:8082` |
| `/api/v1/inventory/**` | `http://inventory-service:8083` |
| `/api/v1/auth/**`、`/api/v1/users/**` | `http://user-service:8084` |
| `/api/v1/payments/**` | `http://payment-service:8085` |

- **不需要 Spring Cloud Kubernetes 或 discovery client**：K8s Service 本身就提供 DNS 與負載均衡，`uri` 直接寫 Service 名稱
- 目標 URI 用 profile 切換：本機用 `localhost:{port}`，叢集內用 `{service}:{port}`。服務之間的內部呼叫也用同樣方式設定 base URL
- 不需登入的路由：`/api/v1/auth/**`、`GET /api/v1/products/**`、`GET /api/v1/categories/**`、`GET /api/v1/inventory/**`；其餘都要 JWT，後台路由另外檢查 `ADMIN`
- **先移除用戶端送來的 `X-User-*` header**，再填入從 JWT 解出的值；否則任何人都能自己帶 `X-User-Id` 冒充別人
- `/internal/**` 一律不建路由；之後在 K8s 加 NetworkPolicy，限制只有特定服務能互相呼叫

---

## 服務互動：下單流程

```
前端 ──▶ Gateway（驗 JWT，帶 X-User-Id）
          │
          ▼
   order-service  POST /api/v1/orders
     1. ──▶ product   GET  /internal/v1/products/batch           取價格、名稱快照；檢查商品是否已上架
     2. ──▶ inventory POST /internal/v1/inventory/reservations   預扣（失敗 → 建單失敗）
     3. 存訂單，狀態 PENDING_PAYMENT
          │
前端 ──▶ payment-service  POST /api/v1/payments
     4. ──▶ order     GET  /internal/v1/orders/{id}              取金額與狀態
     5. mock-callback 成功：payment 狀態與 PaymentSucceeded 同交易寫入 Outbox
     6. Kafka payment.events → order：訂單改為 PAID，並寫 OrderPaid
     7. Kafka order.events → inventory：確認預扣

   取消或逾時：order 寫 OrderCancelled → Kafka order.events → inventory 釋放預扣
```

### 雛型階段：同步 REST

- 服務之間用 RestClient 或 HTTP Interface 呼叫，搭配 Resilience4j 的 timeout、retry
- 原因：中介軟體評估文件把 Kafka 排在第 3 步，事件機制還沒建立

### 階段 C：事件驅動

Kafka 使用單節點 KRaft。業務資料與事件在同一個 PostgreSQL 交易寫入 Outbox；輪詢器以至少一次方式發送，消費者在同一個交易記錄 `eventId` 並更新業務資料，重複事件會略過。失敗重試 4 次後送到同名 `.DLT` topic。事件 JSON 欄位為 `eventId`、`type`、`version`、`occurredAt`、`aggregateId`、`payload`；同一 aggregate 以 id 作為 Kafka key。

事件 payload 與版本規則見[事件契約](event-contracts.md)。

建單時查商品與預扣、建立付款時查訂單金額仍維持同步，因為這些步驟需要即時結果。付款結果、訂單付款／取消後的庫存確認與釋放則走事件：

| Topic | 事件 | 發送方 | 接收方 |
|---|---|---|---|
| `payment.events` | `PaymentSucceeded`、`PaymentFailed` | payment | order |
| `order.events` | `OrderPaid`、`OrderCancelled` | order | inventory；之後 notification |
| `product.events` | `ProductCreated`、`ProductUpdated`、`ProductStatusChanged` | product | 之後搜尋索引與快取失效 |
| `user.events` | `UserRegistered` | user | 之後 notification |

庫存預扣逾時釋放排程保留，作為訂單服務長時間故障時的安全網。Kafka 目前只靠 NetworkPolicy 隔離，沒有 SASL/ACL；不可直接暴露到不受信任的網路。

---

## 建立順序

| 順序 | 項目 | 重點 |
|---|---|---|
| 1 | user-service | Gateway 驗 JWT 需要它，最先做；步驟見 [user-service-implementation-plan.md](../implementation-plans/backend/user-service-implementation-plan.md) |
| 2 | api-gateway | 先接 product + user；驗證 JWT 驗簽、`X-User-Id` 傳遞、`/internal` 封鎖 |
| 3 | 上 K8s | 用 kind 或 minikube 部署 gateway、product、user；熟悉 Deployment、Service、ConfigMap、Secret |
| 4 | inventory-service | 預扣、確認、釋放與冪等 |
| 5 | order-service | 串起 product + inventory |
| 6 | payment-service | 把整個下單流程跑完 |

開發時仍用 IDE 跑服務、用 compose 起 DB 與 Redis；K8s 用來驗證部署與服務之間的連線。


## 階段 D：搜尋基礎設施（D1–D6 完成，D7 驗收中）

Elasticsearch 9.4.5 搭配官方 SmartCN 外掛，已加入 Compose 與 kind；NetworkPolicy 只允許商品服務連線，Kibana 僅限本機開發 profile。商品服務以 `product.events` 維護搜尋索引，前台關鍵字查詢使用 Elasticsearch 並在不可用時退回 SQL；回表資料仍以 PostgreSQL 為準。Grafana 儀表板顯示搜尋引擎用量、退回原因、ES 平均延遲、索引事件、重建文件數與過期命中，持續退回 SQL 有專屬告警；索引延遲使用既有 Kafka consumer lag 告警。SEC-14 記錄了目前僅適用隔離開發環境的明文 HTTP 限制。詳見[搜尋實作紀錄](../implementation-plans/middleware/search-implementation-record.md)與[事件契約](event-contracts.md)。
