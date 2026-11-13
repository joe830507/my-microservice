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

> 2026-11-13 文件整理：暫緩／後續項目的現況、前提與影響範圍統一見[集中清單](../deferred-items.md)；本處原始說明與歷史紀錄保留。


### 修正進度（optimize/codebase-review）

20 項均已逐項處理；採用的替代方案、暫緩範圍與原因記在各節註記。

| 議題 | 狀態 | 驗證 |
|---|---|---|
| 1 商品列表固定排序 | 完成 | 同價商品跨頁整合測試 |
| 2 前台相關性排序 | 完成 | query 單元＋Chrome 請求參數；真實部署 e2e 範圍見註記 |
| 3 登入交易範圍 | 完成 | bcrypt 交易範圍及 refresh cookie／DB 整合測試 |
| 4 去重資料清理 | 完成 | 三個實際消費服務啟用；清理邊界與 cron 驗證 |
| 5 Outbox 批次發布 | 完成 | 部分 ACK 保留及 aggregate 順序測試 |
| 6 Outbox 索引 | 完成 | 五萬筆 EXPLAIN ANALYZE 使用新索引 |
| 7 圖片縮小 | 完成（方案一） | 單元＋Chrome 真實 canvas；獨立縮圖暫緩 |
| 8 郵件交易範圍 | 完成 | SMTP／租約及多副本整合測試 |
| 9 商品列表索引 | 完成 | 全站及單分類查詢計畫 |
| 10 購物車跨分頁同步 | 完成 | 單元＋Chrome 雙分頁 |
| 11 子分類快取 | 完成 | 保留停用分類語意及 afterCommit 失效 |
| 12 詳情圖片並行 | 完成 | Chrome 延遲商品回應時圖片已發出 |
| 13 首頁計數 | 完成（短期方案） | 30 秒 TTL、請求共用及帳號切換測試；統計 API 暫緩 |
| 14 狀態轉換查詢 | 完成 | 訂單與付款整合測試 |
| 15 完成訂單交易 | 完成 | 完成、冪等及錯誤狀態整合測試 |
| 16 登入 Redis 往返 | 完成 | MGET／雙 key Lua 與門檻、故障放行測試 |
| 17 Outbox 指標 | 完成 | 獨立 15 秒排程，發布路徑不再 count |
| 18 映像分層 | 完成；CDS／AOT 暫緩 | Docker 建置及隔離資料庫實際啟動 |
| 19 暫時事件錯誤重試 | 完成 | PostgreSQL 暫停五秒後成功，DLT 無增加 |
| 20 DLT 維運與重放 | 完成 | 手冊、確認流程與真實 Kafka 二進位保留測試 |

後端 819 個測試、前端 603 個單元測試及四個 Chrome 檢查通過；兩個前端型別檢查／正式打包與 Docker 建置／啟動亦通過。詳細命令、範圍及計數見 [驗證紀錄](../evidence/codebase-optimization-validation.md)。

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

> 2026-11-13 修正註記：六個服務新增 migration，刪除 occurred_at 舊索引，改用 (topic, aggregate_id, sequence_id) 部分索引；保留 pending sequence 索引。PostgreSQL 18.6／五萬事件的 EXPLAIN ANALYZE 確認使用新索引，約 3.001ms → 0.462ms。查詢計畫與可重現 SQL 見 [evidence](../evidence/codebase-optimization-explain.txt) 及 [SQL](../evidence/codebase-optimization-explain.sql)。

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

> 2026-11-13 修正註記：新增 status/created_at/id 及 category_id/status/created_at/id 複合索引，移除前綴重複的 status 單欄索引。五萬商品 EXPLAIN ANALYZE 確認兩條查詢皆不再排序：全站約 6.798ms → 0.032ms，單分類約 0.569ms → 0.044ms。詳見第 6 點 evidence，時間僅代表此測試資料分布，不是正式環境 SLA。第一輪審查追加：十個分類的常數 IN 降冪查詢由 status 複合索引依序掃描再篩選，約 0.199ms；IN 升冪約 0.146ms，全站／單分類升冪約 0.027／0.110ms，均需 Incremental Sort 補正 id ASC。category 複合索引無法直接提供多分類全域順序；本 fixture 的表現可接受，暫不再加索引，後續資料分布／同時戳筆數增加時需重新評估。

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

> 2026-11-13 修正註記：completeOrder 加 @Transactional；markCompletedIfShipped 加 clearAutomatically/flushAutomatically，確保同一交易回應讀到新狀態。由既有完成訂單的成功、冪等、404／409 整合測試覆蓋。訂單完成目前不發 OrderCompleted（通知實作計畫既有決策），已補入 event-contracts；未來需要完成通知時再擴充。

位置：`OrderServiceImpl.java:247`、`OrderRepository.markCompletedIfShipped`

問題：其他狀態轉換（`shipOrder`、`cancelOrder`…）都有 `@Transactional`，而且 UPDATE 都有 `clearAutomatically = true, flushAutomatically = true`，只有完成訂單兩者都沒有。目前 `open-in-view: false`，三次呼叫各自是獨立的 persistence context，所以結果是正確的；但如果之後有人在 `completeOrder` 加上 `@Transactional`（例如要寫 `OrderCompleted` 事件），第二次 `findOrderWithItems` 會拿到 persistence context 裡的舊實體，回傳的狀態還是 SHIPPED。

建議：和其他轉換一致——方法加 `@Transactional`，`markCompletedIfShipped` 加 `clearAutomatically = true, flushAutomatically = true`。另外確認是否需要 `OrderCompleted` 事件（notification 是否要寄「訂單完成」信），不需要的話在 event-contracts 註明。

## 16.（低）LoginAttemptService 的 Redis 往返

> 2026-11-13 修正註記：isBlocked 改一次 MGET；recordFailure 改一個 Lua 同時遞增兩個 key，仍只在首次失敗設定 TTL。保留兩層門檻、成功僅清來源 IP 及 Redis 故障放行行為；補兩層門檻單元測試。適用目前單一 Redis 部署。

位置：`backend/spring-user-service/src/main/java/com/mymicroservice/user/service/impl/LoginAttemptService.java`

問題：`isBlocked` 用兩次 `GET`；`recordFailure` 執行兩次 Lua script。每次登入失敗共 4 次往返。

建議：`isBlocked` 改成一次 `multiGet`；`recordFailure` 把 script 改成一次處理兩個 key（`KEYS[1]`、`KEYS[2]`）。行為不變，現有測試應該都能通過。

## 17.（低）OutboxMetrics 每 500ms 一次 `count(*)`

> 2026-11-13 修正註記：pending count 移至獨立排程，預設每 15 秒，可用 app.events.outbox-metrics-interval 調整；發布輪詢與追趕批次不再 count(*)。

位置：`backend/common/src/main/java/com/mymicroservice/common/events/OutboxMetrics.java`、`OutboxRelay.java` 的 `metrics.refreshPendingCount`

問題：每個服務每個副本每 500ms 都 `count(*)` 一次，Prometheus 一般 15～30 秒才抓一次，絕大多數結果沒被用到；積壓時（正是需要看指標的時候）`count(*)` 也最慢。

建議：改成獨立排程（例如每 15 秒），或用 `Gauge.builder(...)` 在被抓取時才查（加上短暫快取避免同時多個 scrape）。

## 18.（低）後端映像檔沒有分層，啟動沒用 CDS／AOT cache

> 2026-11-13 修正註記：Dockerfile 用 tools extract 分成 dependencies、spring-boot-loader、snapshot-dependencies、application 四層，改由 JarLauncher 啟動。已成功建置 notification 測試映像並在隔離 DB 實際啟動，五個 migration 均成功。CDS／AOT cache 暫緩：需要可重現的依賴與啟動 training 流程及實際 CPU 基準，本次不增加帶環境依賴的訓練啟動。

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

> 2026-11-13 修正註記：指定的暫時性 DB／交易錯誤與 5xx AppException 採 1 秒起指數退避、上限 30 秒、無限重試；未分類錯誤仍有限次數，格式／業務錯誤直接 DLT。實際暫停 PostgreSQL 五秒後恢復的 Kafka 整合測試通過，確認多次嘗試後成功、DLT metric 與 topic offset 均為零；格式錯誤進 DLT 測試保留。退避與 max.poll.interval.ms 的搭配已寫入事件契約。

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

> 2026-11-13 修正註記：事件契約加入 Compose／kind 查看指令、headers 判讀、錯誤分流、負責人與人工退款結案要求。新增 dlt-replay.sh＋Java 21 工具，指定 partition 與 offset 半開區間，先預覽並輸入 REPLAY，保留二進位 key、value、headers、原 partition 與 eventId，逐筆 ACK、不提交 DLT offset。真實 Kafka 整合測試涵蓋取消零發送及確認後資料完全保留。自動退款／自動 DLT listener 維持原審查建議暫緩。

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

---

## 修正後審查（第一輪，2026-11-13）

範圍：`fc76e00..3b466b4`（Review #1～#20 共 20 個 commit）。方式：逐項比對上面的建議與實際程式、migration、測試及 evidence；靜態閱讀，未重跑測試。

結論：20 項都有照建議（或註記的替代方案）完成，測試也對準了各點的風險，沒有阻擋合併的問題。以下是修正本身帶進來的新問題與遺漏，依優先順序排列。

> 本輪修正：R1～R7 已逐項處理；R1、R2 各自提交，R3～R7 合併提交。七個相關模組 93 個重點測試及 common 完整 67 個測試通過（兩者有重疊），命令與範圍見[本輪驗證紀錄](../evidence/codebase-optimization-validation.md)。R3 補測並記錄限制，未額外新增索引。

| # | 優先 | 項目 |
|---|---|---|
| R1 | 中 | Outbox relay 在 Kafka metadata 取不到時，每筆 `send` 各阻塞 `max.block.ms`，一批 100 筆可達 5 分鐘 |
| R2 | 中低 | 去重清理第一次執行是一個無上限的 DELETE，會卡住唯一的排程執行緒 |
| R3 | 低 | 第 9 點的複合索引沒有涵蓋「含子分類」與「createdAt 升冪」兩種實際查詢 |
| R4 | 低 | 第 15 點「是否需要 OrderCompleted」的結論沒寫進 event-contracts |
| R5 | 低 | 第 19 點的暫時性錯誤分類可以再涵蓋 JDBC 層的 transient／recoverable 例外 |
| R6 | 低 | DLT 重放沿用舊的 record timestamp |
| R7 | 低 | 過時註解與程式風格 |

### R1.（中）Outbox relay 在 metadata 不可用時逐筆阻塞

> 修正完成：送出前即建立三秒共用截止時間；每次送出前檢查，遇到已失敗 future 或同步例外便停止送出剩餘事件，仍保留前面已成功的 ACK。正在進行的單次 send 無法中止，最多仍可超過截止時間一個 max.block.ms，但不再逐筆累積整批等待。common 模組完整測試通過（57 個，其中 OutboxRelayIntegrationTests 8 個），新增五筆事件遇 metadata TimeoutException 時僅 send 一次、全部保留待送的案例。

位置：`backend/common/src/main/java/com/mymicroservice/common/events/OutboxRelay.java`（`publishPending` 的送出迴圈）

問題：`KafkaProducer.send` 在 topic metadata 還沒取得時（例如服務啟動時 Kafka 不在、或新 topic）會**同步**阻塞到 `max.block.ms`（各服務設 3000ms），之後才回傳失敗的 future。修正前遇到第一筆失敗就 `break`，一輪最多卡 3 秒；修正後迴圈會把整批 100 筆都送完才開始等 ACK，最壞一批 100 × 3 秒 = 5 分鐘。這段期間：

- 交易與 `FOR UPDATE SKIP LOCKED` 的列鎖一直持有；
- Spring 預設排程執行緒池只有 1 條（各服務沒設定 `spring.task.scheduling.pool.size`），`refreshMetrics`、`PaymentTimeoutJob`、`ReservationExpiryJob` 等全部排在後面；
- 3 秒 ACK 窗口在送出迴圈之後才起算，對這種情況沒有作用。

建議：

- 送出迴圈也受同一個截止時間約束：截止時間在送出前就開始計算，超過就不再送剩下的事件（留給下一輪）。
- 某筆 `send` 回傳時 future 已經失敗（`future.isCompletedExceptionally()`，metadata 逾時就是這種情況），就停止送出這一批剩下的事件。已送出的照常等 ACK、成功的照常標記。
- 補一個測試：mock `KafkaTemplate.send` 回傳已失敗的 future（`org.apache.kafka.common.errors.TimeoutException`），驗證一批多筆事件時 `send` 只被呼叫一次（或在截止時間內有上限），而且沒有任何事件被標記。

### R2.（中低）去重清理的第一次 DELETE 沒有上限

> 修正完成：processed_event 與已發布 outbox 都改為每批一個短交易，以 LIMIT／SKIP LOCKED 刪除，預設每批 5000 筆（app.events.cleanup-batch-size），直到不足一批為止。六個 outbox 服務的 scheduling pool 調為 4，避免清理占用唯一排程執行緒。已檢查排程：fixed-delay 任務同一註冊不會重疊；業務 cron 使用 ShedLock 與條件式更新，SMTP 使用租約，relay 使用列鎖，沒有依賴其他任務必須依序執行。common clean test 通過，新增去重／outbox 每批三筆、逐批獨立提交與近期／待送資料保留的整合測試。七個相關模組的 93 個重點回歸亦通過，含實際 pool size、ShedLock 及 SMTP 多副本驗證，詳見本輪驗證紀錄。

位置：`backend/common/src/main/java/com/mymicroservice/common/events/ProcessedEventCleanup.java`

問題：`processed_event` 過去從沒清過，上線後第一次執行會一次刪掉所有 14 天前的資料，資料多時是一個長時間的大交易（產生大量 WAL、長時間持有被刪列的鎖）。而且它和 outbox relay 共用唯一的排程執行緒（見 R1），刪除期間這個副本的事件發布會停住。`purgePublishedEvents` 也是同樣的寫法，只是它每天都在跑，量比較小。

建議：改成分批刪除，每批一個短交易，直到刪除筆數小於批次大小為止，例如：

```sql
DELETE FROM <schema>.processed_event
WHERE event_id IN (
    SELECT event_id FROM <schema>.processed_event
    WHERE processed_at < now() - (? * interval '1 second')
    LIMIT 5000)
```

批次大小做成設定值（或固定常數即可）。`purgePublishedEvents` 可以一併改成同樣的寫法。另外可考慮設定 `spring.task.scheduling.pool.size`（例如 2～4），讓清理與發布不互相阻塞；若加了，要確認現有排程沒有依賴「單執行緒不會並行」的假設。

### R3.（低）複合索引沒有涵蓋兩種實際查詢

> 修正完成：既有 evidence SQL／計畫追加十個常數分類 IN 的降冪、升冪，以及全站／單分類升冪查詢，亦補入第 9 點註記。本次五萬筆 fixture 的 IN 降冪採 status 複合索引有序掃描後篩選，約 0.199ms；IN 升冪約 0.146ms，全站／單分類升冪約 0.027／0.110ms，升冪都需 Incremental Sort 補正 id ASC。沒有宣稱 category 複合索引提供多分類全域順序；目前量測可接受，暫不新增索引，以免增加寫入成本，資料分布或同時戳筆數改變時再評估。

位置：`backend/spring-product-service/src/main/resources/db/migration/V8__optimize_product_listing.sql`；evidence `codebase-optimization-explain.sql`

問題：

1. 前台分類頁一定帶 `includeSubcategories=true`，上層分類會產生 `category_id IN (多個 id)`。`(category_id, status, created_at DESC, id)` 對多個 category_id 無法直接給出整體的 `created_at` 順序，仍然要排序；evidence 只量了單一分類的等號條件，代表不了最常見的上層分類頁。
2. `sortDirection=asc` 時 ORDER BY 是 `created_at ASC, id ASC`，索引是 `created_at DESC, id ASC`，反向掃描得到的是 `created_at ASC, id DESC`，順序對不上，一樣要排序。

建議：不一定要再加索引（分類數少、資料量小時影響有限），但請在第 9 點的修正註記補一句這兩種情況的實際表現：在 evidence SQL 補一條 `category_id IN (…10 個…)` 的 `EXPLAIN ANALYZE`，結果可以接受就記錄下來即可。

### R4.（低）OrderCompleted 的結論沒寫進 event-contracts

> 修正完成：event-contracts 的訂單事件發送規則與第 15 點註記均已補上「目前不發 OrderCompleted，之後有完成通知需求再新增」，並連結既有通知實作計畫。未新增事件或改變訂單完成行為。

位置：`docs/architecture/event-contracts.md`；第 15 點的建議最後一句

問題：第 15 點要求確認是否需要 `OrderCompleted` 事件，不需要的話在 event-contracts 註明。`docs/implementation-plans/middleware/notification-implementation-plan.md:34` 已經有結論（不新增），但 event-contracts 和第 15 點的修正註記都沒有提到。

建議：在 event-contracts 的 order.events 說明加一句「訂單完成不發事件（見 notification 實作計畫），之後若要寄完成通知再新增」，第 15 點註記也補上。

### R5.（低）暫時性錯誤分類可以再涵蓋 JDBC 層例外

> 修正完成：抽出 isTransient／retryBackOff，新增 SQLTransientException、SQLRecoverableException、RecoverableDataAccessException，保留既有 Spring 資源／交易錯誤及 5xx AppException；類別名稱改用 import。不需 Kafka 的八個單元案例已通過，涵蓋七種暫時性錯誤的原始／ListenerExecutionFailedException 包裝形式及未知錯誤的三次有限重試；指數退避確認 1 秒起、最高 30 秒且持續重試。

位置：`backend/common/src/main/java/com/mymicroservice/common/events/EventsAutoConfiguration.java`（`setBackOffFunction`）

問題：分類只看 Spring 的 `DataAccessException` 子類別。若某次失敗的例外沒有經過 Spring 轉換（例如 Hibernate 直接拋出、或包在其他例外裡），cause 鏈上只會有 `java.sql.SQLTransientException`／`SQLRecoverableException`，這次就會被當成「未分類」。`DefaultErrorHandler` 預設在例外類型改變時重設重試狀態，所以 DB 故障期間只要有一次例外類型不同，就會改用 `FixedBackOff(500, 3)`，約 1.5 秒後進 DLT，等於第 19 點要防止的情況又回來了。

建議：cause 鏈的判斷再加上 `java.sql.SQLTransientException`、`java.sql.SQLRecoverableException`、`org.springframework.dao.RecoverableDataAccessException`。另外可以把這段判斷抽成一個有名稱的方法（例如 `isTransient(Throwable)`），並把完整類名改成 import，順便補一個不需要 Kafka 的單元測試，逐一驗證這些例外（含包在 `ListenerExecutionFailedException` 裡的情況）會拿到指數退避。

### R6.（低）DLT 重放沿用舊的 record timestamp

> 修正完成：ProducerRecord timestamp 改傳 null，手冊註明重放時使用當下時間，原時間仍保留在 DLT header（若有提供）與事件內容。真實 Kafka 整合測試改用一天前的 DLT timestamp，確認重放後 timestamp 不早於本次重放開始時間，且原時間 header、key、payload、其他 headers 仍完整保留。

位置：`infra/scripts/DltReplay.java`（`new ProducerRecord<>(..., record.timestamp(), ...)`）

問題：重放時沿用 DLT 訊息的時間戳（通常就是原事件的時間）。原 topic 依時間保留（7 天）時，接近 7 天前的事件被送回去後，只要所在的 segment 一關閉，就可能很快被保留機制刪掉；consumer 若有 lag，可能還沒讀到就消失了。lag 的時間型指標也會被這些舊時間戳拉偏。

建議：timestamp 傳 `null`，讓 broker 使用重放當下的時間；原時間戳已經在 DLT header `kafka_dlt-original-timestamp` 裡，會跟著 headers 一起保留。手冊相應補一句「重放後的 record 時間是重放時間」。測試目前沒有斷言 timestamp，不受影響。

### R7.（低）過時註解與程式風格

> 修正完成：更新鎖設定與登入交易範圍註解；合併付款成功分支並移除多餘別名；production 類別改用 import 並整理 OutboxRelay／CategoryTreeCache 的 import 順序；子分類快取註解統一中文，兩個 key 共用一小時加 0～299 隨機秒數的 TTL 方法，新增 LINKS_KEY TTL 整合斷言；移除 relay 多餘空行。付款 callback、登入及分類快取回歸均已通過。

- `common/.../scheduling/SchedulingLockAutoConfiguration.java:20` 的註解「沒設定的服務（例如沒有排程的 product-service）」：product-service 現在有排程也有 shedlock 表，請更新。
- `AuthServiceImpl.login` 上方的「會寫入 refresh token，不能是 readOnly」：方法已經沒有交易註解，改成說明「交易只包在簽發 token 那一段」。
- `PaymentServiceImpl.mockCallback`：`Payment current = payment;` 多餘，直接用 `payment`；兩個 `if (completed)` 可以合併。
- 完整類名寫在程式裡：`ProductServiceImpl` 的 `com.mymicroservice.product.cache.CategoryTreeCache`、`EventsAutoConfiguration` 的多個例外類別、`CategoryTreeCache` 的 `java.util.stream.Collectors`，改成 import。`OutboxRelay`、`CategoryTreeCache` 新增的 import 順序也請整理。
- `CategoryTreeCache.descendantIds` 的註解是英文，專案其他註解都是中文，請統一；`LINKS_KEY` 的 TTL 也可以比照 `KEY` 加上隨機秒數，避免兩個 key 同時過期。
- `OutboxRelay.send` 的 `finally` 區塊和 `publishPending` 結尾各留了一個多餘的空行。

### 修正後請回報

- R1、R2 各一個 commit，附相關模組的 `mvn test` 結果；R3～R7 可以合成一個 commit。
- R3 附上 `IN` 查詢的 `EXPLAIN ANALYZE`（追加到既有 evidence 即可）。
- 決定不改的項目，在本節對應段落下方註明原因。

---

## 修正後審查（第二輪，2026-11-13）

範圍：`3b466b4..750eb77`（R1、R2、R3～R7 三個 commit）。方式：靜態閱讀程式、測試與 evidence，未重跑測試。

結論：R1～R7 全部依建議完成，沒有新的問題，這一輪不需要再修。

| # | 結果 |
|---|---|
| R1 | 截止時間改在送出前起算，送出與等 ACK 共用；送出時就已失敗的 future 或同步例外會停止送剩下的事件。「metadata 失敗只 send 一次」與「同步 send 耗盡窗口仍保留 ACK」兩個測試對準了風險。文件也誠實註明：正在進行的單次 send 無法中止，最多仍會超過截止時間一個 `max.block.ms`。 |
| R2 | 兩種清理都改成每批一個短交易；`SKIP LOCKED` 讓 ShedLock 的 `lockAtMostFor` 到期、另一個副本也開始清理時仍然安全。排程池調為 4，並確認沒有排程依賴單執行緒依序執行。測試用 afterCommit 驗證每批各自提交（4→1→0），寫法到位。 |
| R3 | evidence 已補 IN 與升冪的查詢計畫，第 9 點註記也寫明限制；不加索引的理由合理。 |
| R4 | event-contracts 與第 15 點註記都已補上。 |
| R5 | 抽成 `retryBackOff`／`isTransient`，並補上直接拋出與 listener 包裝兩種情況的單元測試。 |
| R6 | 改用 `null` timestamp，測試同時驗證新時間與 `kafka_dlt-original-timestamp` header。 |
| R7 | 註解、import、多餘變數與空行都已處理；`LINKS_KEY` 與 `KEY` 共用加隨機秒數的 TTL。 |

合併前建議：本輪只跑了 93 個重點測試及 common 的完整 67 個測試，而排程池大小改了六個服務的執行環境，合併前請在 backend 跑一次完整的 `mvnw test`。已於 2026-11-13 在 `750eb77` 執行：830 個測試全部通過（common 67、product 194、user 164、inventory 95、order 137、payment 50、notification 12、gateway 111），BUILD SUCCESS，約 12.5 分鐘。

（測試程式裡仍有大量完整類名，例如 `org.mockito.Mockito.verify`，只影響可讀性，可以之後順手整理，不列為本輪項目。）
