# 已知的安全問題

> 建立日期：2026-11-11
> 範圍：vue-customer 與後端 6 個服務（經 api-gateway 與直連）的本機開發環境
> 方式：前端程式碼與依賴檢查，加上對運行中的服務做授權、偽造身分與濫用探測（探測腳本不放進 repo，結果記在這裡）

雛型階段的取捨與「上線前必修」要分開看：**嚴重度高的項目在任何對外環境（包含 K8s 上給別人連的環境）之前必須處理**。狀態欄會隨處理進度更新。

## 總表

| 編號 | 問題 | 嚴重度 | 狀態 | 預計處理 |
| ---- | ---- | ------ | ---- | -------- |
| [SEC-01](#sec-01-顧客可以自己把訂單標成付款成功) | 顧客可以自己把訂單標成付款成功 | 高 | 未處理 | 接真的金流前 |
| [SEC-02](#sec-02-繞過-gateway-直連服務可以偽造任何人的身分) | 繞過 Gateway 直連服務可以偽造任何人的身分 | 高 | **K8s 上已處理並實測**（NetworkPolicy）；本機開發環境仍然如此 | — |
| [SEC-03](#sec-03-開發環境的預設設定不能帶上線) | 開發環境的預設設定不能帶上線 | 高 | **K8s 上已處理**（隨機 Secret、Redis 密碼）；compose 與 repo 裡的開發用設定未改 | compose 的埠綁定 |
| [SEC-04](#sec-04-註冊沒有頻率限制) | 註冊沒有頻率限制，且可列舉 Email | 中 | 未處理 | 待排 |
| [SEC-05](#sec-05-登入鎖定依-email可被用來鎖住別人) | 登入鎖定依 Email，可被用來鎖住別人 | 中 | 未處理 | 待評估 |
| [SEC-06](#sec-06-前端缺少瀏覽器端防護) | 前端缺少瀏覽器端防護（CSP、標頭；refresh token 在 localStorage） | 中 | **部分處理**：標頭已加，CSP 已在真實 Chrome 驗證；token 仍在 localStorage | Step 11 |
| [SEC-07](#sec-07-登出所有裝置後已發出的-access-token-仍然有效) | 登出所有裝置後已發出的 access token 仍然有效 | 中（設計限制） | 已在畫面說明 | — |
| [SEC-08](#sec-08-錯誤訊息洩漏內部資訊) | 錯誤訊息洩漏內部類別名稱等資訊 | 低 | 未處理 | 待排 |
| [SEC-09](#sec-09-服務直連的-actuator-與-swagger) | 服務直連的 actuator 公開、Swagger 預設開啟 | 低 | 未處理 | Step 11 |
| [SEC-10](#sec-10-建單沒有冪等-key) | 建單沒有冪等 key | 低 | 未處理 | 見 vue-customer「之後再處理」 |
| [SEC-11](#sec-11-productsbatch-洩漏非上架商品的內容) | `/products/batch` 洩漏非上架商品的內容 | 中 | **已修正** | `0139c00` |
| [SEC-12](#sec-12-非-dev-環境沒有建立管理員的方式) | 非 dev 環境沒有建立管理員的方式 | 中 | 未處理（K8s 上暫用 dev profile） | 待排 |

---

## SEC-01 顧客可以自己把訂單標成付款成功

- **現象**：`POST /api/v1/payments/{id}/mock-callback` 讓付款的本人（或 ADMIN）呼叫，指定 `SUCCESS` 或 `FAILED`。顧客建單、建立付款後自己呼叫 `SUCCESS`，訂單就變成 `PAID`，等於不用付錢。
- **驗證**：以一般顧客帳號實際走過：建單 → 建立付款 → `mock-callback` `SUCCESS` → 0.08 秒後訂單 `PAID`。
- **成因**：雛型階段用它模擬第三方金流的回呼，payment-service 的程式碼註解已寫明「之後會移除這支 API」。
- **處理方向**：接真的金流前移除；真實金流的回呼要由第三方伺服器呼叫，並驗證簽章，不經過使用者身分。在此之前任何對外環境都不能開放這支 API（例如 Gateway 不轉發 `/payments/*/mock-callback`，或用設定開關只在開發環境啟用）。

## SEC-02 繞過 Gateway 直連服務可以偽造任何人的身分

- **現象**：各服務不驗 JWT，信任 Gateway 帶來的 `X-User-Id`、`X-User-Roles`。直連 order-service 並帶上偽造的 `X-User-Id` 會被接受；同理可以偽造 `X-User-Roles: ADMIN`。
- **驗證**：對 `http://localhost:8082/api/v1/orders` 帶隨機的 `X-User-Id`，回 200。
- **不受影響的路徑**：經過 Gateway 時這些 header 會被清掉再重新帶上；探測過「顧客帶偽造 `X-User-Roles: ADMIN`」、「匿名帶偽造 `X-User-Id` 建單」、「匿名帶偽造 `X-User-Roles` 讀草稿」都被擋下。
- **風險**：安全完全取決於「服務的埠不能被外部連到」。
- **進度（2026-11-11）**：[後端部署](../implementation-plans/backend-deployment-implementation-plan.md) 已完成。所有服務都是 `ClusterIP`，並加上 `default-deny-ingress` 與逐一開放的 `NetworkPolicy`。**實際測過攔截**：從沒有授權的 Pod 連不到任何後端服務、資料庫、Redis、物件儲存，只連得到前端；前端只連得到 Gateway；各服務只連得到被允許的對象（完整矩陣見該文件）。
  - 重要：**kind 預設的 kindnet 在 Windows／WSL2 上完全不執行 NetworkPolicy**（policy 建立成功卻沒有作用），要用 Calico（`infra/k8s/scripts/kind-up.sh`）。換任何叢集都要用同樣的對照組實測，不能只看 `kubectl apply` 成功
  - 還沒做：出去（Egress）的限制；本機直接啟動（非 K8s）的開發環境仍然沒有隔離
- **處理方向**：
  - K8s 上所有後端服務只用 `ClusterIP`，不可用 `NodePort`、`LoadBalancer`，對外只開 api-gateway（或 Ingress）
  - 加 `NetworkPolicy`：只允許 api-gateway 連進各服務，服務之間只開需要的方向（order → product / inventory、payment → order）
  - 長期可以讓服務自己驗 JWT，或在服務之間改用 mTLS，就不再只靠網路隔離
- 本機的 `docker-compose` 與直接啟動，所有服務都在 localhost 上，開發時可以接受。

## SEC-03 開發環境的預設設定不能帶上線

- 資料庫帳密預設 `postgres / postgres`，Redis 沒有密碼
- `infra/docker-compose.yml` 的埠寫成 `5432:5432` 這種形式，會綁在所有網卡上，同一個網路的人可以直接連資料庫與 Redis
- `infra/seaweedfs/s3.json` 有 commit 一組開發用的物件儲存密鑰；`backend/spring-product-service/src/test/resources/seaweedfs/s3.json` 與 `backend/spring-user-service/src/test/resources/jwt/*.pem`（測試用 JWT 金鑰）也在 git 裡
- 沒有問題的部分：user-service 的 `secrets/`（JWT 私鑰、管理員密碼）已被 `.gitignore`，沒有進 git
- **進度（2026-11-11）**：K8s 部署不使用這些預設值：資料庫與 Redis 密碼、JWT 金鑰、物件儲存金鑰、管理員密碼都由 `infra/k8s/scripts/create-secrets.sh` 隨機產生、只存在叢集的 Secret 裡；Redis 有設密碼（沒帶密碼回 `NOAUTH`）；repo 的 `infra/k8s` 沒有任何 Secret 的值。**還沒處理**：`infra/docker-compose.yml` 的埠綁定與預設密碼（本機開發用）、repo 裡 commit 的測試用金鑰與 `s3.json`。
- **處理方向**：
  - compose 的埠改成 `127.0.0.1:5432:5432`
  - 正式環境的資料庫密碼、物件儲存密鑰、JWT 金鑰都用 K8s `Secret`，不寫進 repo
  - 確認正式環境沒有沿用測試用的 JWT 金鑰與物件儲存密鑰（沿用等於金鑰已公開）

## SEC-04 註冊沒有頻率限制

- **現象**：連續呼叫 15 次 `POST /auth/register`，全部成功（201）。登入有限制（第 6 次錯誤起回 429），註冊沒有。
- **風險**：可以大量灌垃圾帳號；`EMAIL_ALREADY_EXISTS` 的回應也能列舉哪些 Email 已經註冊。
- **處理方向**：Gateway 對 `/auth/register` 加和登入同樣的依 IP 限流；列舉 Email 是註冊流程的固有取捨，要避免的話得改成「一律回成功、寄信確認」，需要有寄信功能後再評估。

## SEC-05 登入鎖定依 Email，可被用來鎖住別人

- **現象**：user-service 依 Email 限制登入錯誤次數（連續 5 次錯誤後回 429 `TOO_MANY_LOGIN_ATTEMPTS`）。任何人只要知道某個 Email，連續打錯就能讓那個帳號暫時無法登入。
- **性質**：常見的取捨（防暴力破解 vs. 可被用來拒絕服務），不一定要改。
- **處理方向**：評估鎖定時間是否夠短、是否改成「Email + IP」的組合、或鎖定時要求額外驗證。

## SEC-06 前端缺少瀏覽器端防護

- refresh token 存在 localStorage：一旦出現 XSS 就會被竊取。目前前端沒有 `v-html`、`innerHTML`、`eval`，商品說明等外部內容都用文字插值輸出，`npm audit --omit=dev` 為 0 個漏洞，所以現況風險低，但沒有第二道防線
- 還沒有 `Content-Security-Policy`、`X-Frame-Options`（或 `frame-ancestors`）、`X-Content-Type-Options: nosniff`、`Referrer-Policy`
- **處理方向**：Step 11 的 nginx 設定加上這些標頭；之後考慮把 refresh token 改成 `HttpOnly` cookie（會牽涉 CSRF 與後端改動）。
- **進度（2026-11-11）**：[vue-customer Step 11](../implementation-plans/vue-customer/step-11-deployment.md) 的 nginx 已加上 CSP（嚴格：只允許同源，圖片另外允許物件儲存）、`nosniff`、`X-Frame-Options`、`Referrer-Policy`、`Permissions-Policy`，並用 Docker 與 kind 驗證每個回應都帶標頭。**CSP 已在真實 Chrome 驗證**（2026-11-11，兩個前端的 Playwright E2E 對著 nginx 映像檔跑：商店與後台完整流程都沒有 CSP 違規，包含後台的圖片直傳；故意塞入內嵌腳本與外部圖片，瀏覽器確實擋下並回報）；HSTS 要在 TLS 終結處（Ingress）加；後台（vue-management）也已套用同一套（[Step 11](../implementation-plans/vue-management/step-11-deployment.md)，CSP 的 `connect-src` 另外允許物件儲存的來源，因為圖片是瀏覽器直傳）；refresh token 還在 localStorage。

## SEC-07 登出所有裝置後已發出的 access token 仍然有效

- **現象**：`DELETE /users/me/sessions` 撤銷所有 refresh token，但已經發出的 access token 在到期前仍然有效（`access-token-ttl: 15m`）。
- **性質**：無狀態 JWT 的限制。帳號頁已經明確告知使用者。
- **處理方向**：縮短 access token 時效，或之後加 token 黑名單（Redis）；目前不處理。
- **已驗證**：撤銷之後用舊的 refresh token 換新 token，立刻回 401 `REFRESH_TOKEN_INVALID`。

## SEC-08 錯誤訊息洩漏內部資訊

- **現象**：不合法參數的 400 錯誤，`detail` 會帶 Spring 的原始訊息，例如 `GET /orders?status=HACKED` 會顯示 `com.mymicroservice.order.enums.OrderStatus` 這個類別名稱；`GET /products/not-a-uuid` 的 400 也是原始訊息，而且沒有 `code` 欄位（不像其他錯誤是統一的 ProblemDetail + `code`）。
- **風險**：洩漏內部套件與類別名稱，對攻擊者有一點參考價值；也讓錯誤格式不一致。
- **處理方向**：在共用的 `GlobalExceptionHandler` 處理 `MethodArgumentTypeMismatchException`，回統一的 `VALIDATION_FAILED` 與不含內部類別名稱的訊息。前端目前把 400 當成「找不到」，不依賴這個訊息。

## SEC-09 服務直連的 actuator 與 Swagger

- 直連各服務的 `/actuator/health` 是公開的（只有 health，目前可以接受）；經 Gateway 的 `/actuator/*`、`/v3/api-docs`、`/swagger-ui*` 對匿名者回 401，沒有問題
- springdoc 預設開啟，product-service 啟動日誌也有警告
- **處理方向**：正式環境設 `springdoc.api-docs.enabled=false`、`springdoc.swagger-ui.enabled=false`；actuator 只開放 K8s 探針需要的 health，並且不對外。

## SEC-10 建單沒有冪等 key

- **現象**：`POST /orders` 沒有 `Idempotency-Key`，連點或網路重送會建立兩張訂單（各自預扣庫存）。
- **現況**：前端送出中停用按鈕、成功後 `replace` 導頁，並在「沒收到回應」時提示使用者先到「我的訂單」確認；後端沒有防護。
- **處理方向**：見 [vue-customer 計畫](../implementation-plans/vue-customer/README.md) 的「之後再處理」。

## SEC-11 `/products/batch` 洩漏非上架商品的內容

- **現象**：匿名者用草稿商品的 id 查 `GET /products/batch`，拿到完整的 SKU、名稱、價格。
- **修正**：非 ADMIN 時，非 `ACTIVE` 的商品只回 `id` 與 `status`；commit `0139c00`（後端與整合測試）、`12aead5`（前端）。細節見 [vue-customer Step 0 的後續補強](../implementation-plans/vue-customer/step-00-backend-prereqs.md)。
- **已用真的服務驗證**：匿名只拿到 `id`、`status`；ADMIN 照舊完整；order-service 建單不受影響。

## SEC-12 非 dev 環境沒有建立管理員的方式

- **現象**：`BootstrapAdminInitializer`（啟動時建立第一個管理員）標了 `@Profile("dev")`，只在 `dev` profile 啟用。其他 profile 沒有任何方式建立 ADMIN：註冊只能建立 `CUSTOMER`。
- **現況**：K8s 上 user-service 暫時用 `SPRING_PROFILES_ACTIVE=dev`，管理員的帳密來自 Secret `bootstrap-admin`（隨機密碼）。`application-dev.yml` 目前只有管理員相關設定，所以實際影響只是名稱誤導，但之後 dev profile 加了別的開發用設定就會一起帶進叢集。
- **處理方向**：把建立管理員改成由設定開關控制（例如 `app.bootstrap-admin.enabled`）而不是綁在 `dev` profile；建立後要有輪替或停用的作法（第一個管理員建好之後關掉開關、改密碼）。這是後端程式碼的修改，要補整合測試。

---

## 驗證過沒有問題的

| 項目 | 結果 |
| ---- | ---- |
| 顧客呼叫管理 API（修改庫存、新增分類與商品、使用者列表、訂單出貨） | 一律 403 |
| 匿名修改庫存 | 401 |
| 顧客帶偽造 `X-User-Roles: ADMIN`（經 Gateway） | 403，header 被 Gateway 清掉 |
| 匿名帶偽造 `X-User-Id` 建單、帶偽造 `X-User-Roles` 讀草稿（經 Gateway） | 401、404 |
| JWT `alg=none`、竄改 `roles` 的 JWT | 401 |
| 註冊時帶 `roles: ["ADMIN"]` | 忽略，實際只有 `CUSTOMER` |
| 別人的訂單、付款、取消、付款回呼 | 一律 404，不透露是否存在 |
| 登入限流 | 連續錯誤第 6 次起回 429 |
| Gateway 的 `/actuator/*`、`/v3/api-docs`、`/swagger-ui*`（匿名） | 401 |
| 匿名讀草稿、已下架、已封存商品（列表、詳情、圖片） | 404／不出現在列表 |
| 前端 `v-html`、`innerHTML`、`eval` | 沒有使用 |
| 前端 `redirect` 參數 | 只接受站內路徑（有測試）；擋外部網址、`//`、`/\` |
| 前端依賴 `npm audit --omit=dev` | 0 個漏洞 |
| 商品說明裡的 HTML | 當成文字顯示，不會被執行（有測試） |

## 還沒有檢查的

- SQL injection 與（後台、前台）XSS 的模糊測試。商品關鍵字查詢有跳脫 `%`、`_`，也用 JPA 參數綁定，但沒有實際模糊測試
- 圖片上傳的類型與內容檢查（presigned URL 有簽 `Content-Type` 與 `Content-Length`，但沒有試過偽造副檔名、內容與類型不符）
- 後端 Maven 依賴的已知漏洞（只檢查了 vue-customer 的 npm 依賴）
- Gateway 是否信任 `X-Forwarded-For`：若信任，依 IP 的登入限流可以被偽造 header 繞過
- JWT 金鑰輪替流程的實際演練
- vue-management（後台）的前端安全檢查
- 密碼雜湊參數（bcrypt cost）、refresh token 的產生與儲存方式的審查

## 探測遺留的測試資料

探測與對接測試在開發資料庫留下測試帳號：`sec-*`、`flood-*`（本次約 17 個）、`api-*`（先前的 API 對接檢查），以及這些帳號的訂單。都沒有刪除；`E2E` 開頭的舊分類與商品只在商店隱藏（分類停用、商品封存）。要徹底清除需要直接操作資料庫，需要你確認清單後才做。
