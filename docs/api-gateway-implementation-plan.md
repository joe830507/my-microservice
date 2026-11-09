# api-gateway 實作步驟

> 建立日期：2026-11-09
> 分支：`feature/api-gateway`
> 對應：[microservice-architecture.md](microservice-architecture.md)「api-gateway 路由」
> 狀態：Step 2 完成（2026-11-09），下一步 Step 3

## 進度

| Step | 內容 | 狀態 |
|---|---|---|
| 1 | 建立專案、路由到 product-service 與 user-service | 完成 |
| 2 | JWT 驗簽（JWKS）與需要登入的路由 | 完成 |
| 3 | 身分 header：移除用戶端送來的 `X-User-*`，改填 JWT 的值 | 未開始 |
| 4 | 封鎖 `/internal/**` | 未開始 |
| 5 | 依來源 IP 限制登入請求次數（Redis） | 未開始 |

每一步都附整合測試，測試通過後單獨 commit。

---

## 版本

| 項目 | 版本 | 原因 |
|---|---|---|
| Spring Boot | **4.0.9** | Spring Cloud 正式版只支援 Boot 4.0.x |
| Spring Cloud | **2025.1.4**（Gateway 5.0.4） | 目前最新正式版；支援 Boot 4.1 的 2026.0.0 還在 RC |
| Java | 21 | 和其他服務一致 |

- 其他服務是 Boot 4.1.1。Gateway 不依賴 common，各服務可以各自選 Boot 版本，差一個小版本沒有影響
- 2026.0.0 正式版發布後，Gateway 再升到 Boot 4.1

---

## Step 1：建立專案、路由

| 項目 | 值 |
|---|---|
| Group / Artifact | `com.mymicroservice` / `api-gateway` |
| Package | `com.mymicroservice.gateway` |
| 位置 | `backend/spring-api-gateway`，加入 `backend/pom.xml` 的 modules |
| port | 8080 |
| 依賴 | `spring-cloud-starter-gateway-server-webflux`、Actuator |

- **reactive（WebFlux）**，不是 Servlet：Gateway 大部分時間在等下游回應，用非阻塞 I/O 比較省資源
- 不依賴 common：common 是 Servlet 與 JPA 的元件
- 路由寫在 `application.yml`，目標 URI 用設定值，例如 `gateway.services.product-uri`：本機是 `http://localhost:8081`，K8s 是 `http://product-service:8081`（之後用 profile 或環境變數切換）
- **不用 discovery client**：K8s Service 本身就提供 DNS 與負載均衡

| 路徑 | 目標 |
|---|---|
| `/api/v1/products/**`、`/api/v1/categories/**` | product-service |
| `/api/v1/auth/**`、`/api/v1/users/**` | user-service |

order、inventory、payment 的路由等服務建立後再加。

**測試**：用 WireMock 模擬下游服務，確認路徑轉到正確的服務、method、query、body 原樣轉送，不在路由表的路徑回 404。

**已完成**：`ApiGatewayApplication`、`application.yml` 的路由；測試 `GatewayRoutingIntegrationTests`（16）。

- Spring Cloud Gateway 5 的設定前綴是 `spring.cloud.gateway.server.webflux.*`（舊的 `spring.cloud.gateway.*` 已不再使用）
- 下游位置用環境變數 `PRODUCT_SERVICE_URI`、`USER_SERVICE_URI` 覆蓋
- 已編碼的 query（例如中文關鍵字 `%E9%9E%8B`）原樣轉送，不會重複編碼
- 下游連線失敗回 5xx
- 實際啟動驗證：經過 Gateway（8080）呼叫 user-service 的 JWKS、登入，以及 product-service 的商品列表都正常

---

## Step 2：JWT 驗簽

用 Spring Security 的 resource server（WebFlux 版）：

| 項目 | 做法 |
|---|---|
| 公鑰 | `jwk-set-uri` 指向 user-service 的 `/api/v1/auth/jwks`；依 token header 的 `kid` 挑公鑰，遇到不認得的 `kid` 會重新抓（支援金鑰輪替） |
| 驗證 | 簽章（RS256）、`exp`、`iss` 必須是 `user-service` |
| 不需登入 | `/api/v1/auth/**`（refresh、logout 用 refresh token 證明身分）、`GET /api/v1/products/**`、`GET /api/v1/categories/**`、`/actuator/health` |
| 其餘 | 都要有效的 JWT，否則 401 |
| 錯誤格式 | 和下游服務一致：ProblemDetail + `code`（例如 `UNAUTHENTICATED`） |

- 後台路由的 `ADMIN` 檢查留在下游服務（`@RequireRoles`）：Gateway 只確認「有登入」，角色規則跟著各服務的 API 走，不用兩邊同步維護

**測試**：測試裡自己產生金鑰對，用 WireMock 提供 JWKS；涵蓋沒帶 token、簽章錯誤、過期、`iss` 不符、未知 `kid`、公開路由不帶 token 也能通過。

**已完成**：`SecurityConfig`、`JwtProperties`（`gateway.security.jwt.*`）、`ProblemDetailAuthenticationEntryPoint`；測試 `GatewayJwtIntegrationTests`（25），`GatewayRoutingIntegrationTests` 需要登入的路由改帶 token（16）。

- 除了 `iss`，也要求 `sub` 必須存在（Step 3 要轉成 `X-User-Id`）
- `/api/v1/auth/**` **不讀** `Authorization`：用戶端常在 access token 過期後才呼叫 refresh，若帶著過期 token，照 Spring 預設會先回 401。其他公開路由（GET 商品）帶了無效 token 仍回 401
- 不在路由表的路徑：沒登入回 401，有登入才回 404，不洩漏哪些路徑存在
- 401 帶 `WWW-Authenticate: Bearer`（token 無效時加 `error="invalid_token"`）；`detail` 不說明是過期還是簽章錯誤
- 金鑰輪替：未知 `kid` 會重新抓 JWKS，測試確認換金鑰後新舊 token 都能通過

---

## Step 3：身分 header

| 情況 | 轉給下游的 header |
|---|---|
| 任何請求 | **先移除**用戶端送來的 `X-User-Id`、`X-User-Roles`（不分大小寫） |
| 有有效的 JWT | 加上 `X-User-Id`（`sub`）、`X-User-Roles`（`roles` 以逗號串接） |

- 公開路由也要移除，否則用戶端可以在註冊、登入時偽造身分，讓稽核欄位記錯人
- 這一步不做，下游所有「信任 header」的設計（`@CurrentUserId`、`@RequireRoles`、稽核）都會失效

**測試**：WireMock 檢查下游收到的 header；用戶端偽造的 `X-User-Id` 在公開與需登入的路由上都要被換掉或移除。

---

## Step 4：封鎖 `/internal/**`

- 路由表本來就沒有 `/internal/**`，但另外明確擋下（回 404），避免之後有人加了 `/api/**` 之類的寬鬆路由時不小心放行
- 也擋下路徑繞過的寫法，例如 `/api/v1/../internal/...`、URL 編碼的 `%2e%2e`

**測試**：各種 `/internal` 寫法都回 404，而且下游完全沒收到請求。

---

## Step 5：依來源 IP 限制登入次數

user-service 只依 email 限制登入失敗，知道 email 的人可以故意鎖住對方；同一個 IP 大量嘗試不同 email 也擋不住。這兩件事要在 Gateway 依 IP 處理。

| 項目 | 做法 |
|---|---|
| 範圍 | `POST /api/v1/auth/login`（之後視需要加 register、refresh） |
| 實作 | Spring Cloud Gateway 的 `RequestRateLimiter` + `RedisRateLimiter`（token bucket），多個 Gateway 實例共用計數 |
| Redis | 新增 `gateway-redis`（port 6381），和其他服務分開 |
| 來源 IP | 本機直接用連線 IP；K8s 在 Ingress 後面時要讀 `X-Forwarded-For`，只信任最後一層代理加上的值（`XForwardedRemoteAddressResolver`），否則用戶端可以偽造 IP |
| 超過上限 | 429，ProblemDetail + `code` |
| Redis 掛掉 | 放行（`RedisRateLimiter` 預設行為），和 user-service 一致 |

**測試**：Testcontainers 的 Redis；同一個 IP 超過上限回 429，不同 IP 不受影響，其他路由不受限制。

---

## 之後再處理

- order、inventory、payment 的路由（服務建立後）
- CORS（前端接上時）
- 上 K8s：目標 URI 改成 Service 名稱、`/internal` 加 NetworkPolicy
- 升級到 Boot 4.1 + Spring Cloud 2026.0.0（正式版發布後）
