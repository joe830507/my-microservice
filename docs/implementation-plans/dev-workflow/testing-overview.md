# 測試策略與測試現況

核對日期：2026-11-16；環境：Windows 11、Docker 29.9.3、Maven Wrapper、Node 以 `package.json` 的 engines 為準。本文件是 [G-01 CI 計畫](ci-implementation-plan.md)的輸入：整理各模組有哪些測試、需要什麼外部資源、哪些依賴本機狀態。

**數字的來源要分清楚**：

- 「靜態 `@Test` 數」是用 grep 數 `@Test`、`@ParameterizedTest`、`@RepeatedTest` 註解的結果，**不等於實際執行的測試數**（參數化測試會展開）。
- 「本次實際執行」才是 2026-11-16 實際跑出來的結果；沒有跑的寫「未執行」。

## 分層與執行方式

測試分層與成本的概念說明見[系統導讀第 10 節](../../architecture/system-guide.md#測試分層與成本)，這裡不重複。

```bash
cd backend
./mvnw -pl spring-order-service -am test      # 單一模組；-am 一起建置 common，避免用到 ~/.m2 裡過期的版本
./mvnw clean test                              # 全部（需要 Docker）
./mvnw -o -pl spring-order-service -am test    # 離線；只適用於依賴與映像都已快取的環境

cd frontend/<app>
npx vitest run                                 # 單元測試（test:unit 是 watch 模式）
npm run test:browser                           # 免後端的瀏覽器測試（API 全部攔截）
npm run test:e2e                               # 需要完整後端與管理員帳號
```

## 後端

九個模組。整合測試以 Testcontainers 啟動真實資源，容器版本與 `infra/docker-compose.yml` 一致。

| 模組 | 靜態 `@Test` 數 | 本次實際執行 | 測試類型 | 外部資源 |
|---|---|---|---|---|
| `common` | 57 | **67 通過，51 秒**（`-pl common test`） | 單元測試為主，加一個 Outbox relay 整合測試 | `OutboxRelayIntegrationTests`：PostgreSQL、Kafka |
| `observability` | 0 | 沒有測試 | — | — |
| `spring-api-gateway` | 71 | 未執行 | `@SpringBootTest` 整合測試 10 類、單元 1 類 | WireMock 模擬下游；共用一個 Redis 容器（`TestRedis`） |
| `spring-user-service` | 139 | 未執行 | `@SpringBootTest`＋MockMvc 整合測試為主，少數單元 | PostgreSQL、Redis |
| `spring-product-service` | 129 | 未執行 | 整合測試為主，搜尋相關另有單元 | PostgreSQL、Redis、SeaweedFS（物件儲存）；搜尋測試另需 Elasticsearch（即時建置映像）與 Kafka |
| `spring-order-service` | 108 | 未執行 | 整合測試為主 | PostgreSQL；WireMock 模擬 product／inventory |
| `spring-inventory-service` | 73 | 未執行 | 整合測試為主 | PostgreSQL；`OrderEventKafkaIntegrationTests` 另需 Kafka |
| `spring-payment-service` | 47 | 未執行 | 整合測試為主，兩個 migration 測試 | PostgreSQL；WireMock 模擬 order |
| `spring-notification-service` | 12 | 未執行 | 兩個整合測試加一個單元 | PostgreSQL、Kafka、Mailpit；測試內還會啟動 Maven 容器建置服務 |

沒有任何測試被 `@Disabled` 或以條件跳過（掃描 `@Disabled`、`assumeTrue`、`@EnabledIf` 無結果）。

測試類別的完整清單可以用 `find backend/<module>/src/test -name '*.java'` 取得；下面只記錄對 CI 有影響的差異。

### 各模組備註

- **common**：除了 `OutboxRelayIntegrationTests`，其餘都不需要 Docker。
- **spring-api-gateway**：10 個整合測試類別共用 `TestRedis` 的單一容器，整個測試執行只啟動一次；`GatewayLoginRateLimitRedisDownIntegrationTests` 驗證 Redis 失效時的行為。`OpenTelemetryVersionAlignmentTest` 是單元測試，檢查依賴版本對齊。
- **spring-user-service**：整合測試類別都使用 `@ActiveProfiles("test")`（`BootstrapAdminInitializerIntegrationTests` 使用 `{"test","dev"}`），金鑰來自 `src/test/resources/jwt/`。**唯一沒有 test profile 的整合測試是 `UserEventContractIntegrationTests`**，見下方「環境依賴」。
- **spring-product-service**：圖片測試用 SeaweedFS 容器；`ObjectStorageTestSupport` 與 `TestcontainersConfiguration` 提供共用設定。`src/test/resources/application.properties` 關閉 Kafka listener 自動啟動、把 Outbox 輪詢拉長，讓測試自己控制時機。
- **spring-order-service／inventory／payment**：各有 `TestcontainersConfiguration`；order 與 payment 各有一個 migration 驗證測試（`RetireReservationSyncMigrationTests`、`RetireOrderNotificationMigrationTests`），直接使用 PostgreSQL 容器。
- **spring-notification-service**：測試數量少但成本最高，見下方。

### 環境依賴（CI 前要知道的）

| # | 測試 | 依賴 | 對 CI 的影響 | 已在 CI 計畫？ |
|---|---|---|---|---|
| 1 | `UserEventContractIntegrationTests` | 沒有 `@ActiveProfiles("test")`，讀 `application.yml` 預設的 `file:./secrets/jwt-*.pem`；`secrets/` 不進版控 | 乾淨環境 context 啟動失敗 | 是（P1） |
| 2 | `NotificationEmailIntegrationTests`（`findMavenDistribution()` 與建置容器，約第 371–456 行） | 以讀寫方式掛載 `~/.m2/repository`；使用 `~/.m2/wrapper/dists/apache-maven-3.10.0` 的 Maven（要先執行過 `./mvnw` 才存在），容器內以離線模式建置；容器以 root 執行 | 檔案權限與快取、順序問題 | 是（P3） |
| 3 | `ProductIndexEventListenerIntegrationTests`、`ProductIndexManagerIntegrationTests`、`ProductIndexRebuildIntegrationTests`、`ProductSearchIntegrationTests` | 以 `../../infra/elasticsearch/Dockerfile` 即時建置含 SmartCN 的 ES 映像（`ImageFromDockerfile`）；路徑相對於模組目錄，**必須從 `backend/<module>` 的工作目錄執行** | 每次 CI 重新建置映像，增加時間 | 部分（P4 只提到一個類別，實際是四個類別都會建置） |
| 4 | `ProductIndexEventListenerIntegrationTests`、`ProductIndexRebuildIntegrationTests` | 固定 host port：分別是 **19200** 與 **19201** | 同時執行兩個 CI 工作會衝突；runner 一次一個 job 時沒問題 | 部分（P4 說「固定 port」，沒有列出數值） |
| 5 | 各服務 `TestcontainersConfiguration`、`TestRedis`、`NotificationEmailIntegrationTests` | 需要可用的 Docker，並能下載 `postgres`、`redis:8.2`、`chrislusf/seaweedfs:3.80`、`axllent/mailpit:v1.33.1`、`maven:3.9-eclipse-temurin-21`、Kafka 與 ES 映像 | 第一次執行要下載映像；離線環境無法執行 | 否（`ubuntu-latest` 有 Docker，只需要知道映像清單） |

#### 掃描時新發現（不在 CI 計畫 P1～P4 內）

- **F1：兩個前端的免後端瀏覽器測試用系統 Chrome，不是 Playwright 內建 Chromium。** `playwright.optimization.config.ts` 設定 `channel: 'chrome'`。CI 計畫的步驟是 `npx playwright install --with-deps chromium`，兩者不一致。GitHub 的 `ubuntu-latest` 映像內建 Google Chrome，所以很可能仍能執行，但這是推測，**沒有驗證**。**處理（使用者決定 2026-11-16）**：維持 `channel: 'chrome'`，CI 依賴 runner 內建的 Chrome，已更新 [CI 計畫](ci-implementation-plan.md)。
- **F2：瀏覽器測試的 dev server 固定使用特定 port。** `test:browser` 使用 5184（vue-customer）與 5185（vue-management），`reuseExistingServer: true`；`test:e2e` 使用 5184（vue-customer）與 5183（vue-management）。兩個前端 job 各自在獨立 runner 上執行時沒有衝突，但同一台機器同時執行 vue-customer 的 `test:browser` 與 `test:e2e` 會共用 5184。

## 前端

兩個應用的 `package.json` 都要求 Node `^22.18.0 || >=24.12.0`。

| script | 做什麼 | 會修改檔案嗎 | 前置條件 |
|---|---|---|---|
| `test:unit` | `vitest`（watch 模式）；CI 要用 `vitest run` | 否 | 無 |
| `type-check` | `vue-tsc --build` | 否 | 無 |
| `build-only`／`build` | `vite build`；`build` 先跑 `type-check` | 否（輸出到 `dist/`） | 無 |
| `lint` | 依序跑 `lint:oxlint`（`oxlint . --fix`）與 `lint:eslint`（`eslint . --fix --cache`） | **會**（`--fix`） | 無 |
| `format` | `prettier --write --experimental-cli src/` | **會** | 無 |
| `test:browser` | Playwright，`testDir: ./browser-tests`，API 全部攔截 | 否 | 系統 Chrome；不需要後端 |
| `test:e2e` | Playwright，`testDir` 在 `e2e/`，真實後端 | 否 | 完整後端、管理員帳號；設定 `E2E_BASE_URL` 可指向已部署的前端 |

CI 計畫 P2 要新增的 `lint:check`、`format:check` 目前**還不存在**。

| 應用 | Vitest 檔案數 | 本次實際執行 | `browser-tests/` | `e2e/` |
|---|---|---|---|---|
| vue-customer | 30 | **30 檔、344 測試通過**，12.3 秒 | `cart-logout.spec.ts`、`optimization.spec.ts` | `browse`、`cart-edge`、`notification-events`、`orders`、`purchase-flow` |
| vue-management | 26 | **26 檔、267 測試通過**，21.6 秒 | `resize.spec.ts` | `auth`、`notification-order-shipment`、`product-flow` |

`test:browser`、`test:e2e`、`lint`、`type-check`、`build` 這次沒有執行。

## 這次沒有驗證的事

- 後端只執行了 `common`；其他八個模組的測試數是靜態計數，沒有實際跑過，也沒有在沒有 `secrets/` 的乾淨環境跑過。
- 「掃描時新發現」的 F1 是閱讀設定檔得到的推論。
- 映像清單只列出測試程式碼中看得到的名稱；PostgreSQL 與 Kafka 的版本寫在各 `TestcontainersConfiguration` 與 `docker-compose.yml`，沒有逐一核對。
