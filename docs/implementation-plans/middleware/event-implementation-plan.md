# 階段 C 事件實作與驗收紀錄

> [原始審查](#review-c)已合併於本文件末尾。審查內容保留當時的範圍、問題與建議；後續修正以本文件前段的實作與驗證紀錄為準，不以搬移代表問題結案。

## 決策

採用 Kafka、Transactional Outbox 與資料庫消費去重。重試策略、交易等待取捨、過渡路徑移除條件見[事件契約](../../architecture/event-contracts.md)。

## 進度

| 步驟 | 實作與驗收 |
|---|---|
| C1 | Compose、K8s、topic 與網路政策已建立；kind 未授權 Pod 無法連 Kafka 的實測通過 |
| C2 | 真實 mapper、rollback、broker 故障恢復與 DB sequence_id 排序測試通過 |
| C3 | 真實 PostgreSQL 並行去重、失敗 rollback、Kafka DLT 與後續訊息處理通過 |
| C4 | 事件整合測試與 kind 停 order 後付款成功、恢復後自動 PAID 驗收通過 |
| C5 | 事件整合測試與 kind 停 inventory、恢復確認／釋放及庫存數量驗收通過 |
| C6 | 商品與使用者契約整合測試及實際 Kafka topic 驗收通過 |
| C7 | Tempo 跨 Kafka trace、lag 與 DLT 告警實際 firing 驗收通過 |

2026-11-11 19:21，完整 backend reactor 執行離線 mvn test，全九模組 SUCCESS（5 分 58 秒）；含 Kafka、PostgreSQL Testcontainers。商品契約擴充涵蓋更新與封存後，另行定向測試也通過。

測試過程修復舊資料表列表斷言、Kafka starter 缺漏、metrics bean 重名，以及舊同步回退的重送補償行為。（此為第一次實作時的紀錄；部署驗收見下方 R1–R3、R4 章節。）

## 結案狀態

部署驗收（R3）、過渡路徑移除（R4）、commit 拆分（R5）皆已完成。原有 `.vscode/launch.json` 不屬本階段，未提交。

## 已知限制

- `payment` V6、`order` V8 migration 使用 PostgreSQL 18 才有的 `uuidv7()`，並直接寫死 schema 名稱（`payment_service`、`order_service`）。這兩份已部署過，為保留 Flyway checksum 不修改；本專案固定使用 `postgres:18.6`，更換 PostgreSQL 版本前需先確認。
- 證據檔 `evidence/phase-c/*.log` 已由 PowerShell 的 UTF-16 輸出轉成 UTF-8，方便在 diff 中閱讀。

## 2026-11-11 R1–R3 重新驗證

執行 `backend/mvnw.cmd -o clean test`，19:41:21 結束，完整九模組 SUCCESS，耗時 6:59，包含真實 Kafka、PostgreSQL Testcontainers。這是本次新執行的驗證；本機 clean build 不等同隔離 CI 或不同人員簽核。[reactor 結果](evidence/phase-c/reactor-summary.log)。

部署：`docker build -f backend/Dockerfile --build-arg SERVICE=<service> -t my-microservice/<service>:phase-c backend`、`kind load docker-image --name my-microservice ...`、`kubectl --context kind-my-microservice apply -k infra/k8s`，再 `kubectl set image` 更新五個事件服務；後續修正版採獨立映像標籤。

| 驗收 | 實測結果 |
|---|---|
| C1 | Compose、K8s、topic 與網路政策已建立；kind 未授權 Pod 無法連 Kafka 的實測通過 |
| C4 | 事件整合測試與 kind 停 order 後付款成功、恢復後自動 PAID 驗收通過 |
| C5 | 事件整合測試與 kind 停 inventory、恢復確認／釋放及庫存數量驗收通過 |
| C6 | 商品與使用者契約整合測試及實際 Kafka topic 驗收通過 |
| C7 trace | Tempo `/api/traces/fe50ccdfc71446a6a7d10c910293ffa5` 包含 order HTTP、outbox publish、inventory order.events process；訂單01a1ca77-250a-704f-a027-9d3a811cf854 |
| C7 告警 | 真實事件及暫時 consumer quota 造成 lag>1000 持續五分鐘，19:54 firing；格式錯誤訊息送 DLT，19:53 DLT firing；兩者 health=ok。驗收後移除 quota 與暫時 fetch 設定 |

停機操作見 [驗收腳本](../../../infra/k8s/scripts/verify-phase-c.ps1)，從 repo 根目錄執行，腳本自行建立暫時 port-forward。[停機輸出](evidence/phase-c/phase-c-acceptance.log)、[商品事件](evidence/phase-c/phase-c-product-topic.log)、[使用者事件](evidence/phase-c/phase-c-user-topic.log)、[Tempo](evidence/phase-c/phase-c-order-trace.json)、[告警 API](evidence/phase-c/phase-c-alert-rules.json)、[lag 查詢](evidence/phase-c/phase-c-lag-query.json)。

驗收修復：lag 指標及 service_name 標籤、DLT 第一筆計數零值基線、Kafka trace（Spring SDK、明確 W3C consumer scope、避免 template observation 覆寫 header）。告警產生器同步修正。common、inventory、order 回歸19:53:12全數成功，1:39；[結果](evidence/phase-c/trace-regression-summary.log)。

R3 五項通過後開始 R4，結果見下一節。

## 2026-11-11 R4 移除結果

R3 五項部署驗收全部通過後，移除 app.events.enabled 與 APP_EVENTS_ENABLED、service 分支、OrderNotificationRetryJob、ReservationSyncJob、相關 properties 與同步通知 client 方法。付款結果、訂單付款／取消／逾時現在一律與 outbox 共用交易。建單預扣與存單失敗時的釋放仍保留，因為它們是建立訂單的同步操作。

新增 payment V6、order V8 升級 migration：先將待完成的舊同步狀態寫入 outbox（已存在事件不重建），再移除欄位與相依索引／限制。已部署的歷史 migration 保留，以維持 Flyway 校驗碼。兩個真實 PostgreSQL 升級測試驗證補事件、去重與刪欄位；API 測試改驗證持久化事件及並行冪等，不再要求同步 HTTP 通知。

R4 kind 升級後再次執行 `powershell -NoProfile -File infra/k8s/scripts/verify-phase-c.ps1`，C4/C5 停機恢復及庫存數量再次通過；[輸出](evidence/phase-c/phase-c-r4-acceptance.log)。實際資料庫查詢 information_schema.columns，order.reservation_synced、payment.order_notification 數量皆為 0；Kafka consumer quota 已刪除。

R4 trace ID `83c66c2341d14ea3a0a4dd12b208365e` 已確認跨付款回呼、payment outbox、order payment.events process、order outbox、inventory order.events process；[span 證據](evidence/phase-c/r4-trace-spans.json)。[最終部署狀態](evidence/phase-c/r4-deployments.log)。

最終 R4 完整驗證：`backend/mvnw.cmd -o clean test` 於 2026-11-11 20:12:19 結束，九模組全數 SUCCESS，7:05；含 Kafka、PostgreSQL、兩個歷史 schema 升級測試及並行冪等回歸。[最終 reactor 摘要](evidence/phase-c/r4-reactor-summary.log)。

---

<a id="review-c"></a>

## 階段 C 實作審查與待修項目

> 審查日期：2026-11-11
> 對象：未 commit 的階段 C 實作（Kafka + Outbox，C1–C7），對照 [middleware-roadmap.md](middleware-roadmap.md)「階段 C」
> 範圍：只做了靜態閱讀（diff 與核心程式），**尚未執行測試與 build**

<a id="review-c-整體評價"></a>

### 整體評價

方向與核心設計正確，可保留：

- `OutboxRelay`：`FOR UPDATE SKIP LOCKED` 加 `NOT EXISTS older`，同一 aggregate 只領最早的未送事件，多副本順序不會亂
- `ProcessedEventHandler`：`ON CONFLICT DO NOTHING`，去重與業務更新同一交易，業務失敗一起回滾
- outbox 保存 `traceparent`，relay 還原 parent span 並寫入 Kafka header
- DLT 發送失敗時往外拋，不靜默遺失事件
- `schema` 有 regex 檢查；`@Modifying(clearAutomatically/flushAutomatically)` 是方法改為 `@Transactional` 後必要的修正
- 已補 `SEC-13`、consumer lag 與 DLT 告警、Kafka 的 NetworkPolicy

以下問題請依優先順序修正。

<a id="review-c-待修項目"></a>

### 待修項目

<a id="review-c-p1正確性與設計"></a>

#### P1：正確性與設計

**1. 預設值不一致**
`PaymentServiceImpl`、`OrderServiceImpl` 的 `@Value("${app.events.enabled:true}")` 缺省為「開」，但 `ReservationSyncJob`、`OrderNotificationRetryJob` 用 `havingValue = "false", matchIfMissing = true`，缺省時 job 仍會執行。目前靠 yml 明確設值才沒出問題。
→ 兩邊的缺省必須一致。

**2. 新舊兩套路徑並存**
roadmap C4、C5 的要求是「移除」payment → order 的同步通知與 `OrderNotificationRetryJob`、order 的 `ReservationSyncJob` 與 `reservation_synced` 補做邏輯。目前改成 `eventsEnabled` 旗標加 if/else 散在 service，舊程式都還在。另外事件模式下 `orderNotification` 在建立時就寫成 `DONE`，語意不對（通知根本還沒發生）。
→ 依 roadmap 移除舊路徑（含旗標、舊 job、對應欄位與測試），或者先在這份文件與 roadmap 寫明「保留旗標作為過渡」的理由與移除時間，並修正 `DONE` 的語意（例如新增 `NOT_REQUIRED`，或移除欄位）。二選一，不要停在中間。

**3. `ResilientEventConsumer` 對不可重試的錯誤也重試**
JSON 解析失敗、不支援的事件型別、訂單已取消（`ORDER_ALREADY_CANCELLED`）都會睡 0.5s、1s、2s 重試三次才進 DLT，而且阻塞在 listener 執行緒。
→ 區分可重試（暫時性：DB、連線）與不可重試（資料或規則錯誤），後者直接進 DLT。
→ 「付款成功但訂單已取消」原本是記錄 `REJECTED` 並提醒要退款，現在變成進 DLT，需決定處理方式（至少 log 與文件要寫明，退款屬階段 F）。
→ 重試的等待改用 Spring Kafka 的 `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` + backoff，不要在 listener 裡 `Thread.sleep`。

<a id="review-c-p2測試缺口"></a>

#### P2：測試缺口

**4. 缺少與 roadmap 驗收對應的測試**

| Step | 驗收 | 現況 |
| ---- | ---- | ---- |
| C2 | rollback 不送出；Kafka 停掉後保留、恢復後送出 | 有，但 `ObjectMapper` 被 mock，payload 是假的；建議改用真的 `ObjectMapper` |
| C3 | 同一事件送兩次只處理一次；失敗進 DLT 不卡 partition | 只有 mock 的單元測試；**沒有 DLT 測試**。需要真 PostgreSQL（Testcontainers）驗證重複事件與並行重送，並驗證事件進 `<topic>.DLT` |
| C4 | 停掉 order 時付款仍成功；恢復後變 PAID；E2E | **沒有**。至少要有 `PaymentEventListener` 的整合測試，E2E 用 compose 驗證並記錄結果 |
| C5 | 停掉 inventory 時取消與付款仍成功；恢復後預扣正確確認／釋放 | **沒有**。`OrderEventListener` 同上 |
| C6 | product、user 事件格式符合規格 | 需要驗證 payload 與 [event-contracts.md](../../architecture/event-contracts.md) 一致 |
| C7 | 一張訂單的 trace 跨過 Kafka；lag 有告警 | 需要實際在 Grafana／Tempo 確認並記錄 |

各服務 `src/test/resources/application.properties` 疑似被改成關閉事件，代表現有服務測試走的是舊路徑。事件模式本身的服務測試需要補上（例如 `markPaid`、`cancelOrder`、`expireUnpaidOrder` 在事件模式下會寫出 `OrderPaid`／`OrderCancelled`）。

<a id="review-c-p3品質與一致性"></a>

#### P3：品質與一致性

**5. Relay 在 DB 交易內等 Kafka ACK**
每筆最多等 10 秒，一批 50 筆，交易與連線佔用可能很久；中途失敗會 rollback 已送出事件的標記而造成重送（有去重所以不會壞）。
→ 縮短 send timeout、縮小批次，或改為「領取後單筆提交」；至少在程式註解與文件寫明取捨。

**6. 順序依賴應用端的 `occurred_at`**
多副本時鐘有偏差時，同一 aggregate 的排序可能錯。
→ 改用資料庫產生的遞增序號（`bigserial`／`created_at default now()` 加 sequence）作為排序依據。

**7. `UuidV7` 重複**
`common/events/UuidV7`（package-private）與 `payment/support/UuidV7` 各一份。
→ 統一放到 `common`（公開），payment 改用它並刪除自己的版本。

**8. 程式風格**
多處使用全名寫法（`java.util.Map.of`、`java.util.concurrent.TimeUnit`、`java.util.List.of`）、import 順序混亂（例如 `PaymentServiceImpl` 的 `OrderNotification`）。
→ 改成 import，與專案其他程式一致。

<a id="review-c-p4流程與文件"></a>

#### P4：流程與文件

**9. 文件語言**
[event-contracts.md](../../architecture/event-contracts.md) 是英文，其他文件與程式註解都是繁體中文。
→ 改成繁體中文。

**10. roadmap 進度未更新**
只改了狀態列，C1–C7 的步驟表沒有標記進度與驗收結果。
→ 每步完成後在 roadmap 標記，並補一份 `event-implementation-plan.md`（格式比照 [observability-implementation-plan.md](observability-implementation-plan.md)、[resilience-implementation-plan.md](resilience-implementation-plan.md)），記錄決定、實測結果與遺留問題。

**11. commit 方式**
目前 C1–C7 全部混在同一批未 commit 的變更（34 個修改檔加新檔）。專案慣例是每步測試通過後單獨 commit。
→ 修完上述問題後，依 C1 → C7 拆成多個 commit：
- C1：Kafka（compose、K8s、NetworkPolicy、topic 建立）
- C2：outbox 共用元件與 migration
- C3：消費端共用元件（`processed_event`、DLT）
- C4：付款成功改事件
- C5：庫存確認、釋放改事件
- C6：product、user 事件
- C7：可觀測（observation、指標、告警）

`.vscode/launch.json` 的變更請確認是否屬於本階段，並放進對應的 commit。

<a id="review-c-修正追蹤2026-11-11"></a>

### 修正追蹤（2026-11-11）

- 項目 1：舊排程改為缺省不啟動，僅明確設定 events=false 才啟動。
- 項目 2：採過渡方案；事件模式使用 NOT_REQUIRED，並新增 DB 約束 migration。移除理由與時點記錄於事件契約及實作計畫。
- 項目 3：改用 DefaultErrorHandler、DeadLetterPublishingRecoverer；資料與 4xx 業務錯誤直接送 .DLT，5xx 與暫時性錯誤重試。已取消訂單的付款會記錄需人工退款。
- 項目 4：已補真實 mapper、PostgreSQL 並行去重、Kafka DLT 後繼訊息處理、付款／訂單／庫存事件模式，以及商品／使用者契約整合測試；部署停機恢復、C6 topic 與 C7 實測仍待驗收。
- 項目 5、6：批次降為十筆，ACK 等待三秒，producer metadata 等待三秒；以 DB sequence_id 排序，交易鎖取捨已記錄。
- 項目 7、8：統一公開 common UUIDv7，移除付款服務副本，整理事件程式的全名與 import。
- 項目 9、10：事件契約改繁體中文，新增 [事件實作計畫](#) 追蹤逐項進度。
- 項目 11：驗收尚未完成，因此尚未拆分 commit；原有 .vscode/launch.json 排除於本階段。

整合測試另發現並修正 Spring Boot 4 Kafka starter 缺漏，以及與 Boot 內建 Kafka metrics bean 重名的問題。

<a id="review-c-驗收清單"></a>

### 驗收清單

- [x] 上述 P1 三項修正完成（舊路徑採明訂期限的過渡方案）
- [x] `mvn test` 全數通過（2026-11-11 19:21，全九模組 SUCCESS；含 Kafka、PostgreSQL。商品契約擴充另行定向測試通過）
- [ ] compose 與 kind 上完成 C4、C5 的停服務再恢復驗證，結果記錄在文件
- [ ] C7 的跨服務 trace 與告警實測有截圖或查詢紀錄
- [ ] 文件改為繁體中文，roadmap 步驟表標記進度
- [ ] 依步驟拆 commit，每個 commit 測試通過

---

<a id="review-c-round-2"></a>

## 第二輪審查（修正後）

> 審查日期：2026-11-11
> 範圍：Codex 依上方清單修正後的未 commit 變更；靜態閱讀程式與文件，**未重跑測試**（測試結果為 `event-implementation-plan.md` 自述）

<a id="review-c-第一輪項目處理結果"></a>

### 第一輪項目處理結果

| 項目 | 結果 |
| ---- | ---- |
| 1 預設值不一致 | 已解決：舊 job 改 `matchIfMissing = false`，只有明確設為 `false` 才啟動，與 `@Value` 缺省（開）一致 |
| 2 新舊路徑並存 | 採過渡方案：保留 `app.events.enabled=false` 舊路徑；新增 `NOT_REQUIRED` 取代事件模式下的 `DONE`；移除時點寫入 `event-contracts.md`。**結案前必須真的移除**（見下方 R4） |
| 3 重試 | 已解決：改用 `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` + `FixedBackOff(500ms, 3 次)`；JSON、`IllegalArgumentException`、業務規則錯誤直接進 DLT；DLT 失敗往外拋；`events.dlt.published` 只在送成功後累加 |
| 4 測試缺口 | 新增 order、inventory、payment、product、user 的整合測試；DLT、並行去重已有測試（自述通過） |
| 5 Relay 持有交易 | 縮小：每批 10 筆、每筆 ACK 最多 3 秒，取捨寫入文件 |
| 6 排序依賴應用端時鐘 | 已解決：各服務 migration 新增 `sequence_id BIGSERIAL`，relay 依它排序 |
| 7 `UuidV7` 重複 | 已解決：統一在 `common`，payment 自己的版本已刪除 |
| 8 程式風格 | 已改為 import |
| 9 文件語言 | `event-contracts.md` 已改為繁體中文 |
| 10 roadmap／實作紀錄 | 新增 `event-implementation-plan.md`，列出各步驟進度與待驗證項目 |
| 11 拆 commit | 尚未做（結案前處理） |

<a id="review-c-待修項目-1"></a>

### 待修項目

<a id="review-c-r1resilienteventconsumer-職責不清建議修"></a>

#### R1：`ResilientEventConsumer` 職責不清（建議修）

`common` 的通用元件目前只剩下「把 `AppException` 轉成 `EventRejectedException`」，卻寫死了 order 專屬的 `"ORDER_ALREADY_CANCELLED"` 錯誤碼與退款提醒 log。共用元件不應知道單一服務的業務碼。

→ 把「付款成功但訂單已取消」的 log 移到 order 的 `PaymentEventListener`（或由 listener 傳入判斷／回呼）。
→ `ResilientEventConsumer` 只保留通用的「可重試／不可重試」分類，並以錯誤碼的 HTTP 狀態判斷，不比對字串。
→ 若分類邏輯已能由 `DefaultErrorHandler` 的 `addNotRetryableExceptions` 涵蓋，評估是否可以直接刪除這個類別，讓 listener 直接丟 `EventRejectedException`。

<a id="review-c-r2完整測試需要獨立驗證"></a>

#### R2：完整測試需要獨立驗證

`event-implementation-plan.md` 記錄「全九模組 `mvn test` SUCCESS（含 Kafka、PostgreSQL Testcontainers）」，但此為自述。
→ 由審查者在乾淨環境重跑完整 reactor（需要 Docker），結果記錄在本文件或實作紀錄。

<a id="review-c-r3部署驗收尚未完成"></a>

#### R3：部署驗收尚未完成

目前只有程式與測試層級的驗證。以下仍待做，且需把結果（指令、輸出或截圖）寫進 `event-implementation-plan.md`：

- [ ] kind：部署新版後，沒有授權的 Pod 連不到 Kafka（C1）
- [ ] 停掉 order 時付款仍成功；恢復後訂單自動變成 PAID（C4）
- [ ] 停掉 inventory 時取消與付款仍成功；恢復後預扣正確確認／釋放（C5）
- [ ] 實際 topic 內可看到 product、user 事件，格式符合 `event-contracts.md`（C6）
- [ ] Tempo 看到一張訂單的 trace 跨過 Kafka；consumer lag 與 DLT 告警實測（C7）

<a id="review-c-r4結案前移除過渡路徑"></a>

#### R4：結案前移除過渡路徑

R3 全部通過後，刪除：`app.events.enabled` 旗標與服務內的 if/else、`OrderNotificationRetryJob`、`ReservationSyncJob`、`payment.order_notification` 與 `order.reservation_synced` 的舊邏輯（含 migration、設定、測試與文件中的相關描述）。移除也要單獨 commit，並重跑測試。

<a id="review-c-r5commit-拆分"></a>

#### R5：commit 拆分

依序拆成 C1 → C7 共七個 commit（內容見第一輪項目 11），每個 commit 測試通過。`.vscode/launch.json` 不屬本階段，排除。另有幾份新 migration（`*_order_outbox_by_sequence.sql`）請歸入 C2。

<a id="review-c-小提醒不阻擋"></a>

### 小提醒（不阻擋）

<a id="review-c-第二輪修正追蹤"></a>

#### 第二輪修正追蹤

- R1：退款提醒已移至 order 的 PaymentEventListener，以 OrderErrorCode enum 判斷；common 僅依 HTTP 狀態區分 5xx 可重試與其他業務錯誤不可重試。保留共用分類元件，因 DefaultErrorHandler 的例外類型分類無法直接區分同一 AppException 的 4xx 與 5xx。
- R1 測試：新增與訂單錯誤碼無關的 HTTP 狀態分類單元測試，以及已取消訂單不提交去重紀錄與 OrderPaid 的整合測試。2026-11-11 19:28，執行離線 Maven `-pl common,spring-order-service,spring-inventory-service,spring-payment-service -am test`，五模組全部 SUCCESS（2 分 02 秒，含 Kafka／PostgreSQL Testcontainers）。這是實作者回歸結果，不取代 R2 的獨立驗證。
- R2：仍待獨立審查者在乾淨環境驗證；實作者自行重跑不視為獨立驗收。
- R3 尚待新版部署驗收；R4 依 R3 全部通過後才執行。R5 尚未完成。

- 命名：`V4__create_event_tables.sql` 與 `V5__order_outbox_by_sequence.sql` 這類「先建後改」的 migration 因為尚未 commit，可直接合併成一份，避免留下沒有發布過的中間版本。
- 同一階段若之後要用 SASL，`SEC-13` 與 NetworkPolicy 要一起更新。

<a id="review-c-r1r4-最終處理2026-11-11"></a>

### R1–R4 最終處理（2026-11-11）

以下紀錄取代上方「第二輪修正追蹤」的待辦狀態；原審查內容保留作為歷史。

| 項目 | 結果 |
|---|---|
| R1 | 通用 consumer 依 HTTP 狀態分類，沒有 order 業務碼；退款提醒位於 PaymentEventListener，以 OrderErrorCode enum 判斷。分類、取消訂單 rollback 與 W3C context 測試通過 |
| R2 | 本次重新執行完整 `mvnw.cmd -o clean test`，含 Kafka/PostgreSQL Testcontainers；不是引用前次結果。本機 clean build 的結果與限制記錄於實作紀錄，不宣稱隔離 CI 或第三方簽核 |
| R3 | kind 的未授權 Pod、C4/C5 停機恢復、C6 實際 topic、C7 Tempo 與真實告警 firing 全數通過；並修復驗收找出的 lag 指標名稱、DLT 零值基線、Kafka trace 傳遞問題 |
| R4 | R3 通過後移除旗標、分支、舊 job／properties／client 通知、補償欄位與舊測試。新增升級 migration 保留未完成通知，既有歷史 migration 不改校驗碼；kind 升級與停機恢復重測通過。獨立提交於 R1–R3 之後 |

指令、輸出、trace、告警及完整測試摘要見 [事件實作與驗收紀錄](#)。R1–R3 修正提交為 `eec6350`；R4 後續獨立提交。R5 的 C1→C7 歷史拆分不在本次範圍，原有 `.vscode/launch.json` 不納入提交。

最終 R4 完整 `clean test`：2026-11-11 20:12:19，九模組 SUCCESS，7:05（含 Kafka、PostgreSQL 與升級 migration）。[摘要](evidence/phase-c/r4-reactor-summary.log)。
