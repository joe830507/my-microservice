# user-service 實作步驟

> 建立日期：2026-11-09
> 分支：`feature/user-service`
> 對應：[microservice-architecture.md](microservice-architecture.md)「user-service」
> 狀態：Step 1～5 完成，Step 6 的 refresh 完成（2026-11-09），下一步 `/logout`

## 進度

| Step | 內容 | 狀態 |
|---|---|---|
| 1 | 建立專案 | 完成 |
| 2 | DB 與設定 | 完成 |
| 3 | 第一個 migration（使用者、角色） | 完成 |
| 4 | 註冊、登入（密碼雜湊、簽發 JWT） | 完成（待確認事項見下方） |
| 5 | JWKS endpoint、`/users/me`、後台使用者列表、dev 的 ADMIN 初始化 | 完成 |
| 6 | Refresh token（存放、輪替） | 進行中（refresh 完成，logout 未做） |

**第一個里程碑（Step 1～3）**：服務能連上自己的 DB 並啟動，Flyway 建好資料表。

**Step 4 完成內容**：`POST /api/v1/auth/register`、`POST /api/v1/auth/login`（簽發 RS256 access token），`JwtProperties` 與 `JwtConfig`；整合測試 `AuthControllerRegisterIntegrationTests`（15 個）、`AuthControllerLoginIntegrationTests`（16 個）全數通過。

**Step 4 待確認**：

- 併發註冊同一個 email 時，目前由 `uq_users_email` 擋下後回傳共用的 `DATA_CONFLICT`（409），還沒有轉成 `EMAIL_ALREADY_EXISTS`；與下方「需要注意」的寫法不一致，待決定要補轉換或接受現狀
- 4-4 第 6 步「金鑰路徑錯誤時啟動失敗、錯誤訊息看得出是哪個檔案」尚未實際驗證

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

### CommonEntity 與稽核（由 common 提供）

`CommonEntity`、`AuditorAware`、錯誤處理都已經在 `backend/common`，user-service **不再自己複製**。

| 項目 | 做法 |
|---|---|
| `User` | 繼承 `com.mymicroservice.common.entity.CommonEntity`（稽核欄位 + `@Version`） |
| `RefreshToken` | 只有 `created_at`，不繼承 `CommonEntity` |
| `User.id` | 和 product 一樣用 `@UuidGenerator(style = Style.VERSION_7)`，加上 `@Setter(AccessLevel.NONE)` |
| 稽核 | 自己寫 `JpaAuditingConfig`，只加 `@EnableJpaAuditing(auditorAwareRef = CommonAutoConfiguration.AUDITOR_AWARE_BEAN_NAME)`；`HeaderAuditorAware` 由 common 自動註冊 |
| 錯誤碼 | 寫 `UserErrorCode` enum 實作 common 的 `ErrorCode`；丟 `AppException`，`GlobalExceptionHandler` 由 common 自動註冊 |

`HeaderAuditorAware` 的規則：有合法的 `X-User-Id` 用它；沒有 header（註冊、登入、refresh）、不是合法 UUID、或不在 HTTP 請求中，記為 `SYSTEM_USER_ID`。自己註冊時建立者是系統，後台停用帳號時會記錄到 ADMIN 本人。

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

### 4-4 詳細步驟：JWT 簽發基礎設施

目標：啟動時讀進 RSA 金鑰，產生一個 `JwtEncoder` bean，給 4-5 登入時簽 token；同一把 `RSAKey` 之後在 Step 5 輸出 JWKS。

#### 1. 加依賴

`pom.xml` 加入 `org.springframework.security:spring-security-oauth2-jose`，版本由 Spring Boot parent 管理，不用寫。

- 它會一併帶進 Nimbus JOSE + JWT（`com.nimbusds.jose.*`）和 `spring-security-core`
- 不是 starter，不會啟用 filter chain，所以不會把 endpoint 鎖住

#### 2. 產生金鑰

在 Git Bash 執行，位置是 `backend/spring-user-service/secrets/`：

```bash
mkdir -p secrets && cd secrets
# 私鑰，PKCS#8 格式（檔頭是 -----BEGIN PRIVATE KEY-----）
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
# 從私鑰匯出公鑰，X.509 SubjectPublicKeyInfo 格式（檔頭是 -----BEGIN PUBLIC KEY-----）
openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem
```

- **先**在 `backend/spring-user-service/.gitignore` 加上 `secrets/`，再產生金鑰，避免不小心 commit 私鑰
- 用 `git status` 確認 `secrets/` 沒有出現在 untracked 清單裡
- 檔頭格式很重要：下一步的解析器只吃 PKCS#8 私鑰和 X.509 公鑰。如果私鑰檔頭是 `BEGIN RSA PRIVATE KEY`（PKCS#1），表示用錯指令

#### 3. 設定檔

`application.yml` 加入 `jwt` 區塊：

```yaml
jwt:
  private-key-location: ${JWT_PRIVATE_KEY_LOCATION:file:./secrets/jwt-private.pem}
  public-key-location: ${JWT_PUBLIC_KEY_LOCATION:file:./secrets/jwt-public.pem}
  key-id: user-service-2026-11
  issuer: user-service
  access-token-ttl: 15m
```

- 放的是金鑰的**路徑**，不是內容：PEM 有很多行，放進環境變數很麻煩；K8s 的 Secret 也是掛成檔案最自然
- `file:./...` 是相對於**執行時的工作目錄**。用 IDE 啟動時，確認 Run Configuration 的 working directory 是 `spring-user-service`，不然會找不到檔案
- K8s 部署時：Secret 掛到例如 `/etc/jwt/`，設定環境變數 `JWT_PRIVATE_KEY_LOCATION=file:/etc/jwt/jwt-private.pem`
- `key-id` 用固定字串就好，之後換金鑰時改成新的值（例如 `user-service-2027-05`）。也可以改用 `RSAKey#computeThumbprint()` 自動算，但先不用

#### 4. `JwtProperties`

位置：`com.mymicroservice.user.config.JwtProperties`

| 欄位 | 型別 | 說明 |
|---|---|---|
| `privateKeyLocation` | `org.springframework.core.io.Resource` | Spring 會自動把 `file:...`、`classpath:...` 字串轉成 `Resource` |
| `publicKeyLocation` | `Resource` | 同上 |
| `keyId` | `String` | |
| `issuer` | `String` | |
| `accessTokenTtl` | `java.time.Duration` | `15m` 這種寫法 Spring 會自動轉 |

- 用 `record` 寫最簡單，加上 `@ConfigurationProperties(prefix = "jwt")`
- 加 `@Validated`，欄位加 `@NotNull` / `@NotBlank`，設定漏了啟動時就會失敗，不用等到登入才發現
- 要讓 Spring 掃到它：在 `UserServiceApplication` 加 `@ConfigurationPropertiesScan`，或在設定類別上加 `@EnableConfigurationProperties(JwtProperties.class)`

#### 5. `JwtConfig`：組出 `RSAKey` 和 `JwtEncoder`

位置：`com.mymicroservice.user.config.JwtConfig`，一個 `@Configuration`，提供兩個 `@Bean`。

**Bean 1：`RSAKey`（Nimbus 的類別，`com.nimbusds.jose.jwk.RSAKey`）**

1. 解析 PEM：`org.springframework.security.converter.RsaKeyConverters`
   - `RsaKeyConverters.pkcs8()` → `Converter<InputStream, RSAPrivateKey>`
   - `RsaKeyConverters.x509()` → `Converter<InputStream, RSAPublicKey>`
   - 輸入用 `resource.getInputStream()`，記得用 try-with-resources 關閉
   - `getInputStream()` 會丟 `IOException`，想一下要往外丟還是包成 `IllegalStateException`（啟動失敗即可，兩種都行）
2. 組成 Nimbus 的 JWK：`new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(...).build()`
   - **一定要設 `keyID`**，簽出來的 JWT header 的 `kid` 和 JWKS 裡的 `kid` 都來自這裡

**Bean 2：`JwtEncoder`（`org.springframework.security.oauth2.jwt.JwtEncoder`）**

```
RSAKey  →  new JWKSet(rsaKey)  →  new ImmutableJWKSet<>(jwkSet)  →  new NimbusJwtEncoder(jwkSource)
```

- `ImmutableJWKSet` 是 `JWKSource<SecurityContext>` 的實作，`SecurityContext` 用 Nimbus 的 `com.nimbusds.jose.proc.SecurityContext`，**不是** Spring Security 那個，import 時注意
- 為什麼 `RSAKey` 要單獨做成 bean：Step 5 的 JWKS endpoint 會注入同一個 `RSAKey`，用 `new JWKSet(rsaKey).toJSONObject()` 輸出，預設只會輸出公鑰部分

#### 6. 驗證

這一步還沒有 API 可以打，先確認：

1. 服務能正常啟動
2. 故意把 `private-key-location` 改成不存在的路徑，啟動要**失敗**，而且錯誤訊息看得出是哪個檔案
3. （選做）寫一個 `@SpringBootTest`：注入 `JwtEncoder`，簽一個只有 `sub` 的 token，再用 `NimbusJwtDecoder.withPublicKey(publicKey).build()` 解回來，確認 `sub` 一樣

#### 7. 預告 4-5 會用到的 API

| 用途 | API |
|---|---|
| 組 claims | `JwtClaimsSet.builder()`：`issuer`、`subject`、`issuedAt`、`expiresAt`、`claim("roles", ...)` |
| 指定演算法 | `JwsHeader.with(SignatureAlgorithm.RS256).build()` |
| 簽發 | `jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue()` |

- header 不用自己設 `kid`，`NimbusJwtEncoder` 會從選到的 JWK 帶入。登入做好之後到 jwt.io 確認 header 裡有 `kid`
- `roles` 放字串陣列（例如 `["CUSTOMER"]`），不要直接放 enum 的 `Set`，避免序列化結果不如預期

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

### 完成內容

| 項目 | 說明 |
|---|---|
| `JwksController` | 和簽章共用同一個 `RSAKey` bean；帶 `use: sig`、`alg: RS256`；`Cache-Control: public, max-age=300` |
| `/users/me` | 停用的帳號仍回傳資料（`status: DISABLED`），擋下停用帳號是登入與 refresh 的責任 |
| `/users` | 篩選 keyword（email／displayName，不分大小寫，跳脫 LIKE 萬用字元）、status、role；排序欄位白名單 + `id` 當 tie-breaker；`User.roles` 用 `@BatchSize` 避免 N+1 |
| 測試 | `JwksControllerIntegrationTests`（4）、`UserControllerMeIntegrationTests`（11）、`UserControllerListIntegrationTests`（31） |

### 身分與權限：common 的 `@CurrentUserId`、`@RequireRoles`

原本寫在 `UserController` 裡的 header 解析搬到 common，之後 order-service 等服務直接沿用：

| 元件 | 作用 |
|---|---|
| `@CurrentUserId UUID userId` | 參數注入 `X-User-Id`；缺少或格式錯誤回 401 `UNAUTHENTICATED` |
| `@RequireRoles("ADMIN")` | 標在方法或類別上，擁有其中任一角色才放行；沒有身分 401、角色不符 403 `FORBIDDEN` |

- `@RequireRoles` 由 `HandlerInterceptor` 檢查，在參數綁定與 `@Valid` **之前**執行：非 ADMIN 送出不合法的參數會收到 403，不會先拿到 400
- 由 `CommonAutoConfiguration` 註冊 `UserContextWebMvcConfigurer`，服務不需要額外設定

### 列表查詢的索引（`V2__add_user_list_indexes.sql`）

| 索引 | 用途 |
|---|---|
| `pg_trgm` GIN：`lower(email)`、`lower(display_name)` | keyword 的 `LIKE '%...%'`；運算式要和查詢的 `lower(...)` 一致才會用到 |
| `(created_at, id)`、`(display_name, id)` | `ORDER BY 欄位, id LIMIT n` 直接依索引順序讀，不用整張表排序；email 排序用既有的唯一索引 |

- tie-breaker 的 `id` 改成和主要欄位**同方向**（原本固定 ASC）：`created_at DESC, id ASC` 只能對上索引的第一欄，還要再做一次 Incremental Sort；同方向就能直接反向掃描索引
- 用 50 萬筆資料 `EXPLAIN ANALYZE` 驗證：keyword 查詢走兩個 trigram 索引（BitmapOr），約 1.8 ms
- 資料量小（例如 5 萬筆以下）時 planner 仍會選全表掃描，這是成本估算的正常結果，不是索引沒生效
- keyword 少於 3 個字元時組不出 trigram，會退回掃描；後台列表可以接受，之後如有需要再限制最短長度

### dev 環境的第一個 ADMIN 帳號

決定：**只在 dev profile 執行的初始化程式**，不用 Flyway migration（避免密碼雜湊寫死在 SQL、每個環境都有同一個帳號）。

**已完成**：`bootstrap/BootstrapAdminInitializer`、`properties/BootstrapAdminProperties`、`application-dev.yml`；測試 `BootstrapAdminInitializerIntegrationTests`（4）、`BootstrapAdminNotDevIntegrationTests`（2）。

| 實際做法 | 說明 |
|---|---|
| 角色 | 只給 `ADMIN`，需要購物時另外註冊一般帳號 |
| `BootstrapAdminProperties` 也加 `@Profile("dev")` | `@ConfigurationPropertiesScan` 會掃到它；不限定 profile 的話，非 dev 環境缺設定會被 `@Validated` 擋下而無法啟動 |
| 密碼 | `BOOTSTRAP_ADMIN_PASSWORD` 環境變數，yml 不給預設值；沒設定時 dev 啟動失敗並指出 `app.bootstrap-admin.password` |
| 多個實例同時啟動 | `saveAndFlush` 撞到唯一約束時視為已建立，記 info 後略過 |
| `toString()` | 覆寫 record 預設的 `toString`，不把密碼印進日誌 |

**本機啟動**：VS Code 用 `.vscode/launch.json` 的「user-service (dev)」，已設定工作目錄、dev profile，並從 `backend/spring-user-service/secrets/dev.env`（gitignore）讀取 `BOOTSTRAP_ADMIN_EMAIL`、`BOOTSTRAP_ADMIN_PASSWORD`。命令列：

```bash
# 單獨跑模組時 common 是從 ~/.m2 取得，common 有改動要先 install（正在執行的服務會鎖住 jar，先停掉）
cd backend && ./mvnw -q -pl common install -DskipTests
cd spring-user-service
set -a; . secrets/dev.env; set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

以下是原本的設計：

| 項目 | 做法 |
|---|---|
| 觸發時機 | `ApplicationRunner`（或監聽 `ApplicationReadyEvent`），類別加 `@Profile("dev")` |
| 帳號來源 | 從設定讀 email、密碼、displayName，例如 `app.bootstrap-admin.*`，綁一個 `@ConfigurationProperties`；密碼用環境變數，不要寫進 yml |
| 冪等 | email 已存在就跳過，不更新密碼、不補角色；重啟多次結果都一樣 |
| 角色 | `ADMIN`（要不要同時給 `CUSTOMER` 自己決定） |
| 密碼 | 一樣經過 `PasswordEncoder` 雜湊 |
| 稽核 | 不在 HTTP 請求中，`HeaderAuditorAware` 會記成 `SYSTEM_USER_ID`，不用特別處理 |
| 日誌 | 建立時記一行 info（只記 email，不要記密碼）；跳過時記 debug |

- 本機啟動要加 `--spring.profiles.active=dev`（或在 `launch.json` 設定），否則不會執行
- 正式環境沒有 dev profile，第一個 ADMIN 之後另外處理（例如一次性的管理指令）
- 驗收：dev 啟動兩次，DB 只有一筆 ADMIN；用該帳號登入，JWT 的 `roles` 含 `ADMIN`；不加 dev profile 啟動不會建立

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

### 完成內容：`POST /api/v1/auth/refresh`

**已完成**：`V3__create_refresh_token_table.sql`、`RefreshToken`、`RefreshTokenRepository`、`RefreshTokenGenerator`、`AuthServiceImpl#refresh`；測試 `AuthControllerRefreshIntegrationTests`（16）。**`/logout` 尚未做。**

| 項目 | 實際做法 |
|---|---|
| 回應 | 登入與 refresh 共用 `TokenResDto`（原 `LoginResDto` 改名），新增 `refreshToken`、`refreshExpiresIn` |
| token | 32 bytes `SecureRandom`，base64url 不補 `=`（43 字元）；DB 存 SHA-256 hex（64 字元）。亂數夠長所以不需要 bcrypt，也因為要用雜湊值查詢所以不能加 salt |
| 有效時間 | `jwt.refresh-token-ttl: 14d`；每次輪替重新計算，持續使用就不會過期 |
| 多裝置 | 每次登入各發一組，互不影響 |
| 角色異動 | refresh 時從 DB 重新讀 roles，新的 access token 會反映 |

**refresh 的檢查順序**

1. 用雜湊值查不到 → `REFRESH_TOKEN_INVALID`（亂填、或使用者被刪除時已被 FK cascade 刪掉）
2. 已撤銷 → **重複使用**：撤銷該使用者所有 token，回 `REFRESH_TOKEN_INVALID`
3. 已過期 → `REFRESH_TOKEN_INVALID`，不撤銷其他 token（自然失效不是竊取）
4. 帳號停用 → 撤銷該使用者所有 token，回 `USER_DISABLED`
5. 條件式撤銷舊 token（`UPDATE ... WHERE revoked_at IS NULL`）；更新 0 筆代表被另一個請求搶先，同樣當作重複使用
6. 換發新的 access token 與 refresh token

**兩個容易出錯的地方**

- **例外會 rollback 撤銷**：第 2、4、5 步先撤銷再丟 `AppException`，預設會連撤銷一起 rollback。`refresh` 加了 `@Transactional(noRollbackFor = AppException.class)`；拿掉它，「重複使用」與「帳號停用」兩個測試會失敗
- **併發 refresh**：如果先查 `revoked_at` 再更新，兩個同時進來的請求都會通過檢查、各換出一組新 token。第 5 步用條件式 UPDATE 讓 DB 決定誰成功

---

## 之後再處理

- Gateway 接上 JWKS 驗簽（`feature/api-gateway` 分支）
- 金鑰輪替：JWKS 同時發布新舊兩把公鑰，用 `kid` 區分
- 登入失敗次數限制（搭配 Redis）
- `POST /api/v1/auth/logout`：撤銷 body 帶來的 refresh token；token 不存在或已撤銷也回成功（204），不洩漏狀態
- 清除過期與已撤銷的 refresh token：排程刪除 `expires_at` 已過一段時間的資料，避免表無限成長
- 重複使用的寬限期：前端多個分頁同時 refresh、或回應在網路上遺失後重送，都會被當成重複使用而登出。如果實際遇到，可以讓剛撤銷幾秒內的 token 再被使用時只回錯誤、不撤銷全部
