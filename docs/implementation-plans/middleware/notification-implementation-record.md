# Notification 實作紀錄

> 更新日期：2026-11-12
> 狀態：E1–E3 完成；E4 實作完成、SMTP 整合驗收待排除本機 Testcontainers 連線問題；E5–E7 尚未開始。
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
- SMTP delivery validation is pending. Mailpit logs show SMTP listening on `0.0.0.0:1025` and its HTTP API is reachable, but the test JVM times out reading SMTP responses through the mapped port. A separate temporary Mailpit container published on local port 1025 also accepted TCP but returned no SMTP greeting. No messages appeared in Mailpit. This is not a passing delivery test.
- Test inputs use reserved `example.com` addresses. This record contains no recipient address, customer name, or message content.

### Follow-up and limitations

- Resolve local Testcontainers SMTP reachability and rerun delivery assertions for all templates; add a concurrent two-dispatcher `SKIP LOCKED` test.
- SMTP retry backoff, maximum attempts, missing-recipient transition to SKIPPED, metrics, and alerts are E5. SMTP and database commits are not atomic; a process failure after SMTP accepts a message and before the SENT update can cause a retry. Stable `Message-ID` helps identify duplicates.
