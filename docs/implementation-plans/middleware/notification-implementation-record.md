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

### 實作

- 新增 `OrderEventListener`、`PaymentEventListener`。`OrderCreated` 在同一交易內 upsert `order_ref` 並排入 `order-created`；`OrderPaid`、`OrderShipped`、`OrderCancelled`、`PaymentFailed` 各自排入對應範本。已知但不需要的 `PaymentSucceeded` 明確忽略。業務寫入與 `processed_event` 由 `ProcessedEventHandler` 放在同一個交易。
- 新增冪等的佇列寫入，依靠既有的 `(event_id, template)` 唯一約束。新增 Flyway V3 保存取消原因，`timeout` 渲染為「逾時未付款」。
- 新增五個 UTF-8 純文字範本與排程派送器。預設每 5 秒一輪、每批 10 筆；以 `FOR UPDATE SKIP LOCKED` 領取到期資料，在同一交易內解析收件人、渲染、SMTP 寄出並更新狀態。`Message-ID` 對每筆信件固定。SMTP 連線逾時 3 秒、讀寫逾時 5 秒。找不到收件人或訂單參照時維持 `PENDING`，稍後重試。
- 顧客名稱會移除控制與格式字元並壓成單行，沒有名稱時用通用稱呼；主旨固定、不含使用者輸入；失敗資料列只記錄例外類別，不記錄地址、名稱或內文。
- 審查後（2026-11-12）：範本渲染抽出為 `EmailTemplateRenderer`，使用者可控的 `{{name}}` 最後才取代，避免名稱內的 `{{...}}` 被再展開（測試以名稱含 `{{amount}}` 驗證）；`EmailDispatcher.isPermanent` 的例外判斷改用 `instanceof`（SMTP 實作類別僅在執行期 classpath，仍以名稱判斷，原因寫在程式註解）。

### 驗證

- notification-service 與相依模組離線 Maven 編譯成功。
- Kafka／PostgreSQL／Mailpit 整合測試驗證事件處理及六筆唯一郵件；重複的 `OrderPaid` eventId 只建立一筆，`PaymentSucceeded` 不建立郵件。
- Mailpit 實收測試在 Testcontainers network 內啟動以目前原始碼建置的通知服務 runner，SMTP 走 `mailpit:1025`，HTTP API 由 host 查詢。所有範本、收件人、內文、去重、併發派送及 `Message-ID` 原始標頭斷言通過。kind 中 customer 下單、付款失敗與取消通知也已實收，資料列為 `SENT`。
- 測試位址使用保留網域 `example.com`；本紀錄不保存客戶姓名或信件內容。

### 後續與限制

- SMTP 可達性與雙派送器 `SKIP LOCKED` 併發測試已通過。SMTP 與資料庫提交不是原子操作：SMTP 接受郵件後、資料列標記 `SENT` 前若程序當機，仍可能重試；固定的 `Message-ID` 讓郵件客戶端可辨識此類重複（已在 Mailpit 實測：收到的 `Message-ID` 與 `email_message.id` 一致，Spring 的 `JavaMailSenderImpl` 會在 `saveChanges` 後還原設定的值）。


## E5：失敗處理與可觀測性

### 實作

- 新增可設定的重試間隔：10 秒、30 秒、1 分、2 分、5 分、10 分、30 分、1 小時。SMTP 暫時性失敗會增加 `attempts` 並回到 `PENDING`，第八次失敗轉為 `FAILED`。認證失敗、訊息準備／解析失敗、收件地址被拒、SMTP 5xx 屬永久性錯誤，立即轉 `FAILED`。每封信獨立處理，單封失敗不會中斷整批。
- 找不到訂單或收件人資料時依同一間隔重試，第五次後轉 `SKIPPED`，`skip_reason` 為 `unknown_order` 或 `unknown_user`。
- 新增 Micrometer 指標：`notification.emails`（依範本與結果）、SMTP 耗時 Timer、待寄筆數與最舊待寄秒數 Gauge。新增 Grafana 面板，以及「寄送失敗」「信件積壓超過 10 分鐘」兩條警告告警。
- 同步更新計畫、roadmap、架構概觀、事件契約、文件索引與 SEC-15。
- 審查後（2026-11-12）：寄信耗時面板原本查詢 `notification_email_send_duration_seconds_*`，實際匯出名稱為 `notification_email_send_duration_milliseconds_*`（毫秒），已修正查詢與單位（`ms`）；面板與告警標題改為繁體中文。

### 驗證

- 整合測試涵蓋找不到訂單、找不到收件人、重試進度與終態失敗；結果見 `evidence/phase-e/e5-notification-resilience.txt`。
- SMTP 寄送、Mailpit 停機恢復、`FAILED` 與積壓告警的觸發及解除已驗證；告警查詢已聚合副本指標，避免重複告警。
- 儀表板產生器可編譯並重新產生儀表板與告警規則。重新套用到 kind 後，待寄筆數、最舊待寄秒數兩個面板有資料；寄信結果與耗時面板的計數器要等有信件寄出才會出現（套用時 Grafana 所在 Pod 重啟，既有指標歷史已清空）。


## E6：kind 部署與端到端驗收

### 實作與部署

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

## 審查後修正（2026-11-12）

依 [phase-e-review.md](phase-e-review.md) 處理：

- **Kafka offset**：`auto-offset-reset: earliest` 進正式設定（E6 已做）。
- **範本替換順序**：`{{name}}` 最後取代並有測試（見 E4）。
- **儀表板指標名稱**：寄信耗時改用 `_milliseconds_`（見 E5）；階段 D 的搜尋延遲面板同樣的問題另在 `feature/search-phase-d` 修正。
- **程式風格**：移除 `EmailDispatcher`、`EmailMessageQueue` 的全名引用，範本渲染拆為 `EmailTemplateRenderer`，`isPermanent` 改用 `instanceof`。
- **文件**：本紀錄改為繁體中文；計畫文件還原成原文，只保留「計畫變更紀錄」，實作結果留在本紀錄。

## 已知限制

- **至少一次送達**：派送器寄出後、更新狀態前當機會重寄一封，`Message-ID` 相同。
- **歷史資料暫不補寄**：notification 上線前已存在、且超過 Kafka 保留期的使用者沒有 `user_contact`；上線前建立的訂單沒有 `order_ref`，相關信件會 `SKIPPED`（`unknown_user`／`unknown_order`）。補資料需要 user-service、order-service 新增內部匯出 API，屬暫緩項目。
- **個資沒有保留期限**：`user_contact`、`email_message` 保存 Email 與寄信紀錄，沒有清理機制（SEC-15）。
- **SMTP 無認證與 TLS**：僅適用隔離的開發環境（SEC-15）。
- **SMTP 整合測試手法較重**：因 Windows Docker Desktop 的 SMTP host 埠轉發不回應，`NotificationEmailIntegrationTests` 在 Mailpit 同一個 Testcontainers network 內執行以目前原始碼建置的通知服務。它需要本機已有 `maven:3.9-eclipse-temurin-21` 映像，以讀寫方式掛載 `backend/`（會在 repo 內留下容器使用者擁有的 `target/` 檔案）並依賴 `host.docker.internal`。斷言都保留，但維護成本高；若之後要簡化，可改用預先建好的小型寄信映像。
- **派送期間持有資料列鎖**：與 `OutboxRelay` 相同的簡化取捨。單次最長約為批次大小 × SMTP 讀寫逾時（預設 10 × 5 秒）。
- **單一副本的寄信耗時等指標在重啟後歸零**：Grafana 所在 Pod 為 ephemeral。
- **註冊確認信（SEC-04 的 Email 列舉）與真實 SMTP**：暫緩，雛型後要補；真實信箱驗證為 E7，待使用者配合。

## 最不確定、希望審查者重點看的地方

- 派送器在同一個交易內做 SMTP 寄送，資料列鎖持有時間受 SMTP 逾時影響；目前是低流量雛型的取捨，流量變大時需要改成「先領取（租約）、交易外寄送」。
- SMTP 整合測試改在容器內執行通知服務（見上方限制），是否接受這個維護成本，或改成更輕的寄信映像。
- `isPermanent` 對 SMTP 類別以名稱判斷（編譯期看不到實作類別）；SMTP 實作套件改名時會靜默失效，已有單元測試覆蓋 550／451 的分類。
- `notification-service` 以 `earliest` 啟動時會處理 Kafka 保留期內的所有歷史訂單事件，對舊訂單會產生 `SKIPPED` 資料列；量大時這些資料列的清理尚未處理。
- 寄信耗時、結果等面板的資料要等有信件寄出才會出現；套用儀表板時 Pod 重啟會清空指標歷史。
