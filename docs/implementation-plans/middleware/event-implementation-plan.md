# 階段 C 事件實作與驗收紀錄

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
