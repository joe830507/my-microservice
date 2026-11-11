# 搜尋（Elasticsearch）實作計畫（階段 D）

> 建立日期：2026-11-11
> 分支：實作時開 `feature/search-phase-d`（從階段 C 合併後的 `main` 切出；階段 C 尚未合併時，從 `feature/event-driven-phase-c` 切出並做 stacked PR）
> 對應：[middleware-roadmap.md](middleware-roadmap.md)「階段 D」、[product-service-middleware-evaluation.md](../../design/product-service-middleware-evaluation.md)「2. 全文檢索」
> 前置：階段 C 完成（`product.events` 已經在發送，見[事件契約](../../architecture/event-contracts.md)）
> 狀態：**實作中**；逐步結果見 [search-implementation-record.md](search-implementation-record.md)

這份文件是給實作者（Codex 或任何人）照著做的**唯一依據**。分成兩半：前半說明「做什麼、為什麼這樣設計」，後半（[實作規範](#實作規範)）說明「怎麼做、哪些事不能做」。**開始實作前兩半都要讀完。** 遇到文件沒寫到、或發現文件有錯的地方，不要自己默默決定，先照[計畫變更](#計畫變更紀錄)的規則處理。

---

## 目標與範圍

**目標**：商店前台的關鍵字搜尋，從 SQL `LIKE '%kw%'` 換成 Elasticsearch：中文斷詞、相關性排序、資料量大時不做全表掃描。ES 掛掉時搜尋仍然可用（退回原本的 SQL，品質較差）。

**做**：

- 在本機 compose 與 kind 加一個單節點 Elasticsearch（含中文分詞）
- product-service 消費自己的 `product.events`，維護搜尋索引
- 全量重建索引的工具（也是第一次建立索引的方式）
- 商店搜尋 API（`GET /api/v1/products?keyword=...`）有關鍵字時改查 ES
- ES 無法使用時退回 SQL；指標、告警、儀表板；文件與驗收證據

**不做**（刻意排除，不要順手做）：

| 項目 | 理由 |
| ---- | ---- |
| 後台（`includeUnpublished=true`）的列表搜尋 | 後台要看到所有狀態、資料量小、要求即時一致；維持 SQL |
| 沒有關鍵字的列表（分類瀏覽、價格篩選、最新上架） | 不需要相關性，SQL 的結果穩定且已驗證；少一條行為不同的路徑 |
| 分面（facet）、搜尋建議、錯字容錯、同義詞 | 需要先有真實的搜尋資料才知道要不要；之後另開 |
| 拆出獨立的 search-service | roadmap 寫「之後可拆」；現在 product-service 消費自己的事件就夠 |
| 前端改動（新增「相關性」排序選項等） | 後端 API 向下相容即可；前端之後另外決定 |
| ES 的認證與 TLS | 和 Kafka 一樣先靠 NetworkPolicy 隔離，記為 SEC-14，對外環境前要補 |

---

## 現況（階段 C 完成後）

- 搜尋在 `ProductServiceImpl.getProductList`：`keyword` 以 `cb.like(cb.lower(name), '%kw%')` 比對**商品名稱**，沒有索引可用、沒有分詞、沒有相關性
- 前台固定只查 `ACTIVE`（`includeUnpublished=false`），後台可以查所有狀態
- 回應是 `ProductQueryResponse`（`items` 含 `primaryImageUrl`、`page`、`size`、`totalElements`、`totalPages`）；前端（vue-customer 的 `SearchView`、vue-management 的商品列表）都依賴這個格式
- `ProductQueryRequest` 的 `sortBy` 目前只有 `createdAt|price|name`，且建構子會補預設值 `createdAt`，所以「沒傳」和「傳 createdAt」無法區分
- `product.events` 的 payload 目前有 `productId`、`sku`、`name`、`description`、`price`、`categoryId`、`status`、`version`；**沒有** `brand`、`createdAt`
- 商品圖片的變動**不會**發出商品事件；價格欄位是 `NUMERIC(12,2)`
- 商品的 `version` 是 JPA `@Version`（樂觀鎖版本），每次更新遞增

---

## 已定的決定

| 項目 | 決定 | 理由 |
| ---- | ---- | ---- |
| 搜尋引擎 | **Elasticsearch**（單節點，replica 0） | 評估文件與 roadmap 的方向；OpenSearch 授權較寬鬆，但這個專案是學習用途，沿用主流的 ES，不做兩邊比較 |
| 中文分詞 | **`analysis-smartcn`**（官方外掛）搭配單字子欄位 | IK 是社群外掛，版本必須和 ES 完全一致，升級時常常落後；smartcn 每個 ES 版本都附帶。分詞品質普通，所以加一個「逐字」子欄位補召回率（見[索引設計](#索引設計)） |
| 索引誰負責 | **product-service 消費 `product.events`**，不是在業務交易裡雙寫 | 雙寫（寫 DB 又寫 ES）會不一致，這正是 Outbox 要解決的問題 |
| 索引存什麼 | 搜尋與排序需要的欄位 + **所有狀態**的商品（含 `status` 欄位） | 比 roadmap 原寫的「只放 ACTIVE」簡單：下架只是更新 `status`，不用處理 ES 的刪除與墓碑；前台查詢固定加 `status = ACTIVE` 的條件 |
| 搜尋結果怎麼組 | **ES 只回商品 id（與總數），詳細資料與主圖從 PostgreSQL 補**（回表） | 價格、主圖、狀態以 DB 為準；索引暫時過期也不會顯示錯誤的價格或已下架的商品；圖片變動不用發事件、不用更新索引 |
| 避免舊事件蓋掉新資料 | **ES 外部版本**（`version_type=external_gte`，版本 = 商品的 `version`） | 比 `processed_event` 去重更適合：索引寫入本來就是冪等的，older 的事件會被 ES 拒絕（409，視為成功略過）。這是對「消費者以 `processed_event` 去重」的**刻意例外**，要寫進事件契約 |
| 索引名稱 | 一律 `products-<yyyyMMddHHmmss>`，別名 `products` | 每次重建都建新索引再切別名，第一次建立和日後換 mapping 是同一條路徑 |
| 何時查 ES | 前台、`keyword` 非空白 | 見「不做」 |
| ES 失敗時 | 退回 SQL（原本的程式路徑，不另外複製一份） | 搜尋是使用者直接看到的讀取，退回比重試好 |
| 預設排序 | 有關鍵字且沒指定 `sortBy` → 相關性；其他情況維持 `createdAt desc` | 要讓 `ProductQueryRequest` 能分辨「沒傳」 |

**與 roadmap 的差異**（實作第一步要同步改 roadmap，見 D1）：roadmap D2 寫「只放 `ACTIVE` 的商品」，改為「索引全部狀態、查詢過濾 `ACTIVE`」；D5 寫「商店搜尋 API 改查 ES」，改為「有關鍵字時才查 ES」。

---

## 架構

```
寫入（非同步，秒級延遲）
  後台改商品 ─▶ product DB（業務資料 + outbox_event，同一個交易）
                    │ outbox relay（階段 C）
                    ▼
              Kafka product.events ──▶ ProductIndexEventListener（group: product-service-search）
                                          │ 以 ES 外部版本寫入
                                          ▼
                                   Elasticsearch  別名 products ─▶ products-<時間>

讀取（同步）
  GET /api/v1/products?keyword=運動鞋
     │ 前台 + 有關鍵字
     ▼
  ES 查詢（只取 id、總數）──失敗/熔斷/索引未就緒──▶ 退回 SQL（原本的實作）
     │
     ▼ 依序用 id 回 PostgreSQL 取商品 + 主圖，丟掉不存在或不是 ACTIVE 的
  ProductQueryResponse（格式不變）

重建（人工觸發，或索引不存在時自動）
  建新索引 ─▶ 從 DB 分批全量灌入 ─▶ 原子切換別名 ─▶ 補灌切換期間的變動 ─▶ 標記就緒
```

---

## 索引設計

### 欄位

| 欄位 | 型別 | 說明 |
| ---- | ---- | ---- |
| `id` | `keyword` | 商品 id，同時是文件 `_id` |
| `sku` | `keyword`（`lowercase` normalizer） | 完全比對，搜 SKU 要找得到 |
| `name` | `text`，analyzer `smartcn` | 主要搜尋欄位。子欄位 `name.char`：`standard` 分詞（CJK 會逐字切開），搭配 `match_phrase` 等同「包含這串字」，補 smartcn 切不準的召回率；子欄位 `name.raw`：`keyword`，供 `sortBy=name` |
| `brand` | `text`，analyzer `smartcn` | 品牌；事件目前沒有，D3 補 |
| `description` | `text`，analyzer `smartcn` | 權重最低 |
| `categoryId` | `keyword` | 篩選用；子孫分類在查詢時展開成 id 清單（沿用現有 `withDescendants`） |
| `status` | `keyword` | 前台固定過濾 `ACTIVE` |
| `price` | `scaled_float`，`scaling_factor: 100` | 對應 `NUMERIC(12,2)`；範圍篩選與排序 |
| `createdAt` | `date` | `sortBy=createdAt`；事件目前沒有，D3 補 |

- `dynamic: strict`：不認得的欄位直接失敗，不要讓 ES 自己猜型別
- `number_of_shards: 1`、`number_of_replicas: 0`（單節點）
- `_meta`：`{"schemaVersion": 1, "built": false}`。`schemaVersion` 是程式裡的常數，mapping 改了就加一；`built` 在全量灌入完成後才設成 `true`（見 D4、D5）
- mapping 與 settings 放在 `src/main/resources/search/products-index.json`，不要寫成 Java 字串

### 查詢（有關鍵字時）

- `bool.filter`：`status = ACTIVE`；有 `categoryId` 時用 `terms`（已展開子孫）或 `term`；有價格區間時用 `range`
- `bool.must`：一個 `bool.should`（`minimum_should_match: 1`），子句與權重：

  | 子句 | 權重 |
  | ---- | ---- |
  | `term` `sku`（關鍵字小寫） | 5 |
  | `match` `name`（smartcn） | 3 |
  | `match_phrase` `name.char` | 2 |
  | `match` `brand` | 2 |
  | `match` `description` | 0.5 |

  **權重只是起點**：D1 的分詞實驗與 D5 的相關性測試用具體案例驗證，不合理就調，並把最後的值與理由記進實作紀錄。
- 排序（一定要有最後的 tie-break，否則翻頁會重複或漏掉）：
  - 相關性：`_score desc, createdAt desc, id asc`
  - `createdAt`／`price`：該欄位依方向，再加 `id asc`
  - `name`：`name.raw` 依方向，再加 `id asc`（ES 依位元組排序，和 SQL 的 collation 不完全相同，可接受）
- 分頁：`from = page * size`、`size`。ES 預設只能翻到前 10000 筆（`from + size <= 10000`）：超過時回 400，新增錯誤碼 `SEARCH_RESULT_WINDOW_EXCEEDED`，**不要**調高 `max_result_window`
- 只取 `_id` 與總數（`_source: false`、`track_total_hits: true`）；總數若超過 10000 以 ES 回的精確值為準（`track_total_hits: true`）

### 回表與過期處理

1. 依 ES 回傳的 id 順序，用 `findAllById` 取商品，再用現有的 `primaryImageUrls` 補主圖
2. 資料庫找不到、或 `status != ACTIVE` 的商品（索引比 DB 舊）直接丟掉，並累加 `search.stale_hits`
3. 因此一頁的筆數可能少於 `size`、`totalElements` 可能略多；這是索引延遲期間可接受的誤差，不要為了補滿一頁再查一次
4. 用 `PageImpl(商品清單, pageable, ES 回的總數)` 組成既有的 `ProductQueryResponse.from(...)`，**不改回應格式**

### API 相容性

- 回應格式、既有參數的意義完全不變
- `ProductQueryRequest.sortBy` 新增允許值 `relevance`；建構子**不再**補 `sortBy`/`sortDirection` 的預設值（保留 `null`），預設值改在 service 決定：有關鍵字（前台）→ `relevance`、否則 → `createdAt`（SQL 路徑維持 `desc`）。`toPageable()` 在 `sortBy` 為 `null` 或 `relevance`（退回 SQL 時）都用 `createdAt desc`
- 後台（`includeUnpublished=true`）即使帶 `sortBy=relevance` 也走 SQL，`relevance` 在 SQL 路徑等同 `createdAt desc`
- Swagger（`ProductApi`）的說明要同步更新

---

## 事件契約變更

在 `product.events` 的 payload **加欄位**（相容變更，不升 `version`）：

| 欄位 | 說明 |
| ---- | ---- |
| `brand` | 品牌；沒有時給空字串（與既有 `description` 的處理一致） |
| `createdAt` | 建立時間，ISO-8601 UTC |

並且要確認（有測試）：`payload.version` 是**這次變更提交後**的 `version`，而且同一商品的事件版本嚴格遞增。已知風險：`publishProductEvent` 若在 `flush` 之前呼叫，會帶到更新前的版本，導致 ES 外部版本把較新的事件當成較舊的拒絕。若測試發現這個問題，要把事件寫入移到 `flush` 之後。

`event-contracts.md` 要同步更新：新欄位、`version` 的語意（商品的樂觀鎖版本）、以及「搜尋索引消費者以 ES 外部版本取代 `processed_event` 去重」的例外與理由。

---

## 步驟

每一步**單獨 commit**，測試通過才 commit（見[實作規範](#實作規範)）。

| Step | 內容 | 驗收 |
| ---- | ---- | ---- |
| D1 | **infra 與分詞實驗**：自訂映像（官方映像 + `analysis-smartcn`）、compose 與 K8s 加單節點 ES、NetworkPolicy、Kibana（只在本機 compose 的開發 profile）、SEC-14；同步修改 roadmap 的 D 章節與文件索引；用 `_analyze` 驗證分詞 | kind 上沒有授權的 Pod 連不到 ES；`_analyze` 對[測試詞](#d1-分詞實驗的測試詞)的結果記錄在實作紀錄且合理 |
| D2 | **索引管理**：ES client 設定、`products-index.json`、建立索引／別名／讀寫 `_meta` 的元件、商品文件的轉換與外部版本寫入（含 409 視為略過） | Testcontainers：建索引與別名、較舊版本被拒絕、相同版本可重寫、較新版本取代、`dynamic: strict` 擋掉未知欄位 |
| D3 | **事件與索引器**：補 `brand`、`createdAt`，確認版本遞增；`ProductIndexEventListener` 消費 `product.events` 寫入索引；ES 暫時性錯誤不進 DLT | 見 [D3](#d3-事件與索引器) |
| D4 | **全量重建**：從 DB 分批灌入新索引、原子切別名、補灌、`built` 標記、跨副本互斥、啟動時自動建立、內部 API | 見 [D4](#d4-全量重建) |
| D5 | **搜尋 API**：有關鍵字改查 ES、回表、`relevance` 排序、熔斷與退回 SQL | 見 [D5](#d5-搜尋-api) |
| D6 | **可觀測與文件**：指標、儀表板、告警；`microservice-architecture.md`、`event-contracts.md`、`known-issues.md`、`docs/README.md`、roadmap | 儀表板與告警能看到退回與索引延遲 |
| D7 | **kind 部署驗收與 E2E**：停 ES、恢復、重建、兩個前端的搜尋；完整 `mvnw.cmd -o clean test` | 見 [D7](#d7-部署驗收) |

### D1 分詞實驗的測試詞

在 `_analyze`（`smartcn` 與 `standard`）與實際查詢上驗證，結果（切出的詞）記錄在實作紀錄：

| 商品名稱 | 搜尋詞 | 預期 |
| -------- | ------ | ---- |
| 無線藍牙耳機 | 藍牙耳機、耳機、藍牙 | 都找得到 |
| 輕量運動鞋 | 運動鞋、鞋 | 都找得到 |
| Apple iPhone 15 手機殼 | iphone、手機殼、手機 | 大小寫不敏感；「手機」找得到「手機殼」 |
| 不鏽鋼保溫杯 | 保溫、不鏽鋼 | 都找得到 |
| 任一商品 | 它的 SKU（大小寫不同） | 找得到 |
| 任一商品 | 完全不相關的詞 | 沒有結果 |

若 smartcn 加 `name.char` 仍然達不到預期，把實驗結果寫進計畫變更紀錄再討論（例如改 IK），**不要**自己換外掛。

### D3 事件與索引器

- 位置：`product/search/`（索引相關的類別都放這個套件，不要散在 `service/` 與 `config/`）
- 監聽：`@KafkaListener(topics = "product.events", groupId = "product-service-search")`，使用 common 的 `ResilientEventConsumer.consume(record, work)`（取得 trace 與錯誤分類）
- 處理：解析 `EventEnvelope` → 轉成商品文件 → 以外部版本寫入（`ProductCreated`、`ProductUpdated`、`ProductStatusChanged` 都是同一個動作：用最新的快照覆寫）。未知的事件型別拋 `IllegalArgumentException`（進 DLT）
- **錯誤處理是這一步最重要的地方**：

  | 情況 | 處理 |
  | ---- | ---- |
  | 資料格式錯誤、未知事件型別 | 不重試，直接進 DLT（沿用階段 C） |
  | ES 回 409 版本衝突 | 視為成功（索引已經有相同或更新的版本），累加 `search.index.events{result=stale_skipped}` |
  | ES 連不上、逾時、5xx、429 | **不進 DLT**；以退避（1 秒起、倍增、上限 30 秒）無限重試，partition 暫停消費，ES 恢復後自動追上 |

  階段 C 的 `DefaultErrorHandler` 只重試 3 次就送 DLT，直接套用會讓 ES 停機 2 秒就把事件丟進 DLT、索引永久過期。因此這個 listener 要用**自己的** container factory 與 error handler。**把建立 DLT recoverer 的共用部分抽到 common（供兩邊使用），不要複製貼上**；抽出來之後階段 C 的行為與測試不能改變。
- 這個 listener 不使用 `processed_event`（見[事件契約變更](#事件契約變更)），所以**不需要**新的 migration
- 測試重點：上面三種情況各一個；ES 停掉期間送入的事件，在 ES 恢復後都有被索引（不進 DLT）；同一個商品的事件亂序送入，最終索引是最新版本

### D4 全量重建

流程（順序不能變）：

1. 取得互斥鎖（PostgreSQL advisory lock，`pg_try_advisory_lock`；拿不到就直接結束並記錄，不排隊）
2. 記錄開始時間 `T`；建立新索引 `products-<時間>`（`_meta.built = false`）
3. 以 **keyset 分頁**（`id > lastId order by id limit 500`，商品 id 是 UUIDv7 會依時間遞增）從 DB 分批讀取全部商品，用 bulk 以外部版本寫入。**逐筆檢查 bulk 回應**，有失敗的項目（409 以外）整個重建失敗，不切別名
4. 設定新索引 `_meta.built = true`
5. 用單一 `_aliases` 呼叫**原子切換**別名（移除舊的、加入新的）；沒有舊索引（第一次建立）就只加入
6. 補灌：重新讀取 `updatedAt >= T - 5 分鐘` 的商品，同樣用外部版本寫入（冪等）。原因：步驟 3 到 5 之間處理的事件寫在舊索引，切換後新索引沒有
7. 舊索引**預設保留**；內部 API 有參數 `deleteOld=true` 才刪除
8. 釋放鎖

- 觸發：
  - 啟動時，若別名不存在、或別名指向的索引 `_meta.schemaVersion` 與程式常數不同，**在背景執行緒**自動重建（多個副本靠 advisory lock 只有一個會做；啟動不能被阻塞，重建期間搜尋走 SQL）
  - 人工：`POST /internal/v1/products/search-index/rebuild`。**比照 inventory 的 `InternalReservationController` 的做法**：Gateway 不建立 `/internal/**` 路由、之後由 NetworkPolicy 限制來源
- 進度與結果寫 log 與指標（已處理筆數、耗時、失敗筆數）
- 測試重點：空資料庫、多批資料、重建期間有更新（補灌正確）、bulk 有失敗項目時不切別名、兩個執行緒同時觸發只有一個執行、schemaVersion 不同時自動重建、`deleteOld`

### D5 搜尋 API

- 路徑：保持 `ProductServiceImpl.getProductList` 為入口。條件為「前台（`!includeUnpublished`）且 `keyword` 非空白」→ `ProductSearchService`（ES）；其他一律走原本的 SQL 程式
- **退回 SQL 的條件**：ES 逾時／連線失敗／5xx／429、熔斷打開、別名不存在、`_meta.built != true`。退回時直接呼叫原本的 SQL 路徑（同一份程式），累加 `search.fallback{reason=...}`
- **不退回**的情況：ES 回 4xx（代表我們的查詢寫錯了）→ 記 error log 並回 500，不要掩蓋 bug
- 逾時與熔斷：連線逾時 1 秒、讀取逾時 3 秒；熔斷參數比照階段 B（最近 20 次、至少 10 次、失敗率 50% 打開、10 秒後放 3 個試探），失敗的定義也比照 `DownstreamCaller`（連線被拒、逾時、5xx 與 429 算失敗；4xx 不算）。**不重試**：退回 SQL 就是重試。`DownstreamCaller` 目前是針對 RestClient 的例外寫的，ES client 的例外不同：自己用 Resilience4j 的 `CircuitBreaker` 加一個小的判斷函式，**不要**為了重用而改壞 `DownstreamCaller`
- 回表與過期處理見[上方](#回表與過期處理)
- 測試重點：相關性（用 D1 的案例，名稱命中排在描述命中前面）、各種篩選與分類子孫、排序與 tie-break（翻頁不重複不遺漏）、回表丟掉過期的商品、深分頁 400、後台與無關鍵字仍走 SQL（用指標或 spy 驗證）、ES 停掉時結果來自 SQL 且格式相同、熔斷打開後不再呼叫 ES、`built=false` 時走 SQL

### D7 部署驗收

比照階段 C 的 `infra/k8s/scripts/verify-phase-c.ps1`，新增 `verify-phase-d.ps1`（從 repo 根目錄執行、自己建立暫時的 port-forward），證據放 `evidence/phase-d/`（**UTF-8**，見實作規範）：

| 驗收 | 做法 |
| ---- | ---- |
| 重建 | 全新環境啟動後自動建立索引；用內部 API 重建一次，別名切換、舊索引保留 |
| 即時更新 | 後台新增、改名、改價、下架商品，前台搜尋在幾秒內反映（輪詢，最多 10 秒） |
| ES 停機 | `kubectl scale statefulset elasticsearch --replicas=0`：前台搜尋仍然回應（來自 SQL）、`search.fallback` 增加；期間改商品；恢復後索引自動追上，不需手動重建 |
| 網路隔離 | 沒有授權的 Pod 連不到 ES:9200；product-service 連得到 |
| 前端 | vue-customer 的搜尋頁、vue-management 的商品列表都正常（用瀏覽器實際操作，不是只靠單元測試） |
| trace | Tempo 看到一個後台改商品 → outbox → Kafka → 索引的 trace（沿用階段 C 的做法） |
| 告警 | `search.fallback` 與索引延遲（沿用 consumer lag 告警，group 為 `product-service-search`）實際 firing 一次 |

---

## 失敗模式

| 情況 | 行為 |
| ---- | ---- |
| ES 停機 | 搜尋退回 SQL；事件留在 Kafka，索引器退避重試，恢復後自動追上（Kafka 保留 7 天；超過時用重建工具） |
| ES 回應很慢 | 3 秒讀取逾時 → 退回 SQL；連續失敗熔斷打開，之後直接走 SQL |
| 事件延遲 | 搜尋結果暫時舊；回表以 DB 為準，不會顯示錯誤的價格或下架的商品 |
| 事件重複、亂序 | ES 外部版本擋掉較舊的 |
| 事件進 DLT（資料格式錯誤） | 該商品的索引停在舊版本；用重建工具修復，並檢查 DLT 告警 |
| 索引損毀或 mapping 改版 | 重建；重建期間搜尋走 SQL |
| 兩個副本同時重建 | advisory lock，只有一個會執行 |
| ES 磁碟水位過高 | ES 會把索引設成唯讀：本機與 kind 關閉磁碟水位檢查（見 D1 注意事項），避免開發環境莫名失敗 |

---

## 可觀測性

指標名稱沿用專案的點號風格：

| 指標 | tag | 說明 |
| ---- | ---- | ---- |
| `search.requests` | `engine`（`es`／`sql`） | 前台關鍵字搜尋走哪個引擎 |
| `search.fallback` | `reason`（`timeout`／`breaker_open`／`index_not_ready`／`error`） | 退回 SQL 的次數與原因 |
| `search.es.duration` | — | ES 查詢耗時（Timer） |
| `search.stale_hits` | — | 回表時被丟掉的過期結果 |
| `search.index.events` | `result`（`indexed`／`stale_skipped`／`failed`） | 索引器處理結果 |
| `search.rebuild.duration`、`search.rebuild.documents` | — | 重建 |

- 儀表板：**改 `infra/observability/generate_dashboard.py` 再重新產生**，不要手改產生出來的 JSON；新增「搜尋」一列（引擎比例、退回原因、ES 耗時、索引事件）
- 告警：`search.fallback` 持續 5 分鐘大於 0（warning）；索引延遲沿用 consumer lag 告警（group `product-service-search`）；熔斷打開沿用既有的熔斷告警（確認 ES 的熔斷器有被涵蓋）
- trace：索引器透過 `ResilientEventConsumer` 取得；ES 呼叫能出現在 trace 裡即可

---

## infra 規範

- **映像**：`infra/elasticsearch/Dockerfile`（`FROM` 官方映像並 `elasticsearch-plugin install --batch analysis-smartcn`）。版本選「Spring Boot 4 的 BOM 所對應的 Elasticsearch client 主版本」，**映像與 client 主版本必須一致**；固定完整版號，不用 `latest`；選定的版本與理由寫進實作紀錄。kind 需 `kind load docker-image`
- **compose**：服務 `elasticsearch`，埠綁 `127.0.0.1:9200`；volume `elasticsearch-data`；healthcheck；`discovery.type=single-node`、`xpack.security.enabled=false`、`ES_JAVA_OPTS=-Xms512m -Xmx512m`、`cluster.routing.allocation.disk.threshold_enabled=false`。Kibana 放 `profiles: ["dev-tools"]`，只綁 `127.0.0.1:5601`，**不放進 K8s**
- **K8s**：`infra/k8s/data/elasticsearch.yaml`（StatefulSet + headless Service + PVC），加進 `kustomization.yaml`；`runAsNonRoot`、`runAsUser/Group/fsGroup: 1000`（ES 映像的使用者）；記憶體 request 1Gi／limit 1.5Gi；readiness 用 `GET /_cluster/health`（replica 0 時單節點是 green）
- **NetworkPolicy**：ingress 只允許 product-service 連 9200；product-service 的 egress 開 9200；用對照組實測（沒有授權的 Pod 連不到，見 verify 腳本）。檔案與寫法比照階段 C 的 Kafka（`network-policies.yaml`、`network-policies-egress.yaml`）
- **設定**：product-service 的 `application.yml` 加 `spring.elasticsearch.uris`（環境變數 `ELASTICSEARCH_URIS`，預設 `http://localhost:9200`）與逾時；K8s 的 service yaml 加對應的 env
- **SEC-14**：`known-issues.md` 新增「Elasticsearch 未啟用認證與 TLS」，格式比照 SEC-13
- **已知會踩的坑**（實作時確認，結果記進實作紀錄）：
  - `vm.max_map_count`：單節點模式通常會略過 bootstrap check；若仍失敗，不要在 Pod 裡開 privileged，改用 `node.store.allow_mmap=false` 並記錄
  - ES 的 Java client 內部用 Jackson 2，本專案其他地方是 Jackson 3（`tools.jackson`）：用 client 自己的 JSON mapper，不要把專案的 `ObjectMapper` 塞給它
  - Testcontainers 要用 `ImageFromDockerfile` 建出含 smartcn 的映像；測試環境離線時要先把基底映像拉好（`mvnw.cmd -o`）

---

## 測試規範

- **用真的 Elasticsearch**（Testcontainers，含 smartcn 的映像）與真的 PostgreSQL、Kafka。ES 的行為（分詞、外部版本、別名切換）用 mock 驗證沒有意義
- 可以 mock 的只有：「ES 失敗」的情境，用停掉容器或錯誤的位址模擬（參考階段 C 的 `OutboxRelayIntegrationTests` 用不可達的 bootstrap 位址的做法），而不是 mock client
- 每個步驟的「驗收」欄與上方各步驟的「測試重點」都要有對應的自動化測試，測試名稱要能看出在驗什麼
- 既有測試不能被改弱：現有的商品列表、搜尋、可見性測試（例如 `ProductVisibilityIntegrationTests`、`ProductListSubcategoryIntegrationTests`）要全部維持通過；改動它們的斷言要在實作紀錄說明理由
- 開發時只跑改到的模組（`mvnw.cmd -o -pl spring-product-service -am test`）；**commit 前**（尤其是 D5、D7）跑完整 reactor（`mvnw.cmd -o clean test`），結果的時間、耗時與模組數寫進實作紀錄

---

## 實作規範

這一節是整份文件最重要的部分之一。上一階段（C）的審查發現了一批「功能對、但做法和專案慣例不合」的問題，這次要避免。

### 流程

1. **一次只做一個 Step**。做完、測試通過、更新文件進度後 **commit**，才開始下一個 Step。**不要**一口氣做完整個階段再一次提交（階段 C 因此無法乾淨拆 commit，最後得從交錯的變更裡切開）
2. Commit 訊息格式：`Phase D<N>: <英文簡述>`，例如 `Phase D3: index product events with external versioning`。訊息本文簡述重點，結尾加上專案要求的 `Co-Authored-By` 行（與歷史 commit 一致）。**不要**使用 `fix(events):` 這類 conventional commit 格式
3. 每個 commit 只包含該步驟的檔案。**不要**順手修改無關的檔案（例如 `.vscode/launch.json` 是使用者本機的設定，永遠不要提交）
4. 不 push、不開 PR、不改 `main`，這些由使用者決定
5. 開始前先讀：[middleware-roadmap.md](middleware-roadmap.md)、[event-implementation-plan.md](event-implementation-plan.md)、[event-contracts.md](../../architecture/event-contracts.md)、[resilience-implementation-plan.md](resilience-implementation-plan.md)（熔斷規則），以及 product-service 現有的程式與測試風格
6. 完成後在實作紀錄（`search-implementation-record.md`，見下）最後列出「**最不確定、希望審查者重點看的地方**」

### 實作紀錄與文件

- 本文件是**計畫**，不要在裡面記實作結果。結果寫在新檔 `docs/implementation-plans/middleware/search-implementation-record.md`，格式比照 [resilience-implementation-plan.md](resilience-implementation-plan.md)：標頭（日期、分支、對應、狀態）→ 內容表 → 規則說明 → 驗證結果 → 遇到的問題 → 已知限制
- **所有文件用繁體中文**（程式識別字、指令、設定鍵名除外）。階段 C 的 `event-contracts.md` 一度是英文，被退回
- 驗證結果要有**可重現的證據**：指令、時間、數字。寫「測試通過」要附指令與結果；沒有親自驗證的項目標「待驗證」，不要寫成已完成。部署驗收的證據放 `evidence/phase-d/`
- **證據檔一律存成 UTF-8（無 BOM）**。Windows PowerShell 的 `>`、`Out-File` 預設會產生 UTF-16，git 會當成二進位而無法閱讀；請用 `Out-File -Encoding utf8` 後去掉 BOM，或在寫檔時指定無 BOM 的 UTF-8
- 文件互相引用的地方要同步：每個 Step 完成時更新本計畫的進度、`docs/README.md` 的索引（**一個檔案只列一次**，階段 C 曾經重複列出）、roadmap 的狀態。不要留下過時的敘述（例如「尚未部署」「進行中」在完成後還在）
- 新的安全限制記到 `docs/security/known-issues.md`（編號接續 SEC-13）

### 程式風格（跟著現有程式，不要自創）

- **註解用繁體中文，寫「為什麼」，不要翻譯程式碼在做什麼**。類別或方法上方用 `//` 說明設計決定與取捨，風格參考 `ProductServiceImpl`、`DownstreamCaller`
- **用 `import`，不要在程式裡寫 `java.util.Map.of(...)` 這種全名**。import 依專案現有順序整理，不要留下沒用到的 import
- **沒有用到的參數與欄位要刪掉**，不要為了「以後可能用」保留
- 套件與命名比照現有：`controller/api/XxxApi`（OpenAPI 介面）+ `XxxController`、`service/XxxService` + `service/impl/XxxServiceImpl`、DTO 放 `dto/<資源>/<動作>/` 且命名為 `XxxReq`／`XxxRes`、錯誤碼放 `ProductErrorCode`。索引相關的新類別放 `product/search/`
- 設定用 `@ConfigurationProperties` 的 record（比照 `StorageProperties`），不要散落的 `@Value`
- 共用元件放 `common`，但 **`common` 不能出現任何單一服務的業務名詞**（例如訂單、商品的錯誤碼字串）；階段 C 曾把 `ORDER_ALREADY_CANCELLED` 寫在 `common` 裡，被退回
- Lombok、Jakarta Validation、Spring 的用法比照現有程式；日誌用 `@Slf4j`，**不要記錄事件的完整 payload 或使用者資料**
- **不要加功能旗標來同時保留新舊兩條路徑**（階段 C 的 `app.events.enabled` 過渡旗標增加了大量分支，最後又花一輪移除）。退回 SQL 是**執行期的失敗退路**，不是旗標；它用的是同一份既有的 SQL 程式，不複製一份
- **不要在 listener 或請求執行緒裡 `Thread.sleep`** 做重試；用框架的 backoff
- 不引入計畫沒寫的新依賴；需要新依賴時（ES client、Testcontainers ES 模組）限制在 `spring-product-service`（測試依賴可在其 pom），並在實作紀錄寫明版本與理由

### 資料庫與 migration

- **已經存在的 migration 檔案絕對不改**（Flyway checksum；階段 C 的 V6、V8 因此留下無法修正的寫法）。需要變更就新增 migration，編號接續 product 的 `V7`
- 本階段原則上**不需要**新的 migration（索引器不使用 `processed_event`）。如果實作時發現需要，先走[計畫變更](#計畫變更紀錄)
- migration 中的 SQL 要能在 PostgreSQL 18 執行，並寫明用了哪些 18 才有的功能

### 測試

- 見[測試規範](#測試規範)。另外：**不要用 `Thread.sleep` 等時間**，用輪詢加逾時（例如 Awaitility，若專案已有；沒有就自己寫短的輪詢工具），失敗時要能看出等的是什麼
- 測試要能在「離線 Maven + 本機已有映像」的環境執行
- 測試資料不要依賴執行順序；每個測試自己準備、自己清理（尤其是 ES 索引與 Kafka topic）

### 誠實回報

- 做不到或沒做的，寫進「已知限制」或「待驗證」，不要含糊帶過
- 計畫有錯或不合理，**先停下來記錄再改**（見下節），不要悄悄偏離
- 不要為了讓測試通過而放寬斷言、跳過測試或加 `@Disabled`；真的要這樣做，在實作紀錄寫明理由與後續

---

## 驗收清單（階段 D 結案條件）

- [ ] D1 到 D7 各自單獨 commit，每個 commit 測試通過
- [ ] 完整 `mvnw.cmd -o clean test` 全模組成功（附指令、耗時、結果）
- [ ] kind 部署驗收的七項（D7 表格）都有證據檔
- [ ] 後台與無關鍵字的列表行為與階段 C 完成時一致（既有測試未被放寬）
- [ ] ES 停機時前台搜尋可用、恢復後索引自動追上
- [ ] 文件：實作紀錄、`event-contracts.md`、`microservice-architecture.md`、`known-issues.md`（SEC-14）、`docs/README.md`、roadmap 都已同步，且全部是繁體中文
- [ ] 實作紀錄最後有「最不確定、希望審查者重點看的地方」

---

## 計畫變更紀錄

計畫與現實不符時，**不要直接改程式去配合，也不要直接改上面的計畫**。在這一節新增一筆：日期、哪個 Step、發現了什麼、打算怎麼改、為什麼。然後：

- 影響範圍小、不改變[已定的決定](#已定的決定)與[API 相容性](#api-相容性)的（例如調整權重、換一個 ES 小版本）：記錄後自行處理，審查時說明
- 會改變決定、API、事件契約、或需要新依賴與新 migration 的：記錄後**停下來**，等使用者確認

（目前沒有變更。）
