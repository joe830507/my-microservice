# Notification 實作紀錄

> 更新日期：2026-11-12
> Status: E1-E3 complete; E4/E5 implemented and SMTP delivery verified in kind; E6 acceptance mostly complete with Maven/Testcontainers SMTP greeting and cross-service Tempo trace evidence still pending; E7 not started.
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


## E4: Outbox and email delivery

### Implementation

- Added `OrderEventListener` and `PaymentEventListener`. `OrderCreated` atomically upserts `order_ref` and queues `order-created`; `OrderPaid`, `OrderShipped`, `OrderCancelled`, and `PaymentFailed` queue their matching templates. Known `PaymentSucceeded` is ignored. Business updates and `processed_event` use `ProcessedEventHandler` in the same transaction.
- Added idempotent email queue insertion backed by the existing `(event_id, template)` unique constraint. Added Flyway V3 to persist cancellation reason and render `timeout` as the Traditional Chinese timeout label.
- Added five UTF-8 plain text templates and scheduled dispatcher. Defaults are 5 seconds and 10 messages per batch. It claims eligible rows with `FOR UPDATE SKIP LOCKED`, resolves the contact, renders, sends SMTP, and updates status in one transaction. `Message-ID` is stable per email row. SMTP timeouts are 3 seconds to connect and 5 seconds for read/write. Missing contact/order references remain pending and are retried later.
- Customer names have control/format characters removed and are flattened to one line; missing names use the generic customer label. Subjects are fixed and do not include user input. Failed rows record only exception type, not address, name, or message body.

### Validation

- Offline Maven compile of notification-service and dependencies: BUILD SUCCESS.
- Kafka/PostgreSQL/Mailpit Testcontainers integration test was added. It confirmed event processing and six unique queued emails, including a duplicate `OrderPaid` eventId producing one row; `PaymentSucceeded` was not recorded.
- SMTP delivery is verified in the kind cluster: customer purchase and payment failure/cancellation flows delivered messages to Mailpit and their database rows reached `SENT`. The separate Testcontainers SMTP test remains blocked on this Windows Docker Desktop host because the mapped SMTP port accepts TCP but the test JVM times out before the server greeting.
- Test inputs use reserved `example.com` addresses. This record contains no recipient address, customer name, or message content.

### Follow-up and limitations

- Resolve local Testcontainers SMTP reachability and rerun delivery assertions for all templates; add a concurrent two-dispatcher `SKIP LOCKED` test.
- SMTP retry backoff, maximum attempts, missing-recipient transition to SKIPPED, metrics, and alerts are E5. SMTP and database commits are not atomic; a process failure after SMTP accepts a message and before the SENT update can cause a retry. Stable `Message-ID` helps identify duplicates.


## E5: Failure handling and observability

### Implementation

- Added configurable retry delays of 10s, 30s, 1m, 2m, 5m, 10m, 30m, and 1h. SMTP failures increment attempts and return to `PENDING`; the eighth failure becomes `FAILED`. Permanent authentication, message preparation/parsing, address rejection, and 5xx SMTP send failures become `FAILED` immediately. Each email is handled independently so a failure does not stop the rest of the batch.
- Missing order/contact references retry on the same schedule and become `SKIPPED` after five attempts, with `skip_reason` set to `unknown_order` or `unknown_user`.
- Added Micrometer counters (`notification.emails`, tagged by template/result), SMTP duration timer, pending count and oldest-pending gauges. Added Grafana dashboard panels and warning alerts for permanent email failures and pending queue age over 10 minutes.
- Updated the plan, middleware roadmap, architecture overview, event contract, docs index, and SEC-15 notes.

### Validation

- Added integration coverage for missing order, missing contact, retry progression, and terminal failure. Test outcomes are recorded in `evidence/phase-e/e5-notification-resilience.txt`.
- SMTP success is verified in kind, including Mailpit outage/recovery. Local Testcontainers SMTP integration remains blocked by the mapped-port greeting timeout described under E4.
- Dashboard generator compiles and regenerates the dashboard and alert rules.


## E6: kind deployment and end-to-end validation

### Implementation and deployment

- Built and deployed the current inventory, order, payment, and notification images to the existing kind cluster in dependency order (inventory → order → notification); all four deployments reached 2/2 Ready. The refreshed order and payment images applied schema migrations V8 and V6; notification validated its four migrations.
- Ran the customer Playwright purchase flow against the kind-hosted frontend using the cluster's bootstrap-admin Secret: browse, cart, registration, checkout, payment success, and management shipment all passed (1/1). Credentials were loaded into process environment variables and were not recorded.

### Validation and limits

- Customer purchase flow delivered `order-created`, `order-paid`, and `order-shipped` to Mailpit; all corresponding `email_message` rows reached `SENT`. A separate E2E verified `payment-failed` and `order-cancelled`; the management UI shipment test passed (1/1).
- SMTP outage/recovery retained three rows as `PENDING` and delivered them after Mailpit returned; notification outage/recovery consumed the Kafka backlog and delivered the three queued messages once. Order-service outage/recovery was also exercised.
- NetworkPolicy probes confirmed notification-service can reach Mailpit SMTP and notification-db while an untrusted pod is blocked from both. The notification FAILED and backlog Grafana alerts each fired once under controlled synthetic conditions and resolved after the synthetic rows were removed.
- The generated Tempo data source returned notification dispatch spans, but a trace proving the complete outbox-to-Kafka-to-notification-consumer path was not isolated. This evidence remains pending.
- Two offline full-reactor runs (`mvn -o clean test`) stopped at two `NotificationEmailIntegrationTests`: on this Windows Docker Desktop setup, the mapped Mailpit SMTP port accepts TCP but its server-first greeting times out in the test JVM. The other modules before notification passed. `mvn -o -pl spring-api-gateway -am test` passed independently (111 gateway tests). This is an environment-level test blocker; the full reactor is not recorded as passing.
- See `evidence/phase-e/e6-kind-validation.txt` for commands and acceptance evidence. E6 remains open until the full reactor and cross-service trace evidence are complete.
