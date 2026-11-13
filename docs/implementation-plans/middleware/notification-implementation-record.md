# Notification 實作紀錄

> 2026-11-13 文件整理：暫緩／後續項目的現況、前提與影響範圍統一見[集中清單](../deferred-items.md)；本處原始說明與歷史紀錄保留。

目前程式分工與依賴圖見[類別關係與職責](../../class-relationships/spring-notification-service.md)；本文件保留實作步驟、設計決策與當時驗收紀錄。

> [原始審查](#review-e)已合併於本文件末尾。審查內容保留當時的範圍、問題與建議；後續修正以本文件前段的實作與驗證紀錄為準，不以搬移代表問題結案。

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

依 [階段 E 原始審查](#review-e) 處理：

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

---

<a id="review-e"></a>

## 階段 E 實作審查與待修項目

> 審查日期：2026-11-12
> 對象：分支 `feature/notification-phase-e` 的 E1–E6（commit `e297c4d` 到 `be8ddaf`），對照 [notification-implementation-plan.md](notification-implementation-plan.md)
> 範圍：靜態閱讀（`EmailDispatcher`、`EmailMessageQueue`、三個 listener、`EmailMetrics`、`application.yml`、V1／V3 migration、範本、order-service 與 inventory-service 的 diff、實作紀錄），加上 kind 即時狀態（`notification_db` 內容）、Mailpit 實際收到的信、Tempo trace，以及 Docker Desktop 的 SMTP 對映埠探測。**尚未重跑 E 的測試與完整 reactor**，`NotificationEmailIntegrationTests` 的內容斷言（各範本的文字）也未逐項檢查
> 狀態：E6 複查完成。kind 部署與恢復驗收大致成立；剩下的 P1 是 offset 設定缺口與完整 reactor 無法全綠（見第 0、2 項）

<a id="review-e-整體評價"></a>

### 整體評價

方向與計畫一致，可保留：

- 發件匣設計正確：消費事件的交易只寫 `email_message`（`ON CONFLICT (event_id, template) DO NOTHING`），派送器用 `FOR UPDATE OF e SKIP LOCKED` 領取，收件人在派送時才解析（`order_ref` → `user_contact`）。
- 訂單事件契約：`OrderCreationPersistence` 獨立成一個 bean，讓 `@Transactional` 經過代理生效，`saveAndFlush` 與 outbox 寫入同一個交易；`shipOrder` 只在 `shipped` 為真時才發 `OrderShipped`；`OrderRepository` 的 `@Modifying` 補上 `clearAutomatically/flushAutomatically`。
- inventory 明確忽略 `OrderCreated`、`OrderShipped`，其他未知型別仍丟例外；有對應的 Kafka 整合測試。
- 失敗分類：永久性 SMTP 錯誤直接 `FAILED`，暫時性錯誤退避；收件人缺漏走獨立的 `SKIPPED` 路徑；`last_error` 只存例外類別名稱，不含地址或內容。
- 顧客名稱清除控制字元與多餘空白，主旨是固定文字；日誌沒有 Email。

以下問題請依優先順序處理。

<a id="review-e-待修項目"></a>

### 待修項目

<a id="review-e-p1可能讓計畫的保證失效"></a>

#### P1：可能讓計畫的保證失效

**0. 正式設定沒有 `auto-offset-reset: earliest`，新部署的 notification 會漏掉歷史 `UserRegistered`**
計畫的「部署順序」寫明 notification 要「從 earliest 開始消費，會處理保留期內的歷史事件」，但 `application.yml` 的 `spring.kafka.consumer` 沒有設定 `auto-offset-reset`，Kafka 預設是 `latest`。**只有測試**用 `spring.kafka.consumer.auto-offset-reset=earliest` 覆寫（`NotificationEmailIntegrationTests`、`UserEventIntegrationTests`），所以測試全過，正式環境卻是另一個行為。
- **實測證據（kind，2026-11-12，E6 複查時）**：`user_contact` 現有 7 筆，是 Codex 重建 user-service 後**新註冊**的使用者；`email_message` 中 3 組 `order-created`／`order-paid`／`order-shipped`（共 9 筆）是 `SKIPPED`、`unknown_user`，其餘 14 封 `SENT`，Mailpit 收到 14 封。被 `SKIPPED` 的是**部署前就存在的使用者**的訂單，正是這個設定缺口的表現（Codex 的紀錄把它歸因於「user-service 映像過舊、沒有發出註冊事件」，兩者可能同時存在，但即使 user-service 正常，歷史事件也不會被消費）。
- 這和階段 D 的教訓相同：測試用了與正式環境不同的設定，掩蓋了差異。
→ 在 `application.yml` 明確設定 `spring.kafka.consumer.auto-offset-reset: earliest`（或針對 notification 的三個 listener 設定），並說明為什麼：新服務上線要處理保留期內的歷史 `UserRegistered`。
→ 補一個**不覆寫** offset 設定的驗證（或在實作紀錄寫明 kind 上實測：重新部署後 `user_contact` 筆數 = 保留期內註冊的使用者數）。
→ 這是設定缺口，不是 E6 驗收的阻擋項（E6 已改用新註冊的使用者並收到信）；但上線順序與計畫不符，必須修正。

**1. （已撤回）固定 `Message-ID` 是否被覆蓋**
我先前的審查判斷 `Message-ID` 會被 `saveChanges()` 覆蓋，這個判斷**是錯的**。我用最小程式直接呼叫 `MimeMessage.saveChanges()` 確實會覆蓋，但 Spring 的 `JavaMailSenderImpl` 在 `saveChanges()` 之後會把原本的 `Message-ID` 重新設回去。實測（kind，Mailpit）：收到的信 `Message-ID` 與 `email_message.id` 一致（例如 `01a1ce8f-4dbe-743f-80eb-f5939f69d913@my-microservice.local`）。**不需要修改程式。**
→ 唯一建議：補一個測試，用 Mailpit API 斷言收到的 `Message-ID` 等於 `<{email_message.id}@my-microservice.local>`，把這個行為固定下來（目前測試沒有對它的斷言）。

**2. E6 複查：大致成立，但完整 reactor 無法全綠，需要決定處理方式**
我逐項對照計畫 E6 表格與 `evidence/phase-e/e6-kind-validation.txt`，並用 kind 即時狀態與 Tempo 查核：

| E6 項目 | 結果 |
| ---- | ---- |
| NetworkPolicy 對照 | ✅ 證據有；未授權 Pod 連不到 Mailpit:1025 與 notification-db |
| 部署順序 inventory → order → notification | ⚠️ 有依序部署的敘述，但**沒有「期間 DLT 告警沒有響」的證據** |
| 完整流程信件進 Mailpit | ✅ 我在 Mailpit 看到 14 封，主旨涵蓋成立、付款成功、出貨、取消、付款失敗；`Message-ID` 與 `email_message.id` 一致 |
| 付款失敗信 | ✅ |
| 停 SMTP、停 notification、停 order | ✅ 證據有（PENDING 後補寄、Kafka 積壓後消化、各補寄 3 封）。「不重複」的證據較弱，只寫「未觀察到重複」，建議用 `email_message` 與 Mailpit 的 `Message-ID` 逐筆對照 |
| 告警 firing 與 resolved | ✅ 兩條告警各 firing 一次且 resolved（比計畫要求更完整） |
| Tempo trace | ✅ **我補上了證據**：`evidence/phase-e/e6-tempo-trace.txt`，trace `4f07e937…` 貫穿 gateway → order-service → outbox → inventory／notification 消費。Codex 的紀錄寫「未能確認」，其實查得到（查詢條件用 notification-service 的 `order.events process` span） |
| 完整 reactor | ❌ 兩個 `NotificationEmailIntegrationTests` 的 SMTP 案例失敗 |
| 前端人工確認 | ⚠️ 以 Playwright E2E 取代（新增了 `notification-events.spec.ts`、`notification-order-shipment.spec.ts`），可接受，但紀錄要如實寫是自動化而非人工 |

**完整 reactor 的失敗我複查過，Codex 的判斷是對的，是環境問題，不是程式問題。** 我在本機啟動 `axllent/mailpit:v1.33.1`，從 host 連映射埠：TCP 可連、HTTP 埠有回應，但 SMTP 埠**沒有任何 220 招呼，連送 `EHLO` 後也沒有回應**；同一個容器從容器內部連 1025 立即收到 `220 … Mailpit ESMTP Service ready`。也就是 Docker Desktop／本機網路對 SMTP 流量的處理有問題（可能是防毒或代理的郵件掃描，或 Docker 的埠轉發），與程式無關；kind 內的寄信證明程式本身正確。
→ 這會讓「完整 `mvn -o clean test` 全模組成功」的結案條件在這台機器上無法達成。**請使用者決定**（見下方「需要你決定」）。

<a id="review-e-p2文件與一致性"></a>

#### P2：文件與一致性

**3. 實作紀錄有英文內容**
紀錄的狀態列（`Status: E1-E3 complete; ...`）與「E6: kind deployment and end-to-end validation」整節是英文，違反計畫「所有文件用繁體中文」的規則（階段 C 的 `event-contracts.md` 曾因此被退回）。
→ 改寫成繁體中文，狀態列比照階段 D 紀錄的格式。

**4. 儀表板的寄信耗時面板很可能和階段 D 一樣查錯指標名稱**
`generate_dashboard.py` 使用 `notification_email_send_duration_seconds_sum/_count`。階段 D 發現本專案的指標匯出帶單位字尾 `_milliseconds`（例如 `search_es_duration_milliseconds_*`、`http_server_requests_milliseconds_*`），用 `_seconds_` 會永遠沒有資料。目前叢集的 Prometheus 只有 `notification_emails_total`、`notification_email_pending`、`notification_email_oldest_pending_seconds`，**還沒有寄信耗時指標**，所以無法直接確認。
→ 寄出至少一封信後，列出 Prometheus 中 `notification_` 開頭的指標，確認實際名稱與單位再修正面板的查詢與 `unit`。
→ 每個新增面板都要在 Grafana 實際查一次，確認有資料；沒資料的要說明原因（例如計數器尚未累加）。

**5. 範本替換順序讓使用者輸入可再被替換**
`send` 先把 `{{name}}` 換成顧客名稱，**之後**才替換 `{{orderId}}`、`{{amount}}`、`{{reason}}`。顧客名稱若含有 `{{reason}}` 之類的字串，會在後續步驟被替換。目前被替換進去的都是該顧客自己的訂單資料，影響很小，但這是典型的範本注入形狀。
→ 改成最後才替換使用者可控的欄位（`{{name}}` 放最後），或一次性替換。補一個測試：名稱含 `{{amount}}` 時，內文逐字保留而不是被展開。

<a id="review-e-p3小項"></a>

#### P3：小項

**6. `EmailDispatcher` 的程式風格**
多處使用全名（`java.time.Duration`、`java.util.ArrayDeque`、`jakarta.mail.MessagingException`、`java.math.BigDecimal`），違反計畫「用 `import`，不要寫全名」；`EmailMessageQueue` 的 `java.math.BigDecimal` 同樣。`isPermanent` 以類別簡稱字串比對例外型別，建議改用 `instanceof` 與實際類別，字串比對在套件改名時會靜默失效。`EmailDispatcher` 太大（派送、重試、範本、寄送、追蹤混在一起），建議至少把範本載入與渲染拆出去，方便單獨測試。

**7. SMTP 寄送發生在資料庫交易內**
整批最長持有鎖的時間約 `batchSize × 讀寫逾時`（預設 10 × 5 秒）。這是計畫接受的簡化取捨（與 `OutboxRelay` 相同），但紀錄應寫明實際的上限數字與 SMTP 卡住時的行為。

<a id="review-e-另外階段-d-的一項待修與-e-無關修在-d-分支"></a>

### 另外：階段 D 的一項待修（與 E 無關，修在 D 分支）

這項是階段 D 重新檢視時發現的，**應該在 `feature/search-phase-d` 修**，不要混進 E 的 commit。

**D-1. D6 的「Elasticsearch 搜尋平均延遲」面板查詢錯誤的指標名稱**
`infra/observability/generate_dashboard.py`（約第 140 行）查 `search_es_duration_seconds_sum / _count`，實際匯出的是 `search_es_duration_milliseconds_*`，所以面板永遠沒有資料。用正確名稱查近 30 分鐘平均約 480 毫秒。
→ 改成 `_milliseconds_`，面板 `unit` 由 `s` 改成 `ms`；`search_es_duration_milliseconds_bucket` 實際存在，可改用 `histogram_quantile(0.95, ...)` 顯示 p95，並更正實作紀錄中「不使用目前未輸出的 histogram bucket」的敘述；改產生器後重新產生儀表板與告警，並在 Grafana 逐一確認 6 個搜尋面板都有資料。

<a id="review-e-已決定使用者2026-11-12"></a>

### 已決定（使用者，2026-11-12）

**兩個 SMTP 整合測試的處理方式：選項 1，改測試手法，不經過 host 映射埠，不新增依賴。**

背景：`NotificationEmailIntegrationTests` 有兩個案例在這台 Windows Docker Desktop 上失敗，原因是 host 映射埠上 SMTP 沒有任何回應（審查者實測：同一個 Mailpit 容器，從容器內連 1025 立即收到 `220` 招呼，從 host 映射埠連則 TCP 可連、HTTP 可通、SMTP 完全沒回應，連 `EHLO` 也沒有）。被測程式沒有問題（kind 內寄信已驗證）。

要求：

- **被測程式不修改**。`EmailDispatcher` 照常用 `JavaMailSender` 對 Mailpit 的 SMTP 寄信；只改測試如何抵達 Mailpit 的 SMTP。
- 讓 SMTP 流量不經過 Docker Desktop 的 host 埠轉發。可行做法（擇一，實作者自行評估後在紀錄寫明為何選它）：
  - 把待測的寄信動作放進**與 Mailpit 同一個 Testcontainers `Network` 的容器**執行（例如讓一個小容器從容器內對 `mailpit:1025` 寄信，再用 Mailpit 的 HTTP API 驗證）；
  - 或以 Mailpit 容器的 Docker 內部 IP（`getContainerInfo().getNetworkSettings()`）直連 1025，並確認這條路徑在 Windows 上可通；若不通就改用上一種。
- **不放寬斷言、不跳過、不加 `@Disabled`**。原本的斷言（信件真的出現在 Mailpit、收件人與主旨與內文要點正確、重複事件只寄一封、多個派送器不重複寄）必須全部保留。
- 若兩種做法都無法在 Windows Docker Desktop 上通過，**停下來回報**，不要自行改成選項 2 或 3；選項 2（GreenMail）需要走計畫變更。
- 順便補上 `Message-ID` 的斷言：Mailpit 讀回的信，`Message-ID` 等於 `<{email_message.id}@my-microservice.local>`。
- 通過後在 `evidence/phase-e/` 補完整 reactor 的結果（指令、耗時、模組數）。這是階段 E 結案前最後一個自動化條件。

<a id="review-e-第二輪複查commit-30d76db-之後2026-11-12"></a>

### 第二輪複查（commit `30d76db` 之後，2026-11-12）

**已修好（我實測確認）**：`auto-offset-reset: earliest` 已進 `application.yml`、測試不再覆寫；範本 `{{name}}` 改最後替換並有測試；`Message-ID` 有斷言；E6 補了 DLT 告警檢查、逐筆核對重複與 Tempo 證據。我重跑 inventory、order、notification 三個模組：BUILD SUCCESS，notification 10 項全過（含 5 項 `NotificationEmailIntegrationTests`，45 秒）。

**仍待處理**：

1. **寄信耗時面板仍查錯指標名稱（P2，已確認）**。`generate_dashboard.py` 第 159–160 行用 `notification_email_send_duration_seconds_sum/_count`，Prometheus 實際只有 `notification_email_send_duration_milliseconds_*`（含 `_bucket`），面板永遠沒有資料，`unit` 也要由 `s` 改 `ms`。與階段 D 的 D-1 是同一個錯，兩處一起修，並在 Grafana 逐一確認每個新增面板有資料。
2. **實作紀錄仍有英文段落（P2）**：E4、E5、E6 的敘述（約第 88–117 行與 E6 節）。
3. **SMTP 測試手法過重（P2，維護性）**：測試內啟動 `maven:3.9-eclipse-temurin-21` 容器，把整個 `backend` 以讀寫方式掛入、離線建置 jar、再以它當派送器，Kafka 位址靠 `host.docker.internal`。斷言都保留，但需要本機已有該映像、會在 repo 內留下 root 擁有的檔案、依賴 Windows Docker Desktop 特性。建議在紀錄的「已知限制」寫明；若之後要簡化，可改成一個預先建好的小型寄信映像。
4. **計畫文件被寫入實作結果（P3）**：計畫明訂「本文件是計畫，不要在裡面記實作結果」，但 `30d76db` 把計畫的 E6 表格改成打勾結果、驗收清單改為已勾選，並把第一項「各自單獨 commit，每個 commit 測試通過」弱化成「均有獨立實作紀錄與 commit」。結果應留在實作紀錄與 evidence；計畫的表格與清單請還原成原文（驗收清單可保留勾選，但條目不得改弱）。
5. **程式風格（P3）**：`EmailDispatcher`、`EmailMessageQueue` 仍有 `java.time.Duration`、`java.util.ArrayDeque`、`java.math.BigDecimal` 等全名；`isPermanent` 以類別簡稱字串比對。
6. **驗收清單尚有三項未勾**：既有測試未被放寬、文件同步（`microservice-architecture.md` 完全沒有 `OrderCreated`／`OrderShipped` 的描述）、紀錄最後的「最不確定的地方」。階段 E 在這三項完成前不應宣告結案。

<a id="review-e-處理結果使用者要求由審查者直接修正2026-11-12"></a>

### 處理結果（使用者要求由審查者直接修正，2026-11-12）

| 項目 | 處理 | 位置 |
| ---- | ---- | ---- |
| D-1 搜尋延遲面板指標名稱 | ✅ 改 `_milliseconds_`、單位 `ms`；Prometheus 實測平均約 65 毫秒。**p95 不加**：`search_es_duration_milliseconds_bucket` 實際只有 `+Inf` 一個桶，沒有可用的百分位數（先前審查誤以為有 bucket），紀錄已更正 | `feature/search-phase-d`（`e468f95`），已合併進 E 分支 |
| 第 0 項 offset | ✅ Codex 已修（`30d76db`） | |
| 第 1 項 `Message-ID` | ✅ 撤回；Codex 已補測試 | |
| 第 2 項 E6 / reactor | ✅ Codex 已完成，我重跑 inventory、order、notification 全綠 | |
| 第 3 項 紀錄英文 | ✅ E4、E5、E6 小標改繁體中文；新增「審查後修正」「已知限制」「最不確定的地方」三節；`microservice-architecture.md`、`event-contracts.md` 的英文段落一併改成繁體中文 | |
| 第 4 項 寄信耗時面板 | ✅ 改 `_milliseconds_` 與 `ms`，面板與告警標題改繁體中文；已套用到 kind。**注意**：套用時 Grafana 所在 Pod 重啟，指標歷史清空，寄信結果與耗時兩個面板要等有信件寄出才會出現資料；待寄筆數與最舊待寄秒數已確認有資料 | |
| 第 5 項 範本替換順序 | ✅ Codex 已修；我再把範本渲染抽成 `EmailTemplateRenderer` | |
| 第 6 項 程式風格 | ✅ 移除全名、`isPermanent` 改 `instanceof`。**例外**：SMTP 實作類別（`org.eclipse.angus.mail.smtp`）只在執行期 classpath，編譯期看不到，仍以類別名稱判斷並在程式註解說明，沒有為此新增依賴 | |
| 第 7 項 鎖持有時間 | ✅ 寫入紀錄的「已知限制」與「最不確定的地方」 | |
| 計畫文件被寫入結果 | ✅ 計畫還原成原文，只保留「計畫變更紀錄」（改為繁體中文並補上 E4／E5／E6 條目）；驗收清單維持原文與未勾選，完成狀況以實作紀錄為準 | |
| SMTP 測試手法過重 | ⚠️ 未改，寫進紀錄的已知限制：需本機有 `maven:3.9-eclipse-temurin-21`、以讀寫掛載 `backend/`。使用者決定的選項 1 本來就走這條路，要簡化需另做預先建好的寄信映像 | |

驗證：`mvn -o -pl spring-notification-service -am test` BUILD SUCCESS，notification 10 項全過（`NotificationEmailIntegrationTests` 5 項，因原始碼變更重建 jar 約 2 分鐘）。

<a id="review-e-修完後"></a>

### 修完後

- 重跑 E 相關測試與完整 reactor，紀錄寫入日期、指令與結果。
- E6 的每一項都有證據檔後，才把階段 E 標成完成；E7（真實信箱）仍是暫緩，不是結案條件。
