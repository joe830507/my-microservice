# 階段 C 事件實作與驗收紀錄

## 決策

採用 Kafka、Transactional Outbox 與資料庫消費去重。重試策略、交易等待取捨、過渡路徑移除條件見[事件契約](../../architecture/event-contracts.md)。

## 進度

| 步驟 | 實作與驗收 |
|---|---|
| C1 | Compose、K8s、topic、網路政策已建立。本機 topic 驗證通過，Kustomize 可組譯；kind 執行待驗證 |
| C2 | 真實 mapper、交易 rollback、broker 不可達保留及恢復投遞測試通過；使用 DB sequence_id 排序 |
| C3 | 真實 PostgreSQL 並行去重與失敗回滾測試通過；Kafka DLT 與同 partition 後續訊息可繼續處理的測試通過 |
| C4 | 訂單消費付款事件、重複事件只產生一筆 OrderPaid、取消與逾時寫出事件的整合測試通過；停機恢復與 E2E 待驗證 |
| C5 | 真實 PostgreSQL 驗證 OrderPaid 確認預留、OrderCancelled 釋放預留，重複事件不重扣；部署停機恢復待驗證 |
| C6 | ProductCreated、ProductUpdated、ProductStatusChanged 與 UserRegistered 的真實資料庫契約測試通過，包含欄位、版本、聚合識別及排除密碼；實際 topic 驗收待辦 |
| C7 | traceparent、指標與告警已設定；Tempo trace 與告警實測待驗證 |

2026-11-11 19:21，完整 backend reactor 執行離線 mvn test，全九模組 SUCCESS（5 分 58 秒）；含 Kafka、PostgreSQL Testcontainers。商品契約擴充涵蓋更新與封存後，另行定向測試也通過。

測試過程修復舊資料表列表斷言、Kafka starter 缺漏、metrics bean 重名，以及舊同步回退的重送補償行為。這些結果不代表部署驗收通過。

已取得本機 kind-my-microservice context 狀態；目前運行舊版服務，尚未將此次程式部署供驗收。

## 結案條件

完成待驗證項目並移除過渡路徑，再依 C1 至 C7 拆分提交。原有 .vscode/launch.json 不屬本階段，排除於提交之外。

## 2026-11-11 R1–R3 重新驗證

執行 `backend/mvnw.cmd -o clean test`，19:41:21 結束，完整九模組 SUCCESS，耗時 6:59，包含真實 Kafka、PostgreSQL Testcontainers。這是本次新執行的驗證；本機 clean build 不等同隔離 CI 或不同人員簽核。[reactor 結果](evidence/phase-c/reactor-summary.log)。

部署：`docker build -f backend/Dockerfile --build-arg SERVICE=<service> -t my-microservice/<service>:phase-c backend`、`kind load docker-image --name my-microservice ...`、`kubectl --context kind-my-microservice apply -k infra/k8s`，再 `kubectl set image` 更新五個事件服務；後續修正版採獨立映像標籤。

| 驗收 | 實測結果 |
|---|---|
| C1 | Calico kind 的 Kafka ready、八個 topic 初始化成功。未授權 app=phase-c-denied Pod 執行 `nc -z -w 3 kafka 9092`，connection_exit=1、Pod Succeeded；已授權服務正常發布消費 |
| C4 | order scale=0 並等 Pod 刪除後付款 SUCCEEDED，訂單仍 PENDING_PAYMENT；scale=2 後自動 PAID、預留 CONFIRMED |
| C5 | inventory scale=0 時付款、取消皆成功；恢復後 CONFIRMED、RELEASED，available=18、reserved=0（初始20） |
| C6 | 實際 API 建商品、註冊後讀取 topic，ProductCreated、UserRegistered 的 version=1、聚合識別與 payload 一致，無密碼 |
| C7 trace | Tempo `/api/traces/fe50ccdfc71446a6a7d10c910293ffa5` 包含 order HTTP、outbox publish、inventory order.events process；訂單01a1ca77-250a-704f-a027-9d3a811cf854 |
| C7 告警 | 真實事件及暫時 consumer quota 造成 lag>1000 持續五分鐘，19:54 firing；格式錯誤訊息送 DLT，19:53 DLT firing；兩者 health=ok。驗收後移除 quota 與暫時 fetch 設定 |

停機操作見 [驗收腳本](../../../../infra/k8s/scripts/verify-phase-c.ps1)，從 repo 根目錄執行，先建立 payment port-forward 18085:8085。[停機輸出](evidence/phase-c/phase-c-acceptance.log)、[商品事件](evidence/phase-c/phase-c-product-topic.log)、[使用者事件](evidence/phase-c/phase-c-user-topic.log)、[Tempo](evidence/phase-c/phase-c-order-trace.json)、[告警 API](evidence/phase-c/phase-c-alert-rules.json)、[lag 查詢](evidence/phase-c/phase-c-lag-query.json)。

驗收修復：lag 指標及 service_name 標籤、DLT 第一筆計數零值基線、Kafka trace（Spring SDK、明確 W3C consumer scope、避免 template observation 覆寫 header）。告警產生器同步修正。common、inventory、order 回歸19:53:12全數成功，1:39；[結果](evidence/phase-c/trace-regression-summary.log)。

R3 五項通過後開始 R4，結果另記。R5 拆分不在此次 R1–R4 範圍。
