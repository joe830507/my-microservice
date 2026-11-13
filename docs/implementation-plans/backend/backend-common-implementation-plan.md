# backend 聚合 pom 與 common 模組實作步驟

目前程式分工與依賴圖見[類別關係與職責](../../class-relationships/common.md)；本文件保留實作步驟、設計決策與當時驗收紀錄。

> 建立日期：2026-11-09
> 分支：`feature/backend-common`（從 `feature/product-service-api` 建立）
> 對應：[microservice-architecture.md](../../architecture/microservice-architecture.md)「後端專案結構」
> 狀態：全部完成（2026-11-09），已合併進 main

## 進度

| Step | 內容 | 狀態 |
|---|---|---|
| 1 | 聚合 pom 與 Maven wrapper | 完成 |
| 2 | 建立 common 模組 | 完成 |
| 3 | 錯誤處理搬進 common | 完成 |
| 4 | 稽核與身分 header 搬進 common | 完成 |
| 5 | product-service 改用 common | 完成 |
| 6 | 合併回 user-service 分支 | 完成 |

**完成標準**：在 `backend/` 執行 `./mvnw verify` 全部通過，product-service 的既有測試不需要改行為就能通過。

---

## Step 1：聚合 pom 與 Maven wrapper

### `backend/pom.xml`

| 項目 | 值 |
|---|---|
| `groupId` / `artifactId` | `com.mymicroservice` / `backend` |
| `version` | `0.0.1-SNAPSHOT` |
| `packaging` | `pom` |
| parent | **不設**（只聚合、不繼承） |
| `<modules>` | `common`、`spring-product-service`（user-service 等合併後再加） |

- 不要放 `<dependencies>`、`<dependencyManagement>`：子模組沒有繼承它，放了也不會生效
- `<modules>` 的順序不影響建置順序，Maven 會依照依賴關係自動排序

### Maven wrapper

- 把 product 的 `mvnw`、`mvnw.cmd`、`.mvn/` 複製一份到 `backend/`，之後都在 `backend/` 執行建置
- 各服務原本的 wrapper 可以先留著，單獨開啟某個服務時仍然能用

### VS Code

- 匯入 `backend/pom.xml` 後，Java Projects 面板應該看到 `backend`、`common`、`product-service` 三個專案
- 如果還是各自分開，執行一次 `Java: Clean Java Language Server Workspace`

### 完成標準

在 `backend/` 執行 `./mvnw -q validate`，沒有錯誤（common 還沒建立前，先只列 product）。

---

## Step 2：建立 common 模組

### `backend/common/pom.xml`

| 項目 | 值 |
|---|---|
| parent | `spring-boot-starter-parent` 4.1.1（和服務一致，享有同一份版本管理） |
| `artifactId` | `common` |
| `packaging` | `jar`（預設） |
| base package | `com.mymicroservice.common` |

### 依賴

| 依賴 | scope | 原因 |
|---|---|---|
| `spring-boot-starter-webmvc` | 預設 | 錯誤處理、讀 header |
| `spring-boot-starter-data-jpa` | 預設 | `CommonEntity`、稽核 |
| `spring-boot-autoconfigure` | 由 starter 帶入 | `@AutoConfiguration` |
| `lombok` | `optional` | 和服務一致 |

### 和服務不同的地方

- **不要**加 `spring-boot-maven-plugin`：它會把 jar 重新打包成可執行 jar，服務就無法把它當函式庫使用
- 沒有 `main` 方法、沒有 `@SpringBootApplication`、沒有 `application.yml`

### Auto-configuration

- 建立 `CommonAutoConfiguration`，標上 `@AutoConfiguration`
- 在 `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 寫入它的完整類別名稱
- 服務只要加依賴就會自動載入，不需要改 `scanBasePackages`
- 提供的 bean 都加上 `@ConditionalOnMissingBean`，讓服務可以自行覆寫

### 完成標準

`./mvnw -q -pl common install` 成功。

---

## Step 3：錯誤處理搬進 common

### 類別

| 類別 | 說明 |
|---|---|
| `ErrorCode`（interface） | `HttpStatus status()`、`String message()`、`String name()` |
| `CommonErrorCode`（enum，實作 `ErrorCode`） | `VALIDATION_FAILED`、`VERSION_CONFLICT`、`DATA_CONFLICT`、`INTERNAL_ERROR` |
| `AppException` | 建構子改收 `ErrorCode` interface |
| `GlobalExceptionHandler` | 從 product 搬過來，`ErrorCode.X` 改為 `CommonErrorCode.X` |

### 設計重點

- enum 本來就有 `name()`，所以實作 interface 時不需要另外寫
- product 的 `ErrorCode` 用 Lombok `@Getter` 產生 `getStatus()`、`getMessage()`。interface 的方法名稱要和它對齊：可以宣告成 `getStatus()`、`getMessage()`，讓 product 的 enum 不用改就能實作；或是改用 record 風格的 `status()`，再請 product 一起調整。**選前者，改動最少**
- `GlobalExceptionHandler` 透過 auto-configuration 註冊為 bean，不要用 `@RestControllerAdvice` 搭配 component scan（掃描不到）。`@RestControllerAdvice` 的註解仍然要保留，Spring MVC 是靠它辨識的

---

## Step 4：稽核與身分 header 搬進 common

| 類別 | 說明 |
|---|---|
| `CommonEntity` | 從 product 搬過來，`version` 維持 `Long` |
| `UserContextHeaders` | 常數：`X-User-Id`、`X-User-Roles` |
| `HeaderAuditorAware` | 有合法的 `X-User-Id` 用它，否則用 `SYSTEM_USER_ID`（規則見 user-service 實作文件 Step 4） |
| `SYSTEM_USER_ID` | `new UUID(0L, 0L)`，定義在 `HeaderAuditorAware` |

### 注意：`@EnableJpaAuditing` 留在各服務

- 是否啟用稽核由服務自己明確宣告，比藏在 auto-configuration 裡更容易看懂，也和 product 現有的 `JpaAuditingConfig` 寫法一致
- common 只提供 `AuditorAware` bean（名稱固定，例如 `auditorAware`），服務自己寫 `@EnableJpaAuditing(auditorAwareRef = "auditorAware")`

---

## Step 5：product-service 改用 common

1. product 的 pom 加入 `com.mymicroservice:common:0.0.1-SNAPSHOT`
2. 刪除 product 的 `AppException`、`GlobalExceptionHandler`、`CommonEntity`
3. product 的 `ErrorCode` 改名為 `ProductErrorCode`，實作 common 的 `ErrorCode`，刪除通用的四個錯誤碼
4. `JpaAuditingConfig` 刪除自己的 `AuditorAware` bean，只保留 `@EnableJpaAuditing`
5. 全部 import 改為 common 的套件
6. 在 `backend/` 執行 `./mvnw verify`，**既有測試不改斷言也要通過**；錯誤回應格式不能有任何變化

---

## Step 6：合併回 user-service 分支

1. `feature/backend-common` commit、push
2. 切到 `feature/user-service`，`git rebase feature/backend-common`
3. 把 `spring-user-service` 加進 `backend/pom.xml` 的 `<modules>`
4. user-service 的 pom 加入 common，Step 4 照 common 的版本寫，不再複製 `CommonEntity`
5. 更新 user-service 實作文件 Step 4 的「CommonEntity 與稽核」段落
