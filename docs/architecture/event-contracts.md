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
| user.events | UserRegistered | userId | userId、email、displayName | 通知服務維護本地收件人資料 |

商品快照的 description、brand 與 categoryId 未設定時使用空字串；createdAt 是商品建立時間的 ISO-8601 UTC 字串；price 是 JSON 數字，payload.version 是商品的樂觀鎖整數版本。事件信封的 version 則是契約版本，兩者意義不同。商品更新先 flush 再寫 outbox，確保 payload.version 是本次提交後的版本，同一商品的事件版本嚴格遞增。搜尋索引消費者不使用 `processed_event`：它把 payload.version 傳給 Elasticsearch 的 `external_gte`，因此重複事件可冪等覆寫，較舊快照會被略過。索引處理結果以 `search.index.events{result=indexed|stale_skipped|failed}` 計數；consumer lag 監控 `product-service-search` 群組，ES 查詢失敗則由搜尋服務退回 SQL 並記錄原因。

業務更新與 outbox 寫入共用交易。Relay 預設每批 100 筆（`app.events.outbox-batch-size`），先送出各 aggregate 最早的事件，再等待 ACK，只批次標記成功的事件。取滿時立即繼續，單輪最多十批（`app.events.outbox-max-batches`），失敗事件留待下輪。ACK 後提交失敗仍會重送；執行資料庫副作用的消費者以 `processed_event` 去重。商品搜尋索引是刻意的例外：不寫 `processed_event`，而以商品的 payload.version 作 ES `external_gte` 版本，讓索引覆寫冪等並拒絕舊快照。已發布事件保留七天，未發布事件持續保留。去重紀錄預設保留 14 天（`app.events.processed-retention`），須大於 topic 保留期；延長 Kafka 保留期時也必須延長此值。超過去重窗口的人工重放需先確認業務冪等性。清理由 ShedLock 協調各副本。

DefaultErrorHandler 負責重試與 DLT。暫時性資料存取錯誤、資料庫資源故障、無法建立交易及 5xx AppException 採 1 秒起、倍增至 30 秒的無限重試，保留 partition 內事件順序；未分類錯誤仍重試三次、間隔 500ms；JSON、參數及業務規則錯誤直接進入來源 topic 加上 .DLT 的死信佇列。退避上限 30 秒低於預設 `max.poll.interval.ms=300000`；若調低 poll interval 或延長處理逾時，須一併重新評估。整合測試暫停 PostgreSQL 五秒後恢復，驗證重試完成且 DLT 計數與 topic offset 不增加。DLT 發送失敗向外拋出，保留原事件。付款成功但訂單已取消時，需人工追蹤退款；自動退款隨真實金流（路線圖階段 F）暫緩。

## DLT 處理與重放

告警的當班維運人員先建立追蹤紀錄，記下 topic、partition、offset、例外、orderId／paymentId 與負責人。以下命令只讀取，不提交消費 offset；輸出可能含個資，勿貼到公開工單。

```sh
# Compose，依實際 compose 檔路徑加上 -f
docker compose -f infra/docker-compose.yml exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic payment.events.DLT --partition 0 --offset earliest --max-messages 10 --property print.headers=true --property print.key=true --property print.offset=true
# kind，namespace 依部署環境調整
kubectl -n my-microservice exec kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic payment.events.DLT --partition 0 --offset earliest --max-messages 10 --property print.headers=true --property print.key=true --property print.offset=true
```

讀取 `kafka_dlt-original-topic`、`kafka_dlt-original-partition`、`kafka_dlt-original-offset`、`kafka_dlt-exception-fqcn`、`kafka_dlt-exception-message`；partition／offset header 是二進位整數，console 顯示可能不是十進位。格式錯誤先修發送端，勿反覆重放原本的壞訊息；業務衝突交由業務負責人處理；基礎設施故障先確認已恢復，再重放。付款成功但訂單已取消時，訂單／付款負責人須核對金流結果、執行人工退款，將 orderId、paymentId、退款憑證與處理時間記入追蹤紀錄後才結案，單純重放不能解決衝突。

重放工具使用專案相同的 Kafka Java client，Java 21 即可執行。先從 common 產生 classpath，Windows 使用 `mvnw.cmd` 並以 PowerShell 讀取 classpath 後呼叫 `java --class-path $cp infra/scripts/DltReplay.java ...`。

```sh
cd backend
./mvnw -q -pl common dependency:build-classpath -Dmdep.outputFile=target/replay-classpath.txt
cd ..
export KAFKA_CLIENT_CLASSPATH="$(cat backend/common/target/replay-classpath.txt)"
bash infra/scripts/dlt-replay.sh localhost:9094 payment.events.DLT 0 12 15
```

最後三個參數是 partition、起始 offset（含）、結束 offset（不含）。工具先完整讀取並驗證範圍（最多 10000 個 offset），列出 offset、來源 topic、key 與長度，輸入 `REPLAY` 才送出；key、value（含 eventId）、headers 與原 partition 均保留，逐筆等待 broker ACK 並列出新 offset。不提交 DLT 消費位置，也不刪除死信。中途失敗可依已列出的成功 offset 縮小範圍後重試；去重窗口內已處理的事件會被略過。先小範圍執行，再核對訂單、庫存、消費 lag 與 DLT 告警，完成後在追蹤紀錄標明重放範圍及結果。不要啟用自動 DLT 重放 listener。

事件為唯一的付款與庫存狀態通知路徑。R3 部署驗收通過後已移除旗標、舊重試工作與補償欄位。升級 migration 先將未完成的舊通知補入 outbox，再刪除欄位；已部署的歷史 migration 保留校驗碼。

UserRegistered 含 email，不可將 payload 或地址複製到 log 或 trace attribute。現有 Kafka 未啟用 SASL/ACL，見[已知安全問題](../security/known-issues.md)。


## 訂單事件發送規則

- `OrderCreated` 於訂單第一次建立時寫入 outbox，與訂單保存使用同一資料庫交易。重送冪等請求僅回傳既有訂單，不會再產生事件。事件的 `aggregateId` 是 orderId；payload 包含 userId 與 JSON 數值 totalAmount。
- `OrderShipped` 只在訂單成功從 PAID 轉為 SHIPPED 的同一交易寫入；payload 包含 orderId。重複出貨請求不會重發。
- inventory-service 將這兩種類型記錄於 `processed_event` 後忽略；其他未知 `order.events` 類型仍進 DLT。


## 通知服務消費行為

`notification-service` 消費 `UserRegistered` 維護本地收件人資料；消費 `OrderCreated` 建立訂單與使用者的對應並排入「訂單成立」信；消費 `OrderPaid`、`OrderShipped`、`OrderCancelled` 排入對應的信；消費 `PaymentFailed` 排入「付款失敗」信。`PaymentSucceeded` 是已知但不需要的事件，明確忽略，付款成功信由 `OrderPaid` 觸發。事件去重與信件入列放在同一個資料庫交易。SMTP 寄送是非同步且有界重試；找不到訂單或收件人資料時，經重試後標記為 `SKIPPED`。新部署的服務從 earliest 開始消費，會處理 Kafka 保留期內的歷史事件。
