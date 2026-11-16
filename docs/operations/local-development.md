# 本機開發與疑難排解

核對日期：2026-11-16。基本步驟（compose、密鑰、建置、前端）在[根目錄 README](../../README.md#本機執行)；這份文件補上那裡沒有寫的部分：notification-service 怎麼啟動、`local-backend.sh` 的行為，以及常見失敗與處理。

**驗證範圍**：下面的指令與設定值是對照 `local-backend.sh`、各服務 `application.yml`、`infra/docker-compose.yml` 整理的，**沒有實際啟動這些服務**（撰寫時 compose 基礎設施沒有在執行）。標明「已執行」的才是真的跑過。

## 啟動 notification-service

`infra/scripts/local-backend.sh` 只啟動 user、product、inventory、order、payment 與 gateway 六個程序，**不包含 notification-service**。要觀察寄信流程，另外啟動：

```bash
# 前提：compose 已啟動（需要 notification-db、kafka、mailpit），且已 ./mvnw -DskipTests package
cd backend/spring-notification-service
java -jar target/notification-service-0.0.1-SNAPSHOT.jar
```

它預設連到的位置（`application.yml`，皆可用環境變數覆蓋）：

| 依賴 | 預設 | 環境變數 | compose 服務 |
|---|---|---|---|
| PostgreSQL | `jdbc:postgresql://localhost:5437/notification_db`，帳密 `postgres`／`postgres` | `SPRING_DATASOURCE_URL`、`DB_USERNAME`、`DB_PASSWORD` | `notification-db` |
| Kafka | `localhost:9094` | `KAFKA_BOOTSTRAP_SERVERS` | `kafka`（topic 由 `kafka-topics-init` 建立） |
| SMTP | `localhost:1025` | `MAIL_HOST`、`MAIL_PORT` | `mailpit`（網頁介面 http://localhost:8025） |
| 寄件人 | `no-reply@my-microservice.local` | `NOTIFICATION_MAIL_FROM` | — |

- port 是 8086，只提供 actuator（`health`、`info`、`prometheus`），沒有業務 API。`Kafka admin fail-fast` 是開的：Kafka 沒起來時服務會啟動失敗，而不是無限重試。
- 消費者 `auto-offset-reset: earliest`：第一次啟動會從頭消費 topic 上留存的事件，寄信工作會一次建立。
- 要把指標、追蹤送到 Grafana，沿用與其他服務相同的做法：`OBSERVABILITY_EXPORT_ENABLED=true`、`OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318`（見 `local-backend.sh` 的 `OBSERVE=1` 區塊）。
- 信件從 Mailpit 的網頁介面查看，不會真的寄出。

## `local-backend.sh`

```bash
infra/scripts/local-backend.sh start     # 依序啟動 user、product、inventory、order、payment、gateway
infra/scripts/local-backend.sh stop
infra/scripts/local-backend.sh restart   # stop、等 3 秒、start
OBSERVE=1 infra/scripts/local-backend.sh start
```

| 項目 | 行為 |
|---|---|
| 前提 | 已執行 `./mvnw -DskipTests package`；compose 已啟動；`backend/spring-user-service/secrets/` 有金鑰與 `dev.env` |
| log | `$LOG_DIR`，預設 `backend/.local-logs/`，每個服務一個檔（`user.log`、`product.log`、`gateway.log` 等） |
| user-service | 先載入 `secrets/dev.env`，以 `--spring.profiles.active=dev` 啟動（會用 `dev.env` 的帳密建立第一個管理員） |
| payment-service | 以 `PAYMENT_MOCK_CALLBACK_ENABLED=true` 啟動，E2E 需要 `mock-callback`；這個開關預設是關閉的（SEC-01） |
| `OBSERVE=1` | 設定 `OBSERVABILITY_EXPORT_ENABLED=true` 與 `OTEL_EXPORTER_OTLP_ENDPOINT`（預設 `http://localhost:4318`） |
| `stop` | Windows 以 PowerShell 找出命令列符合 `(api-gateway\|-service)-0.0.1-SNAPSHOT.jar` 的 `java.exe` 並結束；其他系統用 `pkill -f`。這個樣式**也會比對到 notification-service 的 jar**，所以 `stop` 會一併停掉手動啟動的通知服務 |
| 啟動完成 | 約 1 分鐘後 `http://localhost:8080/actuator/health` 為 `UP` |

要不要讓腳本也啟動 notification-service，評估見文末。

## 疑難排解

每一列都有來源；沒有文件或程式依據的情況不收錄。

| 現象 | 原因 | 處理 | 來源 |
|---|---|---|---|
| user-service 啟動或測試報 `FileNotFoundException: secrets\jwt-public.pem` | `application.yml` 預設從 `./secrets/` 讀 JWT 金鑰，`secrets/` 不進版控 | 依根 README 步驟 2 產生金鑰與 `dev.env`；測試改用 `src/test/resources/jwt/`（`UserEventContractIntegrationTests` 目前漏了 test profile，見 CI 計畫 P1） | [根 README](../../README.md#本機執行)、[CI 計畫 P1](../implementation-plans/dev-workflow/ci-implementation-plan.md#p1-usereventcontractintegrationtests-依賴本機-secrets) |
| 後台 API 的 `@RequireRoles` 好像沒有作用，或行為跟原始碼不符 | 打包時沒加 `-am`，用到 `~/.m2` 裡過期的 `common` | 一律用 `./mvnw -pl <module> -am package`（或 `test`） | [架構參考：common](../architecture/microservice-architecture.md#common-放什麼) |
| Windows 上重新打包時 jar 被鎖住 | 服務還在執行，Windows 會鎖住 jar | 先 `infra/scripts/local-backend.sh stop` 再打包 | `local-backend.sh` 的 `stop` 註解 |
| 顧客結帳後呼叫 `mock-callback` 被拒絕 | 該開關預設關閉（SEC-01） | 用 `local-backend.sh` 啟動（已設為開啟），或手動啟動時加 `PAYMENT_MOCK_CALLBACK_ENABLED=true` | [SEC-01](../security/known-issues.md#sec-01-顧客可以自己把訂單標成付款成功)、`local-backend.sh` |
| `NotificationEmailIntegrationTests` 在 Windows Docker Desktop 失敗，SMTP 沒有回傳 greeting | Docker Desktop 的 SMTP host port mapping 問題 | 測試已改成在同一個 Testcontainers network 內執行服務與 Mailpit；失敗時先確認 Docker 狀態與該測試的現行寫法 | [通知實作紀錄](../implementation-plans/middleware/notification-implementation-record.md) |
| product 的搜尋整合測試很慢，或兩個測試互相干擾 | 測試即時建置 SmartCN 的 ES 映像，並且 `ProductIndexEventListenerIntegrationTests` 與 `ProductIndexRebuildIntegrationTests` 使用固定 host port 19200、19201 | 不要同時執行兩個會用到這些 port 的建置；確認 port 沒有被其他程式占用 | [搜尋實作紀錄](../implementation-plans/middleware/search-implementation-record.md)、[測試現況](../implementation-plans/dev-workflow/testing-overview.md#環境依賴ci-前要知道的) |
| 前端 `npm ci` 或指令因 Node 版本失敗 | `package.json` 的 engines 要求 `^22.18.0 \|\| >=24.12.0` | 切換到符合的 Node 版本 | [根 README](../../README.md#本機執行) |
| `test:e2e` 失敗 | 需要完整後端與管理員帳號 | 先用 `local-backend.sh start` 與 compose 備好環境，再依前後台紀錄的 Step 12 執行 | [商店前台紀錄](../implementation-plans/vue-customer/README.md#step-12)、[管理後台紀錄](../implementation-plans/vue-management/README.md#step-12) |

## 開發資料庫中的測試資料

先前的安全探測與 API 對接檢查在開發資料庫留下測試帳號與訂單（`sec-*`、`flood-*`、`api-*`），沒有清除。內容與處理條件見[安全清單：探測遺留的測試資料](../security/known-issues.md#探測遺留的測試資料)。要清除需要使用者確認清單，本文件不提供清除指令。

## 評估：讓 `local-backend.sh` 啟動 notification-service

**這次沒有修改腳本**，結論供使用者決定。

- 可行：腳本的 `stop` 樣式已涵蓋 notification 的 jar，`start` 只需要多一個 `( cd backend/spring-notification-service && nohup java -jar target/notification-service-0.0.1-SNAPSHOT.jar > "$LOG_DIR/notification.log" 2>&1 & )`，預設連線值與 compose 一致。
- 代價：notification 依賴 Kafka 與 Mailpit，且第一次啟動會從 topic 開頭消費；預設就多啟動，會讓只想跑前後台 E2E 的人多一個程序，也會在 Mailpit 累積信件。
- 建議：用環境變數控制，例如 `WITH_NOTIFICATION=1 infra/scripts/local-backend.sh start`，預設行為不變。
