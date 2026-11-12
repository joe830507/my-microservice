# Notification 實作紀錄

> 更新日期：2026-11-12
> 狀態：E1–E6 完成；E7（真實信箱驗證）暫緩。完整 Maven reactor 與 kind 驗收證據見本紀錄及 `evidence/phase-e/`。
> 計畫：[notification-implementation-plan.md](notification-implementation-plan.md)

## E1：基礎設施與服務骨架

### 完成項目

- 新增 `spring-notification-service` Maven 模組（Spring Boot 4.1.1、Java 21），加入根 reactor 與 Docker 多模組建置。
- 建立 Flyway V1 業務表、V2 共用 Outbox relay 表；資料庫 schema 為 `notification_service`。
- 新增 notification-db、Mailpit compose/kind 資源、通知服務 Deployment/Service、PDB 與最小權限 NetworkPolicy。
- 新增 SEC-15，記錄 Mailpit 無認證/TLS 及 Email 個資保留政策限制。
- Docker image 建置成功；kind 中 notification-service 2/2 Ready、Mailpit 1/1 Ready。

### 驗證

- 離線 Maven：`mvn.cmd -o -Dmaven.repo.local=C:\Users\joe83\.m2\repository -pl spring-notification-service -am test`，BUILD SUCCESS；common 既有測試 52 項通過，notification-service 目前沒有自身測試。
- Docker：`docker build -f backend/Dockerfile --build-arg SERVICE=notification-service -t my-microservice/notification-service:0.1.0 backend`，成功。
- `kubectl kustomize infra/k8s` 成功；kind 內 Flyway 驗證 3 migrations、schema version 2。
- notification Pod TCP 探測：Mailpit 1025 與 Kafka 9092 open；Flyway 成功連線 notification-db 並套用 migration。
- 使用既有 `product-service` Pod 作未授權對照，連 `mailpit:1025` 與 `notification-db:5432` 皆連線逾時，符合 NetworkPolicy 預期。
- Compose config 驗證通過。
- 證據：[e1-kind.txt](evidence/phase-e/e1-kind.txt)。

### 計畫調整

部署時確認 `common` 的 `EventsAutoConfiguration` 會排程執行 `OutboxRelay`/`OutboxMetrics`，通知服務也需要 `outbox_event`。V1 已套用於本機 kind，不回頭修改已套用 migration，改以 V2 建立相容 schema。沒有變更 `common` 或事件契約。

Mailpit 在 `readOnlyRootFilesystem` 下需要可寫暫存目錄，kind manifest 以 `emptyDir` 掛載 `/tmp`；kind 從官方 registry 拉取 Mailpit v1.33.1，compose 維持官方版本標籤。

### 已知限制與待辦

- 尚未做真實事件觸發寄信、重試/退避、DLT、追蹤與完整端到端驗收（E2–E6）。
- 未建立 git commit；工作目錄原有其他未提交變更，未將其混入本階段。
- 真實信箱驗證屬 E7，暫緩至使用者配合。

## 最不確定、希望審查者重點看的地方

- `outbox_event` V2 是否完整符合 `common` relay 未來演進需要的欄位與索引。
- Mailpit 的暫存資料庫是 Pod ephemeral；Pod 重建會清空測試信件。
- kind 測得的 NetworkPolicy 行為受目前叢集 CNI 影響；已用 notification 與 product Pod 作正反對照。


## E2：收件人資料

### 實作

- `UserEventListener` 消費 `user.events`，只處理版本 1 的 `UserRegistered`。
- 驗證 eventId、type、version、occurredAt、aggregateId 和 payload 必要欄位；比對 payload.userId 與 aggregateId，錯誤資料不做寫入而送進 DLT。
- 通過 common `ProcessedEventHandler` 在同一 PostgreSQL transaction 中寫入 `processed_event` 並 upsert `user_contact`，保證事件重送不重複處理。

### 驗證

- 執行：`mvn.cmd -o -Dmaven.repo.local=C:\Users\joe83\.m2\repository -pl spring-notification-service -am test`。
- 結果：notification-service Kafka/PostgreSQL 整合測試 3 項通過（duplicate eventId、同 userId 新事件 upsert、缺欄位進 DLT）；common 模組 52 項通過；BUILD SUCCESS。
- 測試結果檔：[e2-user-events.txt](evidence/phase-e/e2-user-events.txt)。
- 未新增 topic；`user.events` 與 `user.events.DLT` 已由既有 Kafka topic init 建立。


## E3：訂單事件契約

### 實作

- inventory-service 在 `ProcessedEventHandler` 事務中記錄 `OrderCreated`/`OrderShipped` 後不做庫存操作；未知事件仍丟例外進 DLT。
- order-service 用 `OrderCreationPersistence` 包住新訂單寫入與 `OrderCreated` outbox append；payload 含 userId 與數值 totalAmount。庫存遠端呼叫保持在此交易邊界外。
- `shipOrder` 在狀態條件更新成功時在同一交易寫入 `OrderShipped`；已 SHIPPED 的冪等重請不重發，不能出貨則不發事件。
- 更新 event contracts 文件，記錄事件 payload、aggregate 與發送規則。

### 驗證

- `mvn.cmd -o -Dmaven.repo.local=C:\Users\joe83\.m2\repository -pl spring-order-service -am test`：order-service 及依賴模組 137 項測試全通過；common 52 項通過。
- `mvn.cmd -o -Dmaven.repo.local=C:\Users\joe83\.m2\repository -pl spring-inventory-service -am test`：inventory-service 及依賴模組 95 項測試全通過；common 52 項通過。
- 覆蓋：OrderCreated 正確 payload、Idempotency-Key 重放與並發只產一事件、庫存失敗不產事件、OrderShipped 成功/重放/409/並發、inventory 忽略兩種新事件並記錄去重、未知類型進 DLT。
- 事件契約與步驟證據已更新：[event-contracts.md](../../architecture/event-contracts.md)。

### 實作觀察

一開始為 `shipOrder` 加上交易後，測試發現條件式 bulk update 後仍讀到舊的 JPA managed entity。將 `markShippedIfPaid` 設為 flush 後清除 persistence context，使回讀使用資料庫新狀態，並保證並發出貨只產生單筆事件。


## E4：發件匣與郵件派送

### Implementation

- Added `OrderEventListener` and `PaymentEventListener`. `OrderCreated` atomically upserts `order_ref` and queues `order-created`; `OrderPaid`, `OrderShipped`, `OrderCancelled`, and `PaymentFailed` queue their matching templates. Known `PaymentSucceeded` is ignored. Business updates and `processed_event` use `ProcessedEventHandler` in the same transaction.
- Added idempotent email queue insertion backed by the existing `(event_id, template)` unique constraint. Added Flyway V3 to persist cancellation reason and render `timeout` as the Traditional Chinese timeout label.
- Added five UTF-8 plain text templates and scheduled dispatcher. Defaults are 5 seconds and 10 messages per batch. It claims eligible rows with `FOR UPDATE SKIP LOCKED`, resolves the contact, renders, sends SMTP, and updates status in one transaction. `Message-ID` is stable per email row. SMTP timeouts are 3 seconds to connect and 5 seconds for read/write. Missing contact/order references remain pending and are retried later.
- Customer names have control/format characters removed and are flattened to one line; missing names use the generic customer label. Subjects are fixed and do not include user input. Failed rows record only exception type, not address, name, or message body.

### Validation

- notification-service 與相依模組離線 Maven 編譯成功。
- Kafka/PostgreSQL/Mailpit 整合測試驗證事件處理及六筆唯一郵件；重複的 `OrderPaid` eventId 只建立一筆，`PaymentSucceeded` 不會建立郵件。
- Mailpit 實收測試在 Testcontainers network 內啟動目前原始碼建置的通知服務 runner，SMTP 走 `mailpit:1025`，HTTP API 由 host 查詢。所有範本、收件人、內文、去重、併發派送及 `Message-ID` 原始 header 斷言通過。kind 中 customer 下單、付款失敗與取消通知也已實收，資料列為 `SENT`。
- 測試位址使用保留網域 `example.com`；本紀錄不保存客戶姓名或信件內容。

### Follow-up and limitations

- E4 的 SMTP 可達性與雙 dispatcher `SKIP LOCKED` 併發測試已通過。SMTP 與資料庫提交不是原子操作：SMTP 接受郵件後、資料列標記 SENT 前若程序當機，仍可能重試；固定 `Message-ID` 可辨識此類重複。


## E5: Failure handling and observability

### Implementation

- Added configurable retry delays of 10s, 30s, 1m, 2m, 5m, 10m, 30m, and 1h. SMTP failures increment attempts and return to `PENDING`; the eighth failure becomes `FAILED`. Permanent authentication, message preparation/parsing, address rejection, and 5xx SMTP send failures become `FAILED` immediately. Each email is handled independently so a failure does not stop the rest of the batch.
- Missing order/contact references retry on the same schedule and become `SKIPPED` after five attempts, with `skip_reason` set to `unknown_order` or `unknown_user`.
- Added Micrometer counters (`notification.emails`, tagged by template/result), SMTP duration timer, pending count and oldest-pending gauges. Added Grafana dashboard panels and warning alerts for permanent email failures and pending queue age over 10 minutes.
- Updated the plan, middleware roadmap, architecture overview, event contract, docs index, and SEC-15 notes.

### Validation

- Added integration coverage for missing order, missing contact, retry progression, and terminal failure. Test outcomes are recorded in `evidence/phase-e/e5-notification-resilience.txt`.
- SMTP 寄送、Mailpit 停機恢復、FAILED 與 backlog 告警觸發／解除已驗證；告警查詢已聚合 replica 指標，避免重複告警。
- Dashboard generator compiles and regenerates the dashboard and alert rules.


## E6：kind 部署與端到端驗收

### Implementation and deployment

- 建置並部署 inventory、order、payment、notification 映像至既有 kind，依 inventory → order → notification 順序 rollout；四個 Deployment 均為 2/2 Ready。order/payment 套用 schema V8/V6，notification migration 全數驗證。
- `application.yml` 明確設定 Kafka consumer `auto-offset-reset: earliest`，讓新部署的 notification 可讀取 Kafka 保留期內既有 `UserRegistered`；Testcontainers 測試不再自行覆寫此值。
- Customer Playwright 購物流程通過 1/1：瀏覽、購物車、註冊、結帳、付款成功與管理端出貨均完成。管理端另有 UI 出貨測試 1/1；付款失敗與取消事件測試 1/1。Playwright 憑證由既有 bootstrap-admin Secret 提供，僅載入程序環境，未記錄或輸出。

### 驗收結果與限制

- Customer 購物流程寄出 `order-created`、`order-paid`、`order-shipped`，對應 `email_message` 均為 `SENT`。另一個 E2E 驗證 `payment-failed` 與 `order-cancelled`；管理端出貨 UI E2E 通過（1/1）。前端以 Playwright 自動化驗收，未另做人工點擊確認。
- 實際 SMTP 交付使用 Mailpit 與通知服務 runner 同一個 Testcontainers network，避免經過 Docker Desktop 的 SMTP host port mapping。runner 從當前原始碼建置通知服務 JAR，並在 network 內啟動；原有事件消費、範本、收件人、去重與併發派送斷言均保留。Mailpit API 逐筆確認 `Message-ID` 原始 header 與 `email_message.id` 相符，並確認沒有 Bcc 收件人。
- SMTP 停機／恢復時三筆郵件保持 `PENDING`，恢復後轉為 `SENT`；notification 停機／恢復後 Kafka backlog 歸零、三封郵件各送出一次；order-service 停機／恢復也已實測。
- NetworkPolicy 探測確認 notification-service 可連 Mailpit SMTP 與 notification-db，未授權 pod 無法連線。受控合成資料使 notification FAILED 與 backlog 告警各觸發一次；刪除合成資料後兩者均解除。
- Tempo trace `4f07e937dd6143327b6e36949257cce8` 已證明 gateway → order-service → outbox → Kafka → inventory／notification consumer 的同一 trace；詳見 `evidence/phase-e/e6-tempo-trace.txt`。排程寄信 span 為獨立 trace。
- 先前兩次完整 reactor 因 Windows Docker Desktop 的 SMTP host port mapping 無法回傳 greeting，在 `NotificationEmailIntegrationTests` 兩例失敗。依使用者選定的方案改為同 Testcontainers network 內執行被測通知服務，已通過完整 `NotificationEmailIntegrationTests` 類別及 Message-ID 斷言。
- 完整 `mvn -o clean test` 通過，10 個模組全綠，0 failures、0 errors、0 skipped，總耗時 13 分 34 秒；api-gateway 111 個測試通過。各模組耗時見 `evidence/phase-e/e6-kind-validation.txt`。
