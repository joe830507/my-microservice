# 既有程式碼最佳化檢討

- 日期：2026-11-12
- 範圍：
  - backend：common、order、inventory、product、payment、user、notification、gateway
  - frontend：vue-customer、vue-management（API 用戶端、狀態管理、主要頁面、打包產物、nginx）
  - 部署：Dockerfile、K8s 資源設定
  - 2026-11-12 追加：Kafka 消費失敗與死信（DLT）的處理，見第 19、20 點
- 方式：靜態閱讀，未執行測試或壓測；以下問題都附上檔案位置與建議做法，交給 Codex 修正後再審查

整體來說，程式的一致性很好。後端的冪等鍵、條件式 UPDATE、outbox／去重、熔斷、快取失效時機（afterCommit）、遠端呼叫不佔 DB 連線等，大部分都已經處理；前端的競態保護（只採用最後一次請求）、refresh 單一化、結帳冪等鍵、付款結果以後端為準，也都做得很完整。以下是還能改進的地方，依優先順序排列。

## 優先順序總覽

| # | 優先 | 範圍 | 類型 | 項目 |
|---|---|---|---|---|
| 1 | 高 | 後端 | 正確性 | 商品列表的資料庫查詢沒有 tie-breaker，翻頁會重複或漏掉商品 |
| 2 | 高 | 前端 | 功能 | 前台搜尋永遠送 `sortBy=createdAt`，Elasticsearch 的相關性排序從沒被用到 |
| 3 | 高 | 後端 | 資源 | 登入／註冊的 bcrypt 在 DB 交易內執行，佔住連線 |
| 4 | 高 | 後端 | 資料成長 | `processed_event` 從不清理，會無限成長 |
| 5 | 中 | 後端 | 效能 | Outbox relay 逐筆同步等待 ACK，單一失敗會讓整批回滾重送 |
| 6 | 中 | 後端 | 索引 | Outbox 的部分索引與實際查詢條件不符，有兩個用不到 |
| 7 | 中 | 前後端 | 效能 | 商品圖片沒有縮圖，列表直接載入原圖（最大 5MB） |
| 8 | 中 | 後端 | 資源 | EmailDispatcher 在 DB 交易內寄 SMTP |
| 9 | 中 | 後端 | 索引 | 商品前台列表缺少複合索引 |
| 10 | 低 | 前端 | 正確性 | 購物車在多個分頁之間不同步，會互相覆蓋 |
| 11 | 低 | 後端 | 效能 | 子分類展開每次都讀全部分類 |
| 12 | 低 | 前端 | 效能 | 商品詳情頁的請求是串接的，圖片可以和商品一起查 |
| 13 | 低 | 前端 | 效能 | 後台首頁一次發 9 個請求，計數用的請求也會查整頁資料 |
| 14 | 低 | 後端 | 效能 | 狀態轉換流程有重複查詢 |
| 15 | 低 | 後端 | 一致性 | `completeOrder` 沒有 `@Transactional`，也沒有 clear |
| 16 | 低 | 後端 | 效能 | LoginAttemptService 每次 2～4 次 Redis 往返 |
| 17 | 低 | 後端 | 效能 | OutboxMetrics 每 500ms 執行一次 `count(*)` |
| 18 | 低 | 部署 | 建置 | 後端映像檔沒有分層，啟動也沒用 CDS／AOT cache |
| 19 | 高 | 後端 | 正確性 | 暫時性錯誤只重試約 1.5 秒就進 DLT，逾時排程會把已付款的訂單取消、釋放已付款的庫存 |
| 20 | 中 | 後端／文件 | 維運 | DLT 只有告警，沒有處理手冊與重放工具 |

---

## 1.（高）商品列表的資料庫查詢沒有 tie-breaker

> 2026-11-13 修正註記：已加入 `id ASC`；同價商品跨頁測試確認固定順序、無重複或遺漏。

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

## 2.（高）前台搜尋永遠送 `sortBy=createdAt`，相關性排序沒被用到

> 2026-11-13 修正註記：搜尋預設 `relevance`、不送排序方向，無關鍵字退回 `latest`。query 單元測試及實際 Chrome 的搜尋參數測試通過；既有真實後端 browse e2e 也已補上斷言，但本次未啟動整套部署執行該套測試。

位置：`frontend/vue-customer/src/features/catalog/query.ts`（`SORT_OPTIONS`、`DEFAULT_PRODUCT_QUERY`、`toApiParams`）

問題：前台的排序選項只有「最新上架／價格由低到高／價格由高到低」，預設 `latest`，而 `toApiParams` 把 `latest` 轉成 `sortBy: 'createdAt'` 一定會送出。後端的設計是「有關鍵字、沒傳 sortBy 時依相關性排序」（`ProductQueryRequest` 的註解、`ProductSearchService.buildSort` 的 `relevance`），但前端從來不會不傳，所以搜尋「跑鞋」時，名稱完全符合的商品可能排在只在描述裡提到的新商品後面。階段 C 調整的 ES 權重（sku 5、name 3、brand 2…）在前台完全沒有作用。

建議：

- `ProductSort` 加 `'relevance'`，`SORT_OPTIONS` 加「最相關」，只在有關鍵字時顯示這個選項。
- 有關鍵字而網址沒帶 `sort` 時，預設為 `relevance`；沒有關鍵字時維持 `latest`。`parseProductQuery` 和 `toRouteQuery`（「和預設值相同就不放進網址」）要依有沒有 `q` 判斷預設值，避免網址出現多餘的 `sort`。
- `toApiParams` 遇到 `relevance` 時送 `sortBy: 'relevance'`（或不送 `sortBy`），不送 `sortDirection`。
- 沒有關鍵字但網址帶了 `sort=relevance`（例如清掉關鍵字）時，退回 `latest`。
- 補單元測試（`query.ts`）與一個 e2e：搜尋關鍵字時預設依相關性，請求參數正確。

## 3.（高）登入／註冊的 bcrypt 在 DB 交易內執行

> 2026-11-13 修正註記：移除類別層級交易；bcrypt 與 Redis 操作在交易外，只有使用者＋outbox 寫入及 token 簽發採短交易。保留 refresh 的 noRollbackFor 與 logout 交易，findByEmail 已有 roles EntityGraph。新增 bcrypt 交易範圍、失敗登入不寫 token，以及 cookie token 與 DB 雜湊一致的整合測試。

位置：`backend/spring-user-service/src/main/java/com/mymicroservice/user/service/impl/AuthServiceImpl.java:39`（類別層級 `@Transactional`）、`:70` `register`、`:94` `login`

問題：`JpaTransactionManager` 開始交易時就向 Hikari 拿連線，所以整個 `login` 期間（Redis 查鎖定 → 查使用者 → bcrypt 比對 → Redis 記錄失敗）都佔著一條 DB 連線；`register` 的 `passwordEncoder.encode` 也是。bcrypt 刻意設計成慢（數十到上百毫秒），預設連線池只有 10 條，登入尖峰或有人暴力猜密碼時，連線池會先被耗盡，連帶拖慢 `/users/me` 等其他 API。失敗的登入（最常見的攻擊情境）完全不需要寫 DB，卻也佔著連線。

建議：

- `login`：拿掉交易，只在「密碼正確、要寫入 refresh token」那一步開交易。做法是把 `issueTokens` 移到另一個 `@Transactional` bean（同類別內部呼叫不會經過代理），或改用 `TransactionTemplate` 包住 `issueTokens`。`findByEmail` 由 repository 自己的唯讀交易處理（需確認 roles 是否 lazy；若是，改用 `findWithRolesByEmail` 之類的 EntityGraph 查詢）。
- `register`：先 `encode` 密碼，再進交易寫入使用者與 outbox。
- 改完後補一個測試確認登入失敗時不會寫入任何 refresh token、成功時 refresh token 與回應一致。

## 4.（高）`processed_event` 從不清理

> 2026-11-13 修正註記：新增 ProcessedEventCleanup，在 order、inventory、notification 三個實際有 processed_event 的消費服務啟用；payment 只發布事件，沒有該表，故不啟用。預設保留 14 天且要求超過預設七天 Kafka 保留期；加入 BRIN 與 ShedLock，補齊 product／notification 的鎖表設定。清理邊界整合測試通過；訂單排程測試亦更新為驗證新增清理排程的 cron。人工重放超過去重窗口須另確認業務冪等性。

位置：`backend/common/src/main/java/com/mymicroservice/common/events/ProcessedEventHandler.java`；order、inventory、notification、payment 的 migration

問題：`outbox_event` 有每天清理（`OutboxRelay.purgePublishedEvents`），但每個消費者的 `processed_event` 只增不減。每筆事件一列，長期會成為各服務最大的表，主鍵索引也會一直變大，`INSERT ... ON CONFLICT` 會越來越慢。

建議：在 common 加一個清理排程（和 outbox 清理同一處），刪除 `processed_at < now() - interval 'N days'` 的資料。N 要大於 Kafka topic 的保留期（重送的事件超過保留期就不可能再出現），例如保留期 7 天就留 14 天，並做成設定值 `app.events.processed-retention`。補上 `processed_at` 的索引（或用 BRIN），並加 ShedLock 避免多個副本同時刪（見第 5 點最後一段）。

## 5.（中）Outbox relay 逐筆同步等待 ACK，單一失敗整批回滾

> 2026-11-13 修正註記：批次預設 100，可設定；先送出 futures，整批共用三秒 ACK 等待窗口，再一次 ANY(uuid[]) 標記成功項目。失敗不回滾成功事件，每個 aggregate 只取最早事件；取滿立即續跑，最多十批以免排程壟斷。purge 加 ShedLock。部分成功及同 aggregate 不越序整合測試通過。

位置：`backend/common/src/main/java/com/mymicroservice/common/events/OutboxRelay.java:48-86`

問題：

1. 每一筆都 `kafka.send(record).get(3, SECONDS)` 才處理下一筆，每輪最多 10 筆（`LIMIT 10` 寫死），每 500ms 一輪，單一副本上限大約每秒 20 筆。下單尖峰或 Kafka 稍慢時會累積延遲（付款完成到訂單變 PAID 的時間會拉長）。
2. 其中一筆失敗時 `status.setRollbackOnly()`，同一批**已經成功送出**的事件也會被回滾成未發布，下一輪重送。消費端有去重所以不會出錯，但會產生不必要的重複訊息，而且 Kafka 持續有問題時每一輪都重送同一批。
3. `purgePublishedEvents` 沒有 ShedLock，每個副本都會執行同一個 DELETE（無害但浪費）。

建議：

- 批次大小改成設定值（例如 `app.events.outbox-batch-size`，預設 100）。
- 先把這一批全部 `send` 出去，收集 future，再逐一等待；成功的標記 `published_at`，失敗的不標記即可（同一個 aggregate 的後續事件本來就不在這一批，因為查詢只取每個 aggregate 最早的一筆），**不要**整批 rollback。用一次 `UPDATE ... WHERE event_id = ANY(?)` 批次標記。
- 若一輪取滿了批次大小，可立即再跑一輪（不等 500ms），積壓時才能追上。
- `purgePublishedEvents` 加 `@SchedulerLock`（order、inventory 已有 shedlock 表；product、user、payment、notification 若沒有要先確認）。

## 6.（中）Outbox 的索引與查詢條件不符

> 2026-11-13 修正註記：六個服務新增 migration，刪除 occurred_at 舊索引，改用 (topic, aggregate_id, sequence_id) 部分索引；保留 pending sequence 索引。PostgreSQL 18.6／五萬事件的 EXPLAIN ANALYZE 確認使用新索引，約 3.001ms → 0.462ms。查詢計畫與可重現 SQL 見 [evidence](evidence/codebase-optimization-explain.txt) 及 [SQL](evidence/codebase-optimization-explain.sql)。

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

## 7.（中）商品圖片沒有縮圖，列表直接載入原圖

> 2026-11-13 修正註記：採建議方案一：後台上傳前長邊最多 1600px、WebP 品質 0.85；轉換失敗、不支援或變大均保留原檔，bitmap 在 finally 釋放。單元測試及 Chrome 真實 canvas 轉換通過。方案二的獨立 400px 縮圖暫緩：已採本次建議的第一方案，避免擴張 API、儲存與刪除契約；既有圖片不會回溯轉換。

位置：

- `frontend/vue-customer/src/components/ProductCard.vue`（列表卡片用 `primaryImageUrl`）
- `frontend/vue-management/src/features/products/images/api.ts`（`uploadProductImage` 原檔直接上傳）、`validate.ts`（`MAX_IMAGE_BYTES` 5MB）
- `backend/spring-product-service`（`storage.max-size: 5MB`，沒有產生縮圖）

問題：後台上傳的圖片原封不動存進物件儲存，前台首頁、分類頁、搜尋結果的每張卡片都載入原圖。一頁 12 張、每張可能數 MB（手機拍的照片常常 3～5MB），手機網路下列表會很慢，也浪費物件儲存的流量。`loading="lazy"` 只能延後載入，不能減少大小。

建議（擇一，建議先做第一個）：

1. **後台上傳前在瀏覽器縮小**：用 `createImageBitmap` + `OffscreenCanvas`（或 `<canvas>`）把長邊縮到例如 1600px，輸出 `image/webp`（品質 0.85），再走原本的 presigned 上傳流程。不用改後端，現有的格式與大小檢查照常有效。縮完比原檔大時用原檔。
2. **後端產生縮圖**：登記圖片時（`ProductImageServiceImpl`）另外產生一張 400px 左右的縮圖，`ProductListItem` 多一個 `primaryThumbnailUrl`，卡片用縮圖、詳情頁用原圖。效果最好但要改 API、儲存與刪除流程，可以標成「暫緩」。

## 8.（中）EmailDispatcher 在 DB 交易內寄 SMTP

> 2026-11-13 修正註記：改為每封短交易領取 SENDING、兩分鐘租約及唯一 lease_token，交易外寄 SMTP，再短交易回寫；過期租約可回收，回寫須符合 token，避免舊 worker 覆蓋新領取者。逐封領取避免後段信件排隊耗盡租約。新增 SMTP 交易外、租約到期回收及有效租約不被搶走的測試；保留固定 Message-ID 與至少一次投遞語意。

位置：`backend/spring-notification-service/src/main/java/com/mymicroservice/notification/mail/EmailDispatcher.java:57`

問題：`SELECT ... FOR UPDATE SKIP LOCKED` 之後，整批（預設 10 封）的 SMTP 寄送都在同一個交易內完成，期間佔著 DB 連線與 row lock。SMTP 慢或逾時時（每封可能數秒），一輪會鎖很久；交易也可能超過資料庫的 idle-in-transaction 限制。

建議：改成「領取 → 寄送 → 回寫」三段：

1. 短交易：`UPDATE email_message SET status='SENDING', lease_until = now() + interval '...' WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED LIMIT ?) RETURNING ...`
2. 交易外逐封寄送。
3. 每封各自用短交易回寫 SENT／重試排程。
4. 查詢條件加上 `OR (status='SENDING' AND lease_until < now())`，副本當機時租約到期可被重新領取。

這會動到 schema 與狀態機（`chk` 約束要加 `SENDING`），請一併更新測試。若評估後覺得目前流量不需要，可以標成「暫緩」並記錄原因。

## 9.（中）商品前台列表缺少複合索引

> 2026-11-13 修正註記：新增 status/created_at/id 及 category_id/status/created_at/id 複合索引，移除前綴重複的 status 單欄索引。五萬商品 EXPLAIN ANALYZE 確認兩條查詢皆不再排序：全站約 6.798ms → 0.032ms，單分類約 0.569ms → 0.044ms。詳見第 6 點 evidence，時間僅代表此測試資料分布，不是正式環境 SLA。

位置：`backend/spring-product-service/src/main/resources/db/migration/`（目前只有 `idx_product_status`、`idx_product_category` 單欄索引）

問題：前台最常見的查詢是 `WHERE status = 'ACTIVE' [AND category_id IN (...)] ORDER BY created_at DESC, id LIMIT 20`，加上 Spring Data 的 `count(*)`。單欄索引只能先篩出全部 ACTIVE 再排序，商品多時每次都要排序整個結果。

建議（配合第 1 點的 id tie-breaker）：

```sql
CREATE INDEX idx_product_status_created ON product_service.product (status, created_at DESC, id);
CREATE INDEX idx_product_category_status_created ON product_service.product (category_id, status, created_at DESC, id);
```

新增後 `idx_product_status` 可以刪掉（被複合索引的前綴取代）。資料量小時效益不明顯，可以用 `EXPLAIN ANALYZE` 搭配假資料（例如 5 萬筆）驗證後再決定。

## 10.（低）購物車在多個分頁之間不同步

> 2026-11-13 修正註記：監聽其他分頁的 localStorage storage 事件（含 clear），重新載入正規化購物車；store dispose 時移除監聽，不反寫造成循環。單元測試及 Chrome 雙分頁同步／移除測試通過。

位置：`frontend/vue-customer/src/features/cart/store.ts`

問題：購物車在 store 建立時從 localStorage 讀一次，之後每次修改都把**整份** `items` 寫回去。同時開兩個分頁時：分頁 A 加入商品 X → 分頁 B（記憶體裡還是舊的清單）改了某個商品的數量 → B 把舊清單整份寫回，X 就從購物車消失。頂部列的件數在另一個分頁也不會更新。

建議：在 store 裡監聽 `window` 的 `storage` 事件，`event.key === CART_KEY` 時用 `readStoredItems()` 重新載入 `items`（只有其他分頁寫入才會觸發，不會和自己的寫入互相循環）。補一個單元測試：派發 `StorageEvent` 後 `items` 更新。

## 11.（低）子分類展開每次都讀全部分類

> 2026-11-13 修正註記：新增獨立的全分類父子關係快取，含停用分類，避免誤用僅含啟用分類的公開樹而改變查詢語意。Redis 故障仍查 DB，既有 afterCommit 同時失效兩個 key。新增命中不查 DB、停用節點及修改後失效測試。

位置：`backend/spring-product-service/src/main/java/com/mymicroservice/product/service/impl/ProductServiceImpl.java:151` `withDescendants`

問題：前台點上層分類（`includeSubcategories=true`，前台分類頁一定會帶）時，每個請求都 `categoryRepository.findAll()`。分類樹已經有 Redis 快取（`CategoryTreeCache`），這裡沒用到。

建議：從 `CategoryTreeCache`（或 `CategoryService` 取樹的方法，快取未命中會自動回填）取得樹，再從樹找出子孫 id；快取失效時機已經是 afterCommit，一致性和現在相同。

## 12.（低）商品詳情頁的請求是串接的

> 2026-11-13 修正註記：商品與圖片請求同時發出，庫存仍待 SKU；圖片失敗獨立處理，商品失敗不套用圖片，保留 latestRequest 保護。Chrome 測試在延遲商品回應時確認圖片請求已送出。

位置：`frontend/vue-customer/src/features/catalog/ProductDetailView.vue` 的 `load`

問題：先等 `getProduct(props.id)` 回來，才同時查圖片與庫存。圖片只需要商品 id，網址上就有，不必等商品資料；目前多等一次往返，圖片是這一頁最大的內容，最晚出現。

建議：`getProductImages(props.id)` 和 `getProduct(props.id)` 同時發出；庫存需要 sku，維持在商品回來之後查。商品回 404 時忽略圖片的結果。現有的 `latestRequest` 競態保護照樣套用。

## 13.（低）後台首頁一次發 9 個請求

> 2026-11-13 修正註記：採短期方案：新增 Pinia 計數快取 30 秒，共享進行中的請求，失敗不快取，切換／登出帳號時清除並防止舊請求回填。新增 TTL、共用請求與失敗重試測試。GROUP BY 統計 API 暫緩：本次用既有 API 消除切頁重複請求，後續資料量／監控顯示瓶頸時再變更跨服務 API。待辦列表維持即時。

位置：`frontend/vue-management/src/views/HomeView.vue`

問題：7 個計數（`size: 1` 取 `totalElements`）加 2 個待辦清單，共 9 個請求。每個計數請求在後端都會跑一次「查一頁＋`count(*)`」，商品三種狀態、訂單三種狀態各自分開查。目前資料量小沒有感覺，但每次回到首頁都會重來一次。

建議：

- 短期：不用改，或把計數結果在 store 裡快取 30～60 秒，避免來回切頁時重複查詢。
- 長期（可標「暫緩」）：product-service、order-service 各提供一個 ADMIN 的統計 API（例如 `GET /api/v1/products/stats` 回 `{ DRAFT: n, ACTIVE: n, INACTIVE: n }`，一個 `GROUP BY status` 查詢），首頁改成 3 個計數請求。

## 14.（低）狀態轉換流程有重複查詢

> 2026-11-13 修正註記：markPaid、shipOrder、completeOrder 移除僅為判斷 404 的前置讀取，條件 UPDATE 後只讀一次；payment callback 保留擁有者檢查後只重讀一次並共用回傳及事件資料。cancelOrder 經檢查原本每條分支最多兩次讀取，失敗分支已直接 return，保留擁有者檢查與既有行為。

位置：

- `OrderServiceImpl.markPaid`（`:179`）：`findById` → UPDATE → `findById`（成功時又一次）＝ 3 次查詢；第一次 `findById` 只用來判斷存在，可以拿掉，改成 UPDATE 回 0 時再查一次判斷「不存在／已取消／已付款」。
- `OrderServiceImpl.cancelOrder`（`:202`）：`findVisibleOrder`（含明細）最多呼叫 3 次。
- `OrderServiceImpl.shipOrder`、`completeOrder`：`findOrderWithItems` 各 2 次，第一次只為了 404。
- `PaymentServiceImpl.mockCallback`（`:93`）：`findVisiblePayment` + 成功時 2 次 `findById`。

都不是瓶頸，但模式一致，可以順手整理：UPDATE 成功時只在最後讀一次；UPDATE 回 0 時才讀出來判斷原因。注意 `@Modifying(clearAutomatically = true)` 會清掉 persistence context，所以 UPDATE 之前讀到的實體不能直接拿來回傳（狀態是舊的），要在 UPDATE 之後讀。`cancelOrder` 的擁有者檢查要保留在 UPDATE 之前（別人的訂單不能被取消）。

## 15.（低）`completeOrder` 沒有 `@Transactional`、`markCompletedIfShipped` 沒有 clear

> 2026-11-13 修正註記：completeOrder 加 @Transactional；markCompletedIfShipped 加 clearAutomatically/flushAutomatically，確保同一交易回應讀到新狀態。由既有完成訂單的成功、冪等、404／409 整合測試覆蓋。

位置：`OrderServiceImpl.java:247`、`OrderRepository.markCompletedIfShipped`

問題：其他狀態轉換（`shipOrder`、`cancelOrder`…）都有 `@Transactional`，而且 UPDATE 都有 `clearAutomatically = true, flushAutomatically = true`，只有完成訂單兩者都沒有。目前 `open-in-view: false`，三次呼叫各自是獨立的 persistence context，所以結果是正確的；但如果之後有人在 `completeOrder` 加上 `@Transactional`（例如要寫 `OrderCompleted` 事件），第二次 `findOrderWithItems` 會拿到 persistence context 裡的舊實體，回傳的狀態還是 SHIPPED。

建議：和其他轉換一致——方法加 `@Transactional`，`markCompletedIfShipped` 加 `clearAutomatically = true, flushAutomatically = true`。另外確認是否需要 `OrderCompleted` 事件（notification 是否要寄「訂單完成」信），不需要的話在 event-contracts 註明。

## 16.（低）LoginAttemptService 的 Redis 往返

位置：`backend/spring-user-service/src/main/java/com/mymicroservice/user/service/impl/LoginAttemptService.java`

問題：`isBlocked` 用兩次 `GET`；`recordFailure` 執行兩次 Lua script。每次登入失敗共 4 次往返。

建議：`isBlocked` 改成一次 `multiGet`；`recordFailure` 把 script 改成一次處理兩個 key（`KEYS[1]`、`KEYS[2]`）。行為不變，現有測試應該都能通過。

## 17.（低）OutboxMetrics 每 500ms 一次 `count(*)`

位置：`backend/common/src/main/java/com/mymicroservice/common/events/OutboxMetrics.java`、`OutboxRelay.java` 的 `metrics.refreshPendingCount`

問題：每個服務每個副本每 500ms 都 `count(*)` 一次，Prometheus 一般 15～30 秒才抓一次，絕大多數結果沒被用到；積壓時（正是需要看指標的時候）`count(*)` 也最慢。

建議：改成獨立排程（例如每 15 秒），或用 `Gauge.builder(...)` 在被抓取時才查（加上短暫快取避免同時多個 scrape）。

## 18.（低）後端映像檔沒有分層，啟動沒用 CDS／AOT cache

位置：`backend/Dockerfile`

問題：執行階段直接 `COPY app.jar`，任何一行程式改動都會產生整個 fat jar 的新層（數十 MB），推送與 K8s 拉取映像都比較慢。Spring Boot 啟動在 100m CPU request 下也偏慢（startupProbe 要給比較長的時間）。

建議：

```dockerfile
# 建置階段之後
RUN java -Djarmode=tools -jar /src/spring-${SERVICE}/target/${SERVICE}-0.0.1-SNAPSHOT.jar extract --layers --launcher --destination /extracted
# 執行階段依 dependencies → spring-boot-loader → snapshot-dependencies → application 的順序 COPY
```

再評估 CDS（`-XX:SharedArchiveFile`，Spring Boot 文件有 training run 的作法）以縮短啟動時間。這項不影響功能，可以排在最後或標「暫緩」。

## 19.（高）暫時性錯誤只重試約 1.5 秒就進 DLT

位置：`backend/common/src/main/java/com/mymicroservice/common/events/EventsAutoConfiguration.java:17-26`；受影響的消費者：`spring-inventory-service/.../messaging/OrderEventListener.java`、`spring-order-service/.../messaging/PaymentEventListener.java`、notification 的三個 listener

問題：共用的 `DefaultErrorHandler` 用 `FixedBackOff(500L, 3L)`。除了明列不可重試的例外（`IllegalArgumentException`、`JacksonException`、`EventRejectedException`）以外，其他例外——包含 DB 連線中斷的 `DataAccessException`、5xx 的 `AppException`——重試 3 次、總共約 1.5 秒後就送進 `<topic>.DLT`。DB 重啟、failover、連線池暫時用完，通常都超過 1.5 秒，訊息內容本身沒有錯，卻被當成死信。

後果不只是「晚一點處理」，而是會被逾時排程做出相反的決定：

1. `PaymentSucceeded` 進 DLT → 訂單停在 `PENDING_PAYMENT` → `PaymentTimeoutJob`（預設 30 分鐘）把**已付款的訂單取消**，並發出 `OrderCancelled` 釋放庫存。
2. `OrderPaid` 進 DLT → 預扣停在 `RESERVED` → `ReservationExpiryJob`（預設 1 小時）把**已付款訂單的庫存還回 available**，之後可能超賣。
3. notification 的事件進 DLT → 收件人或訂單關聯缺漏，後續信件變成 `SKIPPED`。

目前只有 `events.dlt.published` 告警，要靠人在逾時前發現並重送，實際上很難趕上。

建議：

- 讓暫時性錯誤不要進 DLT，DLT 只留「重試也沒用」的訊息。比照搜尋索引的 `ProductSearchKafkaConfig`：把暫時性例外（至少 `TransientDataAccessException`、`DataAccessResourceFailureException`、`CannotCreateTransactionException`、5xx 的 `AppException`）改成指數退避、不限次數或上限拉到數分鐘以上；其他未分類的例外維持有限次數後進 DLT。
- 不限次數重試會卡住同一個 partition，這是刻意的：順序比吞吐量重要，且 consumer lag 已有告警可以看到。要確認 `max.poll.interval.ms` 與退避上限搭配不會觸發 rebalance（Spring Kafka 的 `DefaultErrorHandler` 會在退避期間 pause/seek，正常情況不會，但要寫進測試說明）。
- 若不想阻塞 partition，替代做法是 Spring Kafka 的 `@RetryableTopic`（非阻塞重試 topic）；但會打亂同一 aggregate 的事件順序，例如 `OrderPaid` 與 `OrderCancelled`，需要另外評估，**不建議**作為第一選擇。
- 測試：Testcontainers 中暫停 PostgreSQL（`docker pause` 或 Toxiproxy）超過 1.5 秒再恢復，驗證事件最終被處理、沒有進 DLT、`events.dlt.published` 未增加；另保留「格式錯誤直接進 DLT」的既有測試。
- 補一段到 `docs/architecture/event-contracts.md` 的錯誤處理說明，改寫「暫時性錯誤重試三次」的描述。

## 20.（中）DLT 只有告警，沒有處理手冊與重放工具

位置：`infra/observability/alerting.yaml`（`mm-kafka-dlt`）；目前沒有任何程式消費 `*.DLT`

問題：死信目前停在 Kafka 裡，沒有程式消費，這本身是常見做法——死信多半需要人判斷，自動重新消費容易在原 topic 與 DLT 之間無限循環。但目前缺少兩樣東西：

1. **處理手冊**：告警響了之後，沒有文件說明怎麼查看死信、怎麼從 header（`kafka_dlt-original-topic`、`kafka_dlt-original-offset`、`kafka_dlt-exception-message` 等）判斷原因、修好後怎麼處理。
2. **重放工具**：第 19 點修正前，暫時性錯誤造成的死信內容沒有問題，只需要送回原 topic；目前沒有工具，只能手動用 console producer 拼訊息。

另外，「付款成功但訂單已取消」（`order/messaging/PaymentEventListener.java:45`）是**預期中**會進 DLT 的業務衝突，需要人工退款，但沒有寫明由誰追蹤、怎麼確認已處理。

建議：

- 新增處理手冊，放在既有文件的章節裡（例如 `docs/architecture/event-contracts.md` 加一節「DLT 處理」，或 `infra/k8s/README.md`），不另開新檔。內容：如何列出死信（kind 與 Compose 各一個指令範例）、如何讀 header、依例外類型分流（格式錯誤 → 修發送端；業務衝突 → 人工處理；暫時性錯誤 → 重放）、重放的步驟與注意事項。
- 加一支重放腳本（例如 `infra/scripts/dlt-replay.sh`）：指定 DLT topic 與 offset 範圍，把訊息**原樣**（含 key，不改 `eventId`）送回原 topic。消費端有 `processed_event` 去重，重放已處理過的事件是安全的；腳本要先列出將重放的訊息並要求確認。
- 「付款成功但訂單已取消」：手冊寫明這類死信要記錄 orderId／paymentId 並人工退款，處理完才算結案；自動退款仍屬路線圖階段 F，維持「暫緩」。
- 不建議寫自動消費 DLT 再重新處理的 listener。

---

## 看過但不建議改的地方

- **前端打包與部署**：兩個前端的路由都已經 lazy-load；vue-management 的 Element Plus 按需引入；打包後 vue-customer 約 330KB、vue-management 約 930KB（未壓縮，含 Element Plus），以後台來說合理。nginx 有 gzip、帶雜湊檔名的長期快取、`index.html` no-cache、安全標頭；`.dockerignore` 完整。
- **前端 API 與狀態**：axios 錯誤統一轉成 `ApiError`；401 refresh 共用同一個 Promise、已換過 token 的請求直接重送；列表頁用遞增編號只採用最後一次請求；分類樹只載入一次；結帳的冪等 key 只在「沒收到回應」時沿用；付款回呼失敗時以後端的付款狀態為準。這些都沒有問題。
- **兩個前端有重複的檔案**：`api/errors.ts`、`api/http.ts`、`composables/useRouteQuery.ts`、`features/auth/interceptors.ts` 兩邊內容完全相同。抽成共用套件（npm workspaces）要多維護一層建置設定，兩個應用的規模還不值得；之後若改了其中一邊，記得另一邊也要同步。
- **Gateway**：路由、限流、熔斷設定合理；Redis 失敗放行、health 不納入 Redis 的取捨也有記錄。
- **inventory 預扣**：依 SKU 排序鎖定、全有全無、以 orderId 冪等，沒有問題。
- **PaymentTimeoutJob**：分批、單筆失敗不影響其他、整批失敗會跳出，沒有無窮迴圈。
- **ProductDetailCache**：MGET 批次讀取、null 短 TTL、afterCommit 失效、不用 `sync=true` 的原因都有記錄，設計正確。

## 修正後請回報

- 每一點各自一個 commit（第 1～4 點與第 19 點優先），附上跑過的測試（後端 `mvn test` 的相關模組、前端 `npm run test:unit` 與相關的 e2e）。
- 第 6、9 點附上 `EXPLAIN` 結果。
- 決定不做或暫緩的項目，在這份文件對應段落下方註明原因。
