# 中介軟體導入計畫

> 建立日期：2026-11-11
> 分支：計畫在 `feature/middleware-roadmap`；每個階段實作時各自開分支（階段 C：`feature/event-driven-phase-c`）
> 對應：[product-service-middleware-evaluation.md](../../design/product-service-middleware-evaluation.md)「建議導入順序」、[microservice-architecture.md](../../architecture/microservice-architecture.md)「之後：事件驅動」
> 狀態：階段 A–D 完成；階段 E 的 E1–E6 完成，E7（真實信箱驗證）暫緩；階段 F 不實作。

從雛型的「同步 REST + 排程補償」走向「事件驅動 + 可觀測 + 可水平擴充」。每個階段都要能在本機 compose 與 kind 上跑起來並驗證，做法和之前一樣：每一步附測試，測試通過後單獨 commit。

---

## 導入前基準（歷史紀錄，2026-11-11）

| 服務              | PostgreSQL |    Redis    |        物件儲存        | 服務之間怎麼溝通              | 排程                       |
| ----------------- | :--------: | :----------: | :--------------------: | ----------------------------- | -------------------------- |
| api-gateway       |     —     |     限流     |           —           | 轉發；向 user-service 抓 JWKS | —                         |
| user-service      |     ✅     | 登入失敗計數 |           —           | —                            | 清除過期 refresh token     |
| product-service   |     ✅     |   商品快取   | 圖片（presigned 直傳） | —                            | —                         |
| inventory-service |     ✅     |      —      |           —           | 被 order 同步呼叫             | 預扣逾時釋放               |
| order-service     |     ✅     |      —      |           —           | 同步呼叫 product、inventory   | 付款逾時取消、補做庫存同步 |
| payment-service   |     ✅     |      —      |           —           | 同步呼叫 order                | 重試通知 order             |

問題：

- **服務之間全是同步呼叫**：下游一慢，上游跟著卡（建單最久約 2 × 5 秒）；失敗靠三支「補做」排程（`ReservationSyncJob`、`OrderNotificationRetryJob`、預扣逾時）
- **看不到跨服務的流程**：沒有指標、沒有追蹤、log 分散在各個 Pod
- **不能水平擴充**：K8s 全部 `replicas: 1`；多副本時 5 支 `@Scheduled` 會各跑一次
- **搜尋是 SQL `LIKE`**：中文不分詞、沒有相關性排序
- **沒有通知、付款是模擬的**（SEC-01 的 `mock-callback` 仍在）

## 已定的決定

| 項目     | 決定                                                    | 理由                                                                                                                        |
| -------- | ------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| 訊息佇列 | **Kafka**（KRaft 模式，不用 ZooKeeper）           | 事件可以重播、依 key 保證順序；之後 ES 同步、通知、快取失效都用同一條管道。評估文件的建議                                   |
| 事件發送 | **Transactional Outbox + 輪詢發送**（應用程式內） | 業務資料與事件在同一個 DB 交易寫入，不會「改了資料卻沒送出事件」；不必維運 Kafka Connect。Debezium（CDC）留作之後的替代方案 |
| 送達保證 | **至少一次（at-least-once）+ 消費端去重**         | 不追求 exactly-once；每個事件有 `eventId`，消費端記錄處理過的 id                                                          |
| 服務探索 | 維持 K8s 原生                                           | 已定案（2026-11-09）                                                                                                        |

## 階段總覽

| 階段        | 內容                                               | 前置              | 主要影響                                   |
| ----------- | -------------------------------------------------- | ----------------- | ------------------------------------------ |
| **A** | 可觀測性：指標、追蹤、集中 log                     | —                | 全部服務、infra                            |
| **B** | 韌性：Resilience4j、ShedLock、多副本               | A（才看得到效果） | order、payment、gateway、所有排程          |
| **C** | Kafka + Outbox：事件機制，取代同步通知與補做排程   | A、B              | payment、order、inventory、product、common |
| **D** | 搜尋：Elasticsearch                                | C                 | product（之後可拆 search-service）         |
| **E** | 通知：notification-service（寄信）                 | C                 | 新服務、user                               |
| **F** | 真的金流（第三方測試環境），移除 `mock-callback`（**不實作**，見階段 F） | C                 | payment                                    |

順序的理由：A、B 改動小、立刻降低之後的除錯成本；C 是改動最大的一步，要在看得到追蹤之後做；D、E 都建立在 C 的事件上（F 原本也是，但已決定不實作）。

每個階段的共同收尾（不再每階段重複列出）：

- `infra/docker-compose.yml`、`infra/k8s/` 加上新元件；埠一律綁 `127.0.0.1`；密碼放 Secret（`create-secrets.sh`）
- `network-policies.yaml` 與 `network-policies-egress.yaml` 同步開放，並用對照組實測（見 [backend-deployment](../backend-deployment-implementation-plan.md)）
- 更新 `docs/architecture/microservice-architecture.md`、`docs/README.md`、`docs/security/known-issues.md`（新元件的安全事項）
- 兩個前端的 E2E 在本機與 kind 上都要通過

---

## 階段 A：可觀測性（已完成，紀錄見 [observability-implementation-plan.md](observability-implementation-plan.md)）

目標：一張訂單從建單、預扣、付款到確認，可以用**一個 traceId** 在 Grafana 上看到完整的呼叫鏈、各段耗時與對應的 log。

| Step | 內容                                                                                                                                                                       | 驗收                                                                 |
| ---- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| A1   | 指標：各服務加 `micrometer-registry-prometheus`，actuator 開放 `prometheus`（只在叢集內可連，Gateway 已封鎖 `/actuator/*`）                                          | `GET /actuator/prometheus` 有 HTTP、JVM、HikariCP、Redis 指標      |
| A2   | 追蹤：加 OpenTelemetry（Micrometer Tracing 的 OTel bridge + OTLP exporter；Boot 4 有對應的 starter，實作時確認）；Gateway → 服務、RestClient 呼叫都要傳遞 `traceparent` | 一次建單在 Tempo 看到 gateway → order → product、inventory 的 span |
| A3   | 結構化 log：`logging.structured.format.console`（JSON），每行帶 `traceId`、`spanId`、服務名稱                                                                        | log 用 traceId 可以串起來                                            |
| A4   | 本機 infra：compose 加 `grafana/otel-lgtm`（OTel Collector + Prometheus + Tempo + Loki + Grafana 一個容器），服務用 OTLP 送出指標、追蹤、log                             | 本機 Grafana 看得到三種資料                                          |
| A5   | K8s：同一套放進 `infra/k8s/observability/`；egress 開放各服務 → collector 的 OTLP 埠                                                                                    | kind 上看得到資料；Grafana 只用 port-forward 連                      |
| A6   | 儀表板與告警規則：服務 RED（請求數、錯誤率、延遲）、建單成功率、預扣失敗數、排程執行結果；儀表板 JSON 放進 repo                                                            | 匯入就能用                                                           |

注意：

- 追蹤的取樣率：開發 100%，之後依環境調整
- 自訂業務指標（建單數、付款成功數）用 `MeterRegistry`，不要從 log 計算
- 不能把 token、密碼、Email 寫進 span 的屬性或 log

## 階段 B：韌性與水平擴充（已完成，紀錄見 [resilience-implementation-plan.md](resilience-implementation-plan.md)）

| Step | 內容                                                                                                                                           | 驗收                                                                                           |
| ---- | ---------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| B1   | order-service 呼叫 product、inventory 加 Resilience4j：逾時、熔斷；**只重試冪等的呼叫**（查商品、以 orderId 為冪等鍵的預扣、確認、釋放） | 停掉 inventory：建單在熔斷打開後立即回 503 `INVENTORY_SERVICE_UNAVAILABLE`，不再每次等滿逾時 |
| B2   | payment-service 呼叫 order 同樣處理                                                                                                            | 同上                                                                                           |
| B3   | Gateway 的路由加熔斷（Spring Cloud Gateway 的 CircuitBreaker filter）與一致的 503 回應                                                         | 下游掛掉時 Gateway 不會堆積連線                                                                |
| B4   | 排程加 **ShedLock**，鎖放在各服務自己的 PostgreSQL（JDBC provider；inventory、order、payment 沒有 Redis，不為了鎖多加依賴）              | 2 個副本時每支排程每輪只執行一次（看 log 與指標）                                              |
| B5   | K8s 調成 2 個副本，加 `PodDisruptionBudget`、`topologySpreadConstraints`；確認 Gateway 限流、登入鎖定都已經放在 Redis（多副本共用）        | 逐一刪除 Pod 時 E2E 仍然通過                                                                   |

注意：

- Resilience4j 與 Spring Boot 4 的相容版本要在實作時確認（或改用 Spring Cloud CircuitBreaker 的 Resilience4j 實作）
- 熔斷、重試的狀態要出現在 A 的指標裡，否則調參數只能用猜的
- 重試要有上限與退避，避免下游恢復時被重試流量打掛

## 階段 C：Kafka + Outbox（事件機制）（已完成，紀錄見 [event-implementation-plan.md](event-implementation-plan.md)）

目標：服務之間「通知對方發生了什麼」改成事件；**需要立即知道結果的仍然同步**（建單時查商品與預扣，因為要當場告訴使用者成功或失敗）。

### 事件設計

| Topic              | key       | 事件                                                             | 發送方  | 消費方                                           |
| ------------------ | --------- | ---------------------------------------------------------------- | ------- | ------------------------------------------------ |
| `payment.events` | orderId   | `PaymentSucceeded`、`PaymentFailed`                          | payment | order                                            |
| `order.events`   | orderId   | `OrderPaid`、`OrderCancelled`（階段 E 新增 `OrderCreated`、`OrderShipped`） | order   | inventory（確認／釋放預扣；階段 E 起忽略新增型別）、之後的 notification |
| `product.events` | productId | `ProductCreated`、`ProductUpdated`、`ProductStatusChanged` | product | 之後的搜尋索引、快取失效                         |
| `user.events`    | userId    | `UserRegistered`                                               | user    | 之後的 notification                              |

- 以聚合 id 當 key：同一張訂單的事件落在同一個 partition，順序有保證
- 事件格式（JSON）：`eventId`（UUIDv7）、`type`、`version`、`occurredAt`、`aggregateId`、`payload`；加欄位相容，改語意就升 `version`
- 本機與 kind 都是單一 broker；topic 先開 3 個 partition、保留 7 天

### 步驟

| Step | 內容 | 驗收 | 結果（2026-11-11） |
| ---- | ---- | ---- | ---- |
| C1 | infra：compose 與 K8s 加 Kafka（KRaft，單一節點，StatefulSet）；NetworkPolicy 只允許有用到的服務連 9092；topic 由設定檔建立 | kind 上沒有授權的 Pod 連不到 Kafka | 完成：kind 實測 |
| C2 | common：outbox 共用元件。各服務 DB 加 `outbox_event` 表（Flyway）；業務程式在同一個交易內寫入事件；發送器用 `SELECT ... FOR UPDATE SKIP LOCKED` 輪詢未發送的事件送到 Kafka，成功後標記（多副本不會重複搶同一筆）；清除已發送的舊資料 | 單元＋整合測試（Testcontainers 的 Kafka）：交易 rollback 時事件不會送出；Kafka 停掉時事件保留、恢復後送出 | 完成：整合測試。排序改用 DB 的 `sequence_id`，不依賴應用端時鐘 |
| C3 | common：消費端共用元件。`processed_event` 表（`eventId` 主鍵）與業務更新在同一個交易內寫入，重複的事件直接略過；失敗有限次重試後送到 dead letter topic（`<topic>.DLT`） | 同一個事件送兩次只處理一次；處理失敗的事件進 DLT，不會卡住整個 partition | 完成：真實 PostgreSQL 與 Kafka 測試。重試與 DLT 由 `DefaultErrorHandler` 處理，格式與業務規則錯誤不重試 |
| C4 | **付款成功改成事件**：payment 寫 `PaymentSucceeded` 到 outbox；order 消費後改為 PAID。移除 payment → order 的同步通知與 `OrderNotificationRetryJob` | 停掉 order 時付款仍然成功；order 恢復後自動變成 PAID；E2E 通過 | 完成：kind 停機恢復；舊路徑已移除（payment V6 migration 把未完成的舊通知轉成事件） |
| C5 | **庫存確認、釋放改成事件**：order 在 PAID、CANCELLED 時寫 `OrderPaid`、`OrderCancelled`；inventory 消費後確認或釋放。移除 order 的 `ReservationSyncJob` 與 `reservation_synced` 欄位的補做邏輯 | 停掉 inventory 時取消與付款仍然成功；恢復後預扣正確確認或釋放 | 完成：kind 停機恢復與庫存數量；舊路徑已移除（order V8 migration） |
| C6 | `product.events`、`user.events` 先開始發送（還沒有消費者），給 D、E 使用 | 事件出現在 topic 裡，格式符合規格 | 完成：契約整合測試與實際 topic 檢查 |
| C7 | 可觀測：Outbox relay 與 listener 明確傳遞 W3C context，trace 跨過 Kafka 不斷；指標加 consumer lag、outbox 待發送數量、DLT 數量 | 一張訂單的 trace 包含非同步的部分；lag 有告警 | 完成：Tempo 看到跨 Kafka 的 trace；lag 與 DLT 告警實測 firing |

驗收證據在 [evidence/phase-c/](evidence/phase-c)。

注意：

- **保留的同步呼叫**：建單時的查商品與預扣、payment 建立付款時查訂單金額。這些需要當下的答案
- inventory 的「預扣逾時釋放」排程保留：它是最後的安全網（訂單服務整個不見時，庫存也不會永遠被扣住）
- 事件規格寫進 `docs/architecture/`，消費者以它為準，不直接依賴發送方的 Java 類別
- Kafka 的認證（SASL/SCRAM）與 ACL：先靠 NetworkPolicy 隔離，已記到 known-issues 的 SEC-13，對外環境前要補
- 實作時的決定：新舊路徑曾以 `app.events.enabled` 旗標過渡，驗收通過後已全部移除；事件 payload 與版本規則見 [event-contracts.md](../../architecture/event-contracts.md)

## 階段 D：搜尋（Elasticsearch）（已完成；見 [計畫](search-implementation-plan.md)與[實作紀錄](search-implementation-record.md)）

目標：商店前台的關鍵字搜尋改用 Elasticsearch（中文斷詞、相關性排序）；ES 掛掉時退回 SQL。後台列表與沒有關鍵字的列表維持 SQL。

D1–D7 已完成。步驟與驗收要求見[搜尋計畫](search-implementation-plan.md#步驟)，實測結果與限制見[搜尋紀錄](search-implementation-record.md)，原始證據在 [phase-d](evidence/phase-d)。前端驗收為使用者人工操作，無截圖，並非自動化 E2E。

## 階段 E：通知（notification-service）

E1–E6 已實作並驗收；E7 真實信箱驗證暫緩，需使用者配合，**不是結案條件**。步驟、事件訂閱與驗收要求見[通知計畫](notification-implementation-plan.md#步驟)，結果與限制見[通知紀錄](notification-implementation-record.md)，原始證據在 [phase-e](evidence/phase-e)。

- 部署順序：inventory → order → notification；每階段前後檢查 DLT／dead-letter 告警。這是時間點檢查，不代表持續監測。
- 前端驗收採 Playwright 自動化，未另做人工點擊；SMTP 成功須由 Mailpit 實際收信確認。
- 事件去重避免重複入列；SMTP 寄送與資料庫提交並非原子操作，仍是至少一次送達，相同 `Message-ID` 不代表 exactly-once。
- 暫緩但後續要補：註冊確認信（SEC-04）、通知上線前的歷史收件人／訂單資料、真實 SMTP 認證與 TLS。

## 階段 F：真的金流（不實作）

> **決定：本階段不實作。** 本專案的重點是前面階段的技術應用（可觀測性、韌性、事件機制、搜尋、通知），串接真實金流商偏向第三方整合，不在學習範圍內。付款維持現有的 `mock-callback` 模擬流程，SEC-01 因此維持未結案，只適用於隔離的開發環境。下表保留原先的規劃作為參考，若將來改變決定可直接沿用。

| Step | 內容                                                                                                                                                        | 驗收                                         |
| ---- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| F1   | 選定第三方（例如綠界或 Stripe 的測試模式），評估 API、回呼方式與簽章                                                                                        | 寫進 `docs/design/`                        |
| F2   | payment-service 接第三方：建立付款取得付款頁或 client secret；前端導向或嵌入                                                                                | 用測試卡完成付款                             |
| F3   | 回呼（webhook）：Gateway 開放一條不需要 JWT 的路由，**由 payment-service 驗證簽章**；以第三方的交易編號去重；成功後寫 `PaymentSucceeded`（沿用 C4） | 偽造的回呼被拒絕；同一個回呼送兩次只處理一次 |
| F4   | 移除 `mock-callback` 與 `PAYMENT_MOCK_CALLBACK_ENABLED`，E2E 改用第三方的測試模式；SEC-01 結案                                                          | —                                           |

---

## 暫不排入（記錄理由）

| 項目                                         | 理由                                                             |
| -------------------------------------------- | ---------------------------------------------------------------- |
| 購物車放 Redis（跨裝置同步）                 | 目前購物車在前端 localStorage 就夠用；要做會員跨裝置購物車時再加 |
| 秒殺扣庫存（Redis Lua + MQ 削峰）            | 需要極高併發的場景才值得；目前庫存在 PostgreSQL 用條件式更新     |
| product 的本機快取（Caffeine）+ 事件廣播失效 | Redis 快取目前足夠；C 完成後如有需要再做                         |
| PgBouncer、讀寫分離                          | 擴充到很多副本、資料量大時才需要                                 |
| Service mesh、mTLS                           | 服務之間的身分目前靠 NetworkPolicy；長期再評估（見 SEC-02）      |
| Debezium（CDC）                              | Outbox 輪詢已足夠；事件量大或想讓應用程式不碰 Kafka 時再換       |

## 原待決事項與決議

- A：（已採用）本機與 K8s 使用同一個 `otel-lgtm` 映像，見[可觀測性紀錄](observability-implementation-plan.md)；原候選 kube-prometheus-stack 較貼近正式環境但較重，未採用。
- C：（已決定）事件格式用 JSON 加文件規格；Schema Registry（Avro／Protobuf）有需要再評估
- D：（已決定）Elasticsearch + 官方 analysis-smartcn；實驗與版本見搜尋實作紀錄
- F：（已決定不實作）原本待選的金流商不再需要選擇

## 審查追蹤

原始審查保留當時的問題與判斷，後續修正及驗證見各階段實作紀錄；不以刪除審查項目代表結案。

| 階段 | 審查 | 後續紀錄 |
|---|---|---|
| C | [階段 C 審查](event-implementation-plan.md#review-c) | [事件紀錄](event-implementation-plan.md) |
| D | [階段 D 審查](search-implementation-record.md#review-d) | [搜尋紀錄](search-implementation-record.md) |
| E | [階段 E 審查](notification-implementation-record.md#review-e) | [通知紀錄](notification-implementation-record.md) |
