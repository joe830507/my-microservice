# user-service 實作步驟

> 建立日期：2026-11-09
> 分支：`feature/user-service`
> 對應：[microservice-architecture.md](microservice-architecture.md)「user-service」
> 狀態：Step 1～3 完成（2026-11-09），下一步 Step 4

## 進度

| Step | 內容 | 狀態 |
|---|---|---|
| 1 | 建立專案 | 完成 |
| 2 | DB 與設定 | 完成 |
| 3 | 第一個 migration（使用者、角色） | 完成 |
| 4 | 註冊、登入（密碼雜湊、簽發 JWT） | 未開始 |
| 5 | JWKS endpoint、`/users/me`、後台使用者列表 | 未開始 |
| 6 | Refresh token（存放、輪替） | 未開始 |

**第一個里程碑（Step 1～3）**：服務能連上自己的 DB 並啟動，Flyway 建好資料表。

---

## Step 1：建立專案

用 Spring Initializr 建立，設定和 product-service 一致：

| 項目 | 值 |
|---|---|
| Spring Boot | 4.1.1 |
| Java | 21 |
| Group | `com.mymicroservice` |
| Artifact | `user-service` |
| Package | `com.mymicroservice.user` |
| 位置 | `backend/spring-user-service` |

依賴：Web、Data JPA、Flyway、PostgreSQL、Validation、Actuator、Lombok、DevTools。springdoc 另外手動加，版本和 product 一樣用 `${springdoc.version}`。

### 不加 `spring-boot-starter-security`

加了之後所有 endpoint 預設都會被鎖住，還要另外寫 `SecurityFilterChain` 全部放行。依照架構文件，**驗 JWT 是 Gateway 的工作**，user-service 只需要兩個函式庫（Step 4 再加）：

| 用途 | 依賴 | 使用的 API |
|---|---|---|
| 雜湊密碼 | `spring-security-crypto` | `PasswordEncoderFactories.createDelegatingPasswordEncoder()` |
| 簽發 RS256 JWT、產生 JWKS | `spring-security-oauth2-jose` | `NimbusJwtEncoder`、Nimbus 的 `JWKSet` |

兩個都是單純的函式庫，不會加進 filter chain。

---

## Step 2：DB 與設定

### `infra/docker-compose.yml`

加入 `user-db`，照 `product-db` 的寫法：

| 項目 | 值 |
|---|---|
| service / container 名稱 | `user-db` / `my-microservice-user-db` |
| `POSTGRES_DB` | `user_db` |
| 對外 port | `5435:5432` |
| volume | 另外建 `user-db-data` |
| healthcheck | 和 product-db 相同 |

### `application.yml`

| 設定 | 值 |
|---|---|
| `server.port` | `8084` |
| `spring.application.name` | `user-service` |
| datasource url | `jdbc:postgresql://localhost:5435/user_db` |
| `spring.flyway.schemas` | `user_service` |
| `spring.jpa.hibernate.ddl-auto` | `validate` |
| `spring.jpa.open-in-view` | `false` |
| springdoc、management | 照 product-service |

---

## Step 3：第一個 migration

`V1__create_user_table.sql`

### 設計重點

- **email 唯一**：要不要不分大小寫？做法一：寫入前一律轉小寫；做法二：建 `lower(email)` 的 unique index。兩種都要搭配查詢時用相同的規則
- **`password_hash`**：DelegatingPasswordEncoder 產生的格式是 `{bcrypt}$2a$10$...`，欄位長度要夠，也要能容納之後換成其他演算法的前綴
- **狀態**：預留 `status`（例如 `ACTIVE`、`DISABLED`），登入時要檢查
- **角色**：架構文件用的是 `X-User-Roles`（複數），建議用關聯表 `user_role(user_id, role)`，不要只放一個 `role` 欄位
- **通用欄位**：id、時間戳、`version` 照 product-service 的 `CommonEntity` 做法。目前每個服務各自複製一份，不急著抽成共用模組

### 完成標準

1. `docker compose up user-db` 啟動 DB
2. 啟動 user-service，Flyway 成功執行 V1
3. `GET /actuator/health` 回傳 `UP`

---

## Step 4：註冊、登入

### CommonEntity 與稽核

**不拆分，沿用 product 的 `CommonEntity`**（稽核欄位 + `@Version`）。

- user-service 裡只有 `User` 需要稽核欄位和樂觀鎖；`RefreshToken` 只有 `created_at`，直接不繼承就好
- 改用條件式 UPDATE 的表，`@LastModifiedDate` 本來就不會觸發，拆出「只有稽核、沒有版本」的父類別也沒有用處
- 等到真的出現「需要稽核但不需要版本」的 entity 時再拆

| 項目 | 決定 |
|---|---|
| 位置 | `com.mymicroservice.user.entity.CommonEntity`（從 product 複製，改 package） |
| 欄位 | `createdAt`、`updatedAt`（`Instant`）、`createdBy`、`updatedBy`（`UUID`）、`version`（`Long`） |
| `version` 型別 | 必須用 `Long`，不能用 `long`：新建時是 `null`，Spring Data 的 `save()` 才會判斷為新資料而用 `persist`，不會先 SELECT 再 `merge` |
| `User.id` | 和 product 一樣用 `@UuidGenerator(style = Style.VERSION_7)`，加上 `@Setter(AccessLevel.NONE)` |

### AuditorAware

| 情況 | 記錄的操作人 |
|---|---|
| 有 `X-User-Id` header（經過 Gateway 的已登入請求） | header 的值 |
| 沒有 header（自己註冊、登入、refresh） | `SYSTEM_USER_ID`（`new UUID(0L, 0L)`） |
| header 不是合法 UUID | `SYSTEM_USER_ID`，並記一筆 warn log |
| 不在 HTTP 請求中（排程、測試） | `SYSTEM_USER_ID` |

- 用 `RequestContextHolder.getRequestAttributes()` 取得目前請求，`null` 表示不在請求中
- user-service 沒有 Spring Security，**不要**照 product 的 TODO 去讀 `SecurityContext`；之後 product 也要改成讀 header
- header 名稱定義成常數，之後各服務共用同一個名稱

### API

| 方法 | 路徑 | 說明 |
|---|---|---|
| POST | `/api/v1/auth/register` | 註冊，預設角色 `CUSTOMER` |
| POST | `/api/v1/auth/login` | 回傳 access token、refresh token（refresh 在 Step 6 才加） |

### JWT 內容

| 項目 | 值 |
|---|---|
| 演算法 | RS256 |
| header `kid` | 金鑰 id，讓 Gateway 從 JWKS 找到對應的公鑰 |
| `iss` | 例如 `user-service`，Gateway 會檢查 |
| `sub` | userId（不要放 email，email 可能會改） |
| `roles` | 角色陣列 |
| `exp` | access token 有效時間短，例如 15 分鐘 |

### 金鑰來源

- **不要每次啟動時隨機產生**：重啟後舊 token 全部失效；部署多台實例時，每台的金鑰也會不一樣
- 從設定檔讀 PEM：本機用設定檔或環境變數，K8s 用 Secret
- 綁一個 `@ConfigurationProperties`，例如 `jwt.private-key`、`jwt.public-key`、`jwt.key-id`、`jwt.issuer`、`jwt.access-token-ttl`

### 需要注意

- 登入失敗時，帳號不存在和密碼錯誤**回傳同一種錯誤**，避免被拿來探測帳號是否存在
- 註冊時 email 已存在，靠 DB 的唯一約束擋下，再把例外轉成錯誤碼，不要只靠先查詢再寫入

### 錯誤碼

| 錯誤碼 | 情境 |
|---|---|
| `EMAIL_ALREADY_EXISTS` | 註冊時 email 已存在 |
| `INVALID_CREDENTIALS` | 帳號不存在或密碼錯誤 |
| `USER_DISABLED` | 帳號已停用 |

---

## Step 5：JWKS、`/users/me`、後台列表

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/api/v1/auth/jwks` | 回傳公鑰的 JWK Set（`JWKSet#toJSONObject()` 預設只輸出公鑰） |
| GET | `/api/v1/users/me` | 從 `X-User-Id` header 取得目前使用者 |
| GET | `/api/v1/users` | 後台分頁查詢（從 `X-User-Roles` 判斷是否為 `ADMIN`） |

- user-service 本身不驗 JWT，信任 Gateway 帶來的 header。本機沒有 Gateway 時，用 curl 手動帶 `X-User-Id` 測試
- 第一個 ADMIN 帳號怎麼產生？可以用 Flyway migration 寫入，或寫成只在 dev profile 執行的初始化程式

---

## Step 6：Refresh token

### API

| 方法 | 路徑 | 說明 |
|---|---|---|
| POST | `/api/v1/auth/refresh` | 用 refresh token 換新的 access token 和新的 refresh token |
| POST | `/api/v1/auth/logout` | 撤銷 refresh token |

### 設計

| 項目 | 決定 | 原因 |
|---|---|---|
| 格式 | 隨機字串（opaque），不用 JWT | 必須能在伺服器端撤銷，本來就要查 DB |
| 存放 | `refresh_token` 表只存 **SHA-256 雜湊值**，搭配 `user_id`、`expires_at`、`revoked_at` | DB 外洩時無法直接拿來使用 |
| 有效時間 | 例如 14 天 | |
| 輪替 | 每次 refresh 都把舊的作廢、發新的 | 縮短被竊取後可使用的時間 |
| 重複使用偵測 | 已作廢的 token 再被使用時，撤銷該使用者所有 refresh token | 代表 token 可能被竊取 |

### 錯誤碼

| 錯誤碼 | 情境 |
|---|---|
| `REFRESH_TOKEN_INVALID` | 不存在、已過期或已撤銷 |

---

## 之後再處理

- Gateway 接上 JWKS 驗簽（`feature/api-gateway` 分支）
- 金鑰輪替：JWKS 同時發布新舊兩把公鑰，用 `kid` 區分
- 登入失敗次數限制（搭配 Redis）
