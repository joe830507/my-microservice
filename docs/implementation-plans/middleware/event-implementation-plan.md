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
