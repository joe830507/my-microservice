# 微服務整體架構（雛型階段）

> 設計日期：2026-11-09
> 目標：先把各微服務建立起來，再統一接到 API Gateway
> 狀態：討論完成，尚未實作（product-service 已完成）

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
| `spring-api-gateway` | 路由、JWT 驗證、身分傳遞、之後的限流 | 空目錄 |
| `spring-product-service` | 分類、商品、商品圖片 | 已實作（含 Redis 快取） |
| `spring-user-service` | 註冊、登入、JWT 簽發、會員與角色 | 待建立 |
| `spring-inventory-service` | 庫存、預扣、確認、釋放 | 待建立 |
| `spring-order-service` | 訂單、商品快照、訂單狀態 | 空目錄 |
| `spring-payment-service` | 付款（雛型階段用模擬金流） | 待建立 |

### 暫不拆分

| 服務 | 原因 |
|---|---|
| cart-service | 先放在前端或 order-service 用 Redis 存，邏輯變複雜再拆 |
| search-service | 依中介軟體評估文件，ES 讀取先放在 product-service，由 MQ 同步 |
| notification-service | 等 Kafka 事件機制建立後再做 |

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

| 服務 | 應用程式 port | DB port |
|---|---|---|
| api-gateway | 8080 | — |
| product-service | 8081 | 5432 |
| order-service | 8082 | 5433 |
| inventory-service | 8083 | 5434 |
| user-service | 8084 | 5435 |
| payment-service | 8085 | 5436 |

> 現有的 `GET /api/v1/products/batch` 主要給 order-service 用。之後可以搬到 `/internal/v1/products/batch`，或是對內、對外各留一支；雛型階段先不動。

---

## 後端專案結構

```
backend/
├── pom.xml                    聚合 pom（packaging pom，只有 <modules>）
├── common/                    共用模組（Spring Boot auto-configuration）
├── spring-product-service/    parent：spring-boot-starter-parent，依賴 common
├── spring-user-service/       同上
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

- 版本固定 `0.0.1-SNAPSHOT`，透過聚合 pom 一起建置，不發佈到 repository
- 單獨建置某個服務：`./mvnw -pl spring-user-service -am package`（`-am` 會先建置 common）
- 改 common 等於所有服務都要重新建置，所以要保持精簡
- 實作步驟見 [backend-common-implementation-plan.md](backend-common-implementation-plan.md)

---

## 各服務最小 API

### user-service

| 方法 | 路徑 | 說明 |
|---|---|---|
| POST | `/api/v1/auth/register` | 註冊 |
| POST | `/api/v1/auth/login` | 回傳 access token、refresh token |
| POST | `/api/v1/auth/refresh` | 換新的 access token |
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

- 資料表：`stock(sku, available, reserved, version)`、`reservation(order_id, sku, qty, status)`
- 雛型階段用 DB 樂觀鎖，或 `UPDATE ... WHERE available >= ?`；之後換成 Redis Lua（見中介軟體評估文件「服務邊界提醒」）
- SKU 由後台用 `PUT /inventory/{sku}` 建立，雛型階段不做跨服務驗證；之後改成接收 product 的 `ProductCreated` 事件

### order-service

| 方法 | 路徑 | 說明 |
|---|---|---|
| POST | `/api/v1/orders` | 建立訂單，body 帶 `items[{sku, qty}]` |
| GET | `/api/v1/orders/{id}` | 訂單詳情，只能看自己的 |
| GET | `/api/v1/orders` | 自己的訂單列表；ADMIN 可查全部 |
| POST | `/api/v1/orders/{id}/cancel` | 取消，只限尚未付款的訂單 |
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
- 不需登入的路由：`/api/v1/auth/**`、`GET /api/v1/products/**`、`GET /api/v1/categories/**`；其餘都要 JWT，後台路由另外檢查 `ADMIN`
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
     5. mock-callback 成功
     6. ──▶ order     POST /internal/v1/orders/{id}/paid
                         └──▶ inventory POST .../reservations/{orderId}/confirm

   取消或逾時：order ──▶ inventory POST .../reservations/{orderId}/release
```

### 雛型階段：同步 REST

- 服務之間用 RestClient 或 HTTP Interface 呼叫，搭配 Resilience4j 的 timeout、retry
- 原因：中介軟體評估文件把 Kafka 排在第 3 步，事件機制還沒建立

### 之後：事件驅動

Kafka + Outbox 導入後，把步驟 6 和「逾時釋放」改成事件，變成 choreography Saga：

| 事件 | 發送方 | 接收方 |
|---|---|---|
| `PaymentSucceeded` | payment | order（改為 PAID）、inventory（確認扣除） |
| `OrderCancelled` | order | inventory（釋放預扣） |
| `ProductCreated` | product | inventory（建立 SKU 庫存紀錄） |

---

## 建立順序

| 順序 | 項目 | 重點 |
|---|---|---|
| 1 | user-service | Gateway 驗 JWT 需要它，最先做 |
| 2 | api-gateway | 先接 product + user；驗證 JWT 驗簽、`X-User-Id` 傳遞、`/internal` 封鎖 |
| 3 | 上 K8s | 用 kind 或 minikube 部署 gateway、product、user；熟悉 Deployment、Service、ConfigMap、Secret |
| 4 | inventory-service | 預扣、確認、釋放與冪等 |
| 5 | order-service | 串起 product + inventory |
| 6 | payment-service | 把整個下單流程跑完 |

開發時仍用 IDE 跑服務、用 compose 起 DB 與 Redis；K8s 用來驗證部署與服務之間的連線。
