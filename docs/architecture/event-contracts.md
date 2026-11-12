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
| order.events | OrderCreated | orderId | userId、totalAmount | 通知服務建立訂單收件人關聯；inventory 去重後忽略 |
| order.events | OrderPaid | orderId | orderId | 確認庫存預留 |
| order.events | OrderShipped | orderId | orderId | 通知服務寄送出貨通知；inventory 去重後忽略 |
| order.events | OrderCancelled | orderId | orderId、選填 reason | 釋放庫存預留 |
| product.events | ProductCreated、ProductUpdated、ProductStatusChanged | productId | productId、sku、name、description、brand、createdAt、price、categoryId、status、version | product-service 搜尋索引與快取更新 |
| user.events | UserRegistered | userId | userId、email、displayName | 預留給通知服務 |

商品快照的 description、brand 與 categoryId 未設定時使用空字串；createdAt 是商品建立時間的 ISO-8601 UTC 字串；price 是 JSON 數字，payload.version 是商品的樂觀鎖整數版本。事件信封的 version 則是契約版本，兩者意義不同。商品更新先 flush 再寫 outbox，確保 payload.version 是本次提交後的版本，同一商品的事件版本嚴格遞增。搜尋索引消費者不使用 `processed_event`：它把 payload.version 傳給 Elasticsearch 的 `external_gte`，因此重複事件可冪等覆寫，較舊快照會被略過。索引處理結果以 `search.index.events{result=indexed|stale_skipped|failed}` 計數；consumer lag 監控 `product-service-search` 群組，ES 查詢失敗則由搜尋服務退回 SQL 並記錄原因。

業務更新與 outbox 寫入共用交易。Relay 每批最多十筆，每筆等待 ACK 最多三秒，期間持有 DB 連線與資料列鎖，作為低流量雛型的簡化取捨。ACK 後提交失敗仍會重送；執行資料庫副作用的消費者以 `processed_event` 去重。商品搜尋索引是刻意的例外：不寫 `processed_event`，而以商品的 payload.version 作 ES `external_gte` 版本，讓索引覆寫冪等並拒絕舊快照。已發布事件保留七天，未發布事件持續保留。

DefaultErrorHandler 負責重試與 DLT。暫時性錯誤重試三次、間隔 500ms；JSON、參數及業務規則錯誤直接進入來源 topic 加上 .DLT 的死信佇列。DLT 發送失敗向外拋出，保留原事件。付款成功但訂單已取消時，錯誤處理器記錄例外並送 DLT，需人工追蹤退款；自動退款隨真實金流（路線圖階段 F）一併不實作。

事件為唯一的付款與庫存狀態通知路徑。R3 部署驗收通過後已移除旗標、舊重試工作與補償欄位。升級 migration 先將未完成的舊通知補入 outbox，再刪除欄位；已部署的歷史 migration 保留校驗碼。

UserRegistered 含 email，不可將 payload 或地址複製到 log 或 trace attribute。現有 Kafka 未啟用 SASL/ACL，見[已知安全問題](../security/known-issues.md)。


## 訂單事件發送規則

- `OrderCreated` 於訂單第一次建立時寫入 outbox，與訂單保存使用同一資料庫交易。重送幂等請求僅回傳既有訂單，不會再產生事件。事件的 `aggregateId` 是 orderId；payload 包含 userId 與 JSON 數值 totalAmount。
- `OrderShipped` 只在訂單成功從 PAID 轉為 SHIPPED 的同一交易寫入；payload 包含 orderId。重複出貨請求不會重發。
- inventory-service 將這兩種類型記錄於 `processed_event` 後忽略；其他未知 `order.events` 類型仍進 DLT。


## Notification consumer behavior

`notification-service` consumes `UserRegistered` to maintain a local contact, `OrderCreated` to associate the order with its user and queue the order-created email, `OrderPaid`, `OrderShipped`, and `OrderCancelled` to queue their matching emails, and `PaymentFailed` to queue the payment-failed email. `PaymentSucceeded` is a known event and is intentionally ignored; the paid email is driven by `OrderPaid`. Inbox deduplication and queue insertion share a database transaction. SMTP delivery is asynchronous and uses bounded retry; missing order/contact data is eventually marked `SKIPPED`.
