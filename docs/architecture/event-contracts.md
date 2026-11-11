# 事件契約

事件以 JSON 傳輸，格式獨立於生產端 Java 類別。消費端應忽略未使用欄位；修改欄位型別或語意須提升 version。事件至少投遞一次，消費端以 eventId 去重。

```json
{"eventId":"UUIDv7","type":"PaymentSucceeded","version":1,"occurredAt":"2026-11-11T10:00:00Z","aggregateId":"order UUID","payload":{}}
```

Kafka key 為 aggregateId。本機與 kind 的 topic 有三個 partition。同一 aggregate 以資料庫 sequence_id 排序，避免依賴應用端時鐘；序號不代表跨交易的提交順序，並行更新仍須由業務端以鎖或版本控制協調。

IDE 使用 localhost:9094；Compose 容器與 kind 使用 kafka:9092。

| Topic | 型別 | Aggregate | Payload | 消費行為 |
|---|---|---|---|---|
| payment.events | PaymentSucceeded | orderId | paymentId、orderId、status、amount | 訂單改為已付款 |
| payment.events | PaymentFailed | orderId | paymentId、orderId、status、amount | 記錄消費，維持訂單狀態 |
| order.events | OrderPaid | orderId | orderId | 確認庫存預留 |
| order.events | OrderCancelled | orderId | orderId、選填 reason | 釋放庫存預留 |
| product.events | ProductCreated、ProductUpdated、ProductStatusChanged | productId | productId、sku、name、description、price、categoryId、status、version | 預留給搜尋索引與快取更新 |
| user.events | UserRegistered | userId | userId、email、displayName | 預留給通知服務 |

商品快照的 description 與 categoryId 未設定時使用空字串；price 是 JSON 數字，version 是商品的樂觀鎖整數版本。事件信封的 version 則是契約版本，兩者意義不同。

業務更新與 outbox 寫入共用交易。Relay 每批最多十筆，每筆等待 ACK 最多三秒，期間持有 DB 連線與資料列鎖，作為低流量雛型的簡化取捨。ACK 後提交失敗仍會重送，消費端須以 processed_event 去重。已發布事件保留七天，未發布事件持續保留。

DefaultErrorHandler 負責重試與 DLT。暫時性錯誤重試三次、間隔 500ms；JSON、參數及業務規則錯誤直接進入來源 topic 加上 .DLT 的死信佇列。DLT 發送失敗向外拋出，保留原事件。付款成功但訂單已取消時，錯誤處理器記錄例外並送 DLT，需人工追蹤退款；自動退款屬階段 F。

事件為唯一的付款與庫存狀態通知路徑。R3 部署驗收通過後已移除旗標、舊重試工作與補償欄位。升級 migration 先將未完成的舊通知補入 outbox，再刪除欄位；已部署的歷史 migration 保留校驗碼。

UserRegistered 含 email，不可將 payload 或地址複製到 log 或 trace attribute。現有 Kafka 未啟用 SASL/ACL，見[已知安全問題](../security/known-issues.md)。
