# 既有程式碼最佳化檢討（第 1 輪）

- 日期：2026-11-12
- 範圍：backend（common、order、inventory、product、payment、user、notification、gateway）、frontend（vue-customer 部署設定）、Dockerfile、K8s 資源設定
- 方式：靜態閱讀，未執行測試或壓測；以下問題都附上檔案位置與建議做法，交給 Codex 修正後再審查

整體來說，程式的一致性很好：冪等鍵、條件式 UPDATE、outbox／去重、熔斷、快取失效時機（afterCommit）、遠端呼叫不佔 DB 連線等，大部分都已經處理。以下是還能改進的地方，依優先順序排列。

## 優先順序總覽

| # | 優先 | 類型 | 項目 |
|---|---|---|---|
| 1 | 高 | 正確性 | 商品列表的資料庫查詢沒有 tie-breaker，翻頁會重複或漏掉商品 |
| 2 | 高 | 資源 | 登入／註冊的 bcrypt 在 DB 交易內執行，佔住連線 |
| 3 | 高 | 資料成長 | `processed_event` 從不清理，會無限成長 |
| 4 | 中 | 效能 | Outbox relay 逐筆同步等待 ACK，單一失敗會讓整批回滾重送 |
| 5 | 中 | 索引 | Outbox 的部分索引與實際查詢條件不符，有兩個用不到 |
| 6 | 中 | 資源 | EmailDispatcher 在 DB 交易內寄 SMTP |
| 7 | 中 | 索引 | 商品前台列表缺少複合索引 |
| 8 | 低 | 效能 | 子分類展開每次都讀全部分類 |
| 9 | 低 | 效能 | 狀態轉換流程有重複查詢 |
| 10 | 低 | 一致性 | `completeOrder` 沒有 `@Transactional`，也沒有 clear |
| 11 | 低 | 效能 | LoginAttemptService 每次 2～4 次 Redis 往返 |
| 12 | 低 | 效能 | OutboxMetrics 每 500ms 執行一次 `count(*)` |
| 13 | 低 | 建置 | 後端映像檔沒有分層，啟動也沒用 CDS／AOT cache |

---

## 1.（高）商品列表的資料庫查詢沒有 tie-breaker

位置：`backend/spring-product-service/src/main/java/com/mymicroservice/product/dto/product/getProductList/ProductQueryRequest.java:77`

```java
return PageRequest.of(page, size, Sort.by(direction(), column));
```

問題：依 `price`、`name`、`createdAt` 排序時，值相同的商品在 PostgreSQL 裡沒有固定順序。`LIMIT/OFFSET` 翻頁時同一個商品可能出現在兩頁，或某個商品永遠看不到。價格相同的商品很常見，所以實際上容易發生。ES 的排序已經有 `id` 當 tie-break（`ProductSearchService.buildSort`），訂單列表也有（`GetOrdersReqDto.toPageable`），只有這裡漏掉。

建議：

```java
return PageRequest.of(page, size, Sort.by(direction(), column).and(Sort.by(Sort.Direction.ASC, "id")));
```

加一個測試：建立多筆同價商品，依 price 翻頁，確認所有頁面合起來沒有重複也沒有遺漏。

## 2.（高）登入／註冊的 bcrypt 在 DB 交易內執行

位置：`backend/spring-user-service/src/main/java/com/mymicroservice/user/service/impl/AuthServiceImpl.java:39`（類別層級 `@Transactional`）、`:70` `register`、`:94` `login`

問題：`JpaTransactionManager` 開始交易時就向 Hikari 拿連線，所以整個 `login` 期間（Redis 查鎖定 → 查使用者 → bcrypt 比對 → Redis 記錄失敗）都佔著一條 DB 連線；`register` 的 `passwordEncoder.encode` 也是。bcrypt 刻意設計成慢（數十到上百毫秒），預設連線池只有 10 條，登入尖峰或有人暴力猜密碼時，連線池會先被耗盡，連帶拖慢 `/users/me` 等其他 API。失敗的登入（最常見的攻擊情境）完全不需要寫 DB，卻也佔著連線。

建議：

- `login`：拿掉交易，只在「密碼正確、要寫入 refresh token」那一步開交易。做法是把 `issueTokens` 移到另一個 `@Transactional` bean（同類別內部呼叫不會經過代理），或改用 `TransactionTemplate` 包住 `issueTokens`。`findByEmail` 由 repository 自己的唯讀交易處理（需確認 roles 是否 lazy；若是，改用 `findWithRolesByEmail` 之類的 EntityGraph 查詢）。
- `register`：先 `encode` 密碼，再進交易寫入使用者與 outbox。
- 改完後補一個測試確認登入失敗時不會寫入任何 refresh token、成功時 refresh token 與回應一致。

## 3.（高）`processed_event` 從不清理

位置：`backend/common/src/main/java/com/mymicroservice/common/events/ProcessedEventHandler.java`；order、inventory、notification、payment 的 migration

問題：`outbox_event` 有每天清理（`OutboxRelay.purgePublishedEvents`），但每個消費者的 `processed_event` 只增不減。每筆事件一列，長期會成為各服務最大的表，主鍵索引也會一直變大，`INSERT ... ON CONFLICT` 會越來越慢。

建議：在 common 加一個清理排程（和 outbox 清理同一處），刪除 `processed_at < now() - interval 'N days'` 的資料。N 要大於 Kafka topic 的保留期（重送的事件超過保留期就不可能再出現），例如保留期 7 天就留 14 天，並做成設定值 `app.events.processed-retention`。補上 `processed_at` 的索引（或用 BRIN），並加 ShedLock 避免多個副本同時刪（見第 4 點最後一段）。

## 4.（中）Outbox relay 逐筆同步等待 ACK，單一失敗整批回滾

位置：`backend/common/src/main/java/com/mymicroservice/common/events/OutboxRelay.java:48-86`

問題：

1. 每一筆都 `kafka.send(record).get(3, SECONDS)` 才處理下一筆，每輪最多 10 筆（`LIMIT 10` 寫死），每 500ms 一輪，單一副本上限大約每秒 20 筆。下單尖峰或 Kafka 稍慢時會累積延遲（付款完成到訂單變 PAID 的時間會拉長）。
2. 其中一筆失敗時 `status.setRollbackOnly()`，同一批**已經成功送出**的事件也會被回滾成未發布，下一輪重送。消費端有去重所以不會出錯，但會產生不必要的重複訊息，而且 Kafka 持續有問題時每一輪都重送同一批。
3. `purgePublishedEvents` 沒有 ShedLock，每個副本都會執行同一個 DELETE（無害但浪費）。

建議：

- 批次大小改成設定值（例如 `app.events.outbox-batch-size`，預設 100）。
- 先把這一批全部 `send` 出去，收集 future，再逐一等待；成功的標記 `published_at`，第一個失敗之後（同一個 aggregate 的後續事件本來就不在這一批，因為查詢只取每個 aggregate 最早的一筆）的不標記即可，**不要**整批 rollback。用一次 `UPDATE ... WHERE event_id = ANY(?)` 批次標記。
- 若一輪取滿了批次大小，可立即再跑一輪（不等 500ms），積壓時才能追上。
- `purgePublishedEvents` 加 `@SchedulerLock`（各服務已有 shedlock 表；product、user、payment、notification 若沒有要先確認）。

## 5.（中）Outbox 的索引與查詢條件不符

位置：各服務的 outbox migration（例如 `backend/spring-order-service/src/main/resources/db/migration/` 中建立 `idx_outbox_event_pending`、`idx_outbox_event_aggregate_pending` 的檔案）

問題：relay 改成依 `sequence_id` 排序後：

- `idx_outbox_event_pending (occurred_at) WHERE published_at IS NULL` 已經沒有查詢會用到。
- `NOT EXISTS` 子查詢的條件是 `topic = ? AND aggregate_id = ? AND sequence_id < ?`，但 `idx_outbox_event_aggregate_pending` 是 `(topic, aggregate_id, occurred_at, event_id)`，`sequence_id` 不在索引裡，要回表才能比較。

每次寫入 outbox 都要維護這些索引，用不到的索引只是寫入成本。

建議：每個服務新增一個 migration：

```sql
DROP INDEX IF EXISTS idx_outbox_event_pending;
DROP INDEX IF EXISTS idx_outbox_event_aggregate_pending;
CREATE INDEX idx_outbox_event_aggregate_pending_seq
    ON outbox_event (topic, aggregate_id, sequence_id) WHERE published_at IS NULL;
```

（schema 名稱依各服務 migration 的寫法調整；改完用 `EXPLAIN` 確認 relay 查詢走新索引，結果記在 evidence。）

## 6.（中）EmailDispatcher 在 DB 交易內寄 SMTP

位置：`backend/spring-notification-service/src/main/java/com/mymicroservice/notification/mail/EmailDispatcher.java:57`

問題：`SELECT ... FOR UPDATE SKIP LOCKED` 之後，整批（預設 10 封）的 SMTP 寄送都在同一個交易內完成，期間佔著 DB 連線與 row lock。SMTP 慢或逾時時（每封可能數秒），一輪會鎖很久；交易也可能超過資料庫的 idle-in-transaction 限制。

建議：改成「領取 → 寄送 → 回寫」三段：

1. 短交易：`UPDATE email_message SET status='SENDING', lease_until = now() + interval '...' WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED LIMIT ?) RETURNING ...`
2. 交易外逐封寄送。
3. 每封各自用短交易回寫 SENT／重試排程。
4. 查詢條件加上 `OR (status='SENDING' AND lease_until < now())`，副本當機時租約到期可被重新領取。

這會動到 schema 與狀態機（`chk` 約束要加 `SENDING`），請一併更新測試。若評估後覺得目前流量不需要，可以標成「暫緩」並記錄原因。

## 7.（中）商品前台列表缺少複合索引

位置：`backend/spring-product-service/src/main/resources/db/migration/`（目前只有 `idx_product_status`、`idx_product_category` 單欄索引）

問題：前台最常見的查詢是 `WHERE status = 'ACTIVE' [AND category_id IN (...)] ORDER BY created_at DESC, id LIMIT 20`，加上 Spring Data 的 `count(*)`。單欄索引只能先篩出全部 ACTIVE 再排序，商品多時每次都要排序整個結果。

建議（配合第 1 點的 id tie-breaker）：

```sql
CREATE INDEX idx_product_status_created ON product_service.product (status, created_at DESC, id);
CREATE INDEX idx_product_category_status_created ON product_service.product (category_id, status, created_at DESC, id);
```

新增後 `idx_product_status` 可以刪掉（被複合索引的前綴取代）。資料量小時效益不明顯，可以用 `EXPLAIN ANALYZE` 搭配假資料（例如 5 萬筆）驗證後再決定。

## 8.（低）子分類展開每次都讀全部分類

位置：`backend/spring-product-service/src/main/java/com/mymicroservice/product/service/impl/ProductServiceImpl.java:151` `withDescendants`

問題：前台點上層分類（`includeSubcategories=true`）時，每個請求都 `categoryRepository.findAll()`。分類樹已經有 Redis 快取（`CategoryTreeCache`），這裡沒用到。

建議：從 `CategoryTreeCache`（或 `CategoryService` 取樹的方法，快取未命中會自動回填）取得樹，再從樹找出子孫 id；快取失效時機已經是 afterCommit，一致性和現在相同。

## 9.（低）狀態轉換流程有重複查詢

位置：

- `OrderServiceImpl.markPaid`（`:179`）：`findById` → UPDATE → `findById`（成功時又一次）＝ 3 次查詢；第一次 `findById` 只用來判斷存在，可以拿掉，改成 UPDATE 回 0 時再查一次判斷「不存在／已取消／已付款」。
- `OrderServiceImpl.cancelOrder`（`:202`）：`findVisibleOrder`（含明細）最多呼叫 3 次。
- `OrderServiceImpl.shipOrder`、`completeOrder`：`findOrderWithItems` 各 2 次，第一次只為了 404。
- `PaymentServiceImpl.mockCallback`（`:93`）：`findVisiblePayment` + 成功時 2 次 `findById`。

都不是瓶頸，但模式一致，可以順手整理：UPDATE 成功時只在最後讀一次；UPDATE 回 0 時才讀出來判斷原因。注意 `@Modifying(clearAutomatically = true)` 會清掉 persistence context，所以 UPDATE 之前讀到的實體不能直接拿來回傳（狀態是舊的），要在 UPDATE 之後讀。

## 10.（低）`completeOrder` 沒有 `@Transactional`、`markCompletedIfShipped` 沒有 clear

位置：`OrderServiceImpl.java:247`、`OrderRepository.markCompletedIfShipped`

問題：其他狀態轉換（`shipOrder`、`cancelOrder`…）都有 `@Transactional`，而且 UPDATE 都有 `clearAutomatically = true, flushAutomatically = true`，只有完成訂單兩者都沒有。目前 `open-in-view: false`，三次呼叫各自是獨立的 persistence context，所以結果是正確的；但如果之後有人在 `completeOrder` 加上 `@Transactional`（例如要寫 `OrderCompleted` 事件），第二次 `findOrderWithItems` 會拿到 persistence context 裡的舊實體，回傳的狀態還是 SHIPPED。

建議：和其他轉換一致——方法加 `@Transactional`，`markCompletedIfShipped` 加 `clearAutomatically = true, flushAutomatically = true`。另外確認是否需要 `OrderCompleted` 事件（notification 是否要寄「訂單完成」信），不需要的話在 event-contracts 註明。

## 11.（低）LoginAttemptService 的 Redis 往返

位置：`backend/spring-user-service/src/main/java/com/mymicroservice/user/service/impl/LoginAttemptService.java`

問題：`isBlocked` 用兩次 `GET`；`recordFailure` 執行兩次 Lua script。每次登入失敗共 4 次往返。

建議：`isBlocked` 改成一次 `multiGet`；`recordFailure` 把 script 改成一次處理兩個 key（`KEYS[1]`、`KEYS[2]`）。行為不變，現有測試應該都能通過。

## 12.（低）OutboxMetrics 每 500ms 一次 `count(*)`

位置：`backend/common/src/main/java/com/mymicroservice/common/events/OutboxMetrics.java`、`OutboxRelay.java` 的 `metrics.refreshPendingCount`

問題：每個服務每個副本每 500ms 都 `count(*)` 一次，Prometheus 一般 15～30 秒才抓一次，絕大多數結果沒被用到；積壓時（正是需要看指標的時候）`count(*)` 也最慢。

建議：改成獨立排程（例如每 15 秒），或用 `Gauge.builder(...)` 在被抓取時才查（加上短暫快取避免同時多個 scrape）。

## 13.（低）後端映像檔沒有分層，啟動沒用 CDS／AOT cache

位置：`backend/Dockerfile`

問題：執行階段直接 `COPY app.jar`，任何一行程式改動都會產生整個 fat jar 的新層（數十 MB），推送與 K8s 拉取映像都比較慢。Spring Boot 啟動在 100m CPU request 下也偏慢（startupProbe 要給比較長的時間）。

建議：

```dockerfile
# 建置階段之後
RUN java -Djarmode=tools -jar /src/spring-${SERVICE}/target/${SERVICE}-0.0.1-SNAPSHOT.jar extract --layers --launcher --destination /extracted
# 執行階段依 dependencies → spring-boot-loader → snapshot-dependencies → application 的順序 COPY
```

再評估 CDS（`-XX:SharedArchiveFile`，Spring Boot 文件有 training run 的作法）以縮短啟動時間。這項不影響功能，可以排在最後或標「暫緩」。

---

## 看過但不建議改的地方

- **前端**：路由已經 lazy-load、nginx 有 gzip 與帶雜湊檔名的長期快取、`index.html` no-cache、`.dockerignore` 完整，沒有發現需要調整的地方。
- **Gateway**：路由、限流、熔斷設定合理；Redis 失敗放行、health 不納入 Redis 的取捨也有記錄。
- **inventory 預扣**：依 SKU 排序鎖定、全有全無、以 orderId 冪等，沒有問題。
- **PaymentTimeoutJob**：分批、單筆失敗不影響其他、整批失敗會跳出，沒有無窮迴圈。
- **ProductDetailCache**：MGET 批次讀取、null 短 TTL、afterCommit 失效、不用 `sync=true` 的原因都有記錄，設計正確。

## 修正後請回報

- 每一點各自一個 commit（第 1、2、3 點優先），附上跑過的測試。
- 第 5、7 點附上 `EXPLAIN` 結果。
- 決定不做或暫緩的項目，在這份文件對應段落下方註明原因。
