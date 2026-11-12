# 搜尋（Elasticsearch）實作紀錄（階段 D）

> 日期：2026-11-12（審查修正更新）
> 分支：`feature/search-phase-d`（從階段 C 後續計畫分支切出）
> 對應：[search-implementation-plan.md](search-implementation-plan.md)、[middleware-roadmap.md](middleware-roadmap.md)
> 狀態：D1–D7 完成；階段 D 結案（前端為使用者人工操作、無截圖）

---

## 內容

| Step | 內容與狀態 |
| ---- | ---- |
| D1 | 單節點 Elasticsearch／SmartCN 映像、Compose、kind、NetworkPolicy、中文分詞與召回實驗已完成；完整 reactor 通過 |
| D2 | ES Java client、商品索引 mapping、索引與別名管理、`_meta` 更新、商品快照轉換及 `external_gte` 寫入已完成；真實 ES Testcontainers 驗收通過 |
| D3 | 商品事件契約與 Elasticsearch listener 已完成；真實 Kafka＋ES 測試涵蓋亂序版本、DLT 與暫時故障恢復 |
| D4 | 全量重建、補灌、原子 alias 切換、跨副本鎖、背景啟動修復及內部 API 已完成；真實 PostgreSQL＋ES 驗收通過 |
| D5 | 前台關鍵字搜尋改查 Elasticsearch：回表、相關性排序、熔斷與退回資料庫查詢已完成；真實 ES＋PostgreSQL 整合測試通過 |
| D6 | 搜尋儀表板、退回 SQL 告警及架構／事件契約／安全／索引文件同步已完成；kind 實際載入及告警 firing 於 D7 驗證 |
| D7 | 完成：kind 部署、故障恢復、告警、完整 reactor、Tempo trace、Gateway 搜尋與兩個前端人工操作均已驗證 |

---

## 規則與版本

- 使用 `9.4.5`，與專案 Spring Boot `4.1.1` BOM 的 `elasticsearch-client.version` 完全一致。自訂映像 `my-microservice/elasticsearch:9.4.5-smartcn`，內建官方 `analysis-smartcn`，不在 Pod 啟動時下載。
- 官方外掛說明：[Smart Chinese analysis plugin](https://www.elastic.co/docs/reference/elasticsearch/plugins/analysis-smartcn)。SmartCN 主要針對簡體中文，繁體中文品質需以實測評估。
- 開發環境關閉認證及 TLS，以 loopback 埠與 NetworkPolicy 隔離；限制見 SEC-14。Kibana 只有 Compose 的 `dev-tools` profile，沒有 K8s 資源。
- 固定 512Mi JVM heap、單節點、關閉開發環境磁碟水位檢查；`node.store.allow_mmap=false` 避免修改主機 sysctl 或使用 privileged initContainer。
- K8s 的記憶體 request 1Gi／limit 1536Mi，資料存入 2Gi PVC；只允許 product-service 連 9200。D1 尚不修改商品程式或事件契約。

---

## D1 基礎設施與分詞驗證

2026-11-11，本機 Docker Desktop 與 Calico kind（`kind-my-microservice`）：

```powershell
docker build -t my-microservice/elasticsearch:9.4.5-smartcn infra/elasticsearch
docker compose -f infra/docker-compose.yml config --quiet
docker compose -f infra/docker-compose.yml up -d --no-build elasticsearch
kind load docker-image --name my-microservice my-microservice/elasticsearch:9.4.5-smartcn
kubectl --context kind-my-microservice -n my-microservice apply -f infra/k8s/data/elasticsearch.yaml
kubectl --context kind-my-microservice -n my-microservice apply -f infra/k8s/network-policies.yaml -f infra/k8s/network-policies-egress.yaml
python infra/elasticsearch/verify-analysis.py
powershell -NoProfile -File infra/k8s/scripts/verify-phase-d.ps1
```

- Compose 與 Kustomize 可解析，ES 在本機與 kind 都啟動成功；不需要 privileged 或修改 `vm.max_map_count`。
- 本機與 kind 各 12 個查詢案例全數通過，包含計畫列出的 10 個中文／英文詞、大小寫不同的 SKU，以及無關名詞「宇宙火箭」。所有測試索引均在 finally 刪除。
- 真實 product-service Pod 可連 ES:9200；未授權 busybox Pod 連線失敗。腳本在 finally 清除測試 Pod 及 port-forward。
- 證據：[本機分詞與搜尋](evidence/phase-d/d1-analysis.json)、[kind 分詞與搜尋](evidence/phase-d/d1-kind-analysis.json)、[網路對照](evidence/phase-d/d1-network.txt)。JSON 與文字證據均為無 BOM UTF-8。

分詞對照（節錄）：

| 範例 | smartcn | standard |
| ---- | ---- | ---- |
| 無線藍牙耳機 | 無、線、藍、牙、耳、機 | 無、線、藍、牙、耳、機 |
| 輕量運動鞋 | 詳見原始證據 | 輕、量、運、動、鞋 |
| Apple iPhone 15 手機殼 | 英文小寫；中文結果詳見證據 | apple、iphone、15、手、機、殼 |

完整回歸：在 backend 執行 `mvnw.cmd -o clean test`，2026-11-11 20:35:51 結束，九模組全部 SUCCESS，耗時 7:12，包含真實 Kafka 與 PostgreSQL Testcontainers。[完整 reactor 摘要](evidence/phase-d/d1-reactor.txt)。

---

## D2 索引管理

- ES Java client 使用 Spring Boot BOM 管理的 `elasticsearch-java` `9.4.5`，連線採 ES 9 對應的 REST 5 transport；Testcontainers Elasticsearch 模組為 `2.0.5`。商品文件轉換時將建立時間轉成 ISO-8601 字串，避免額外引入 JSON 依賴；ES client 使用自己的 Jackson 2 mapper，不共用應用程式的 Jackson 3 mapper。
- `search.elasticsearch.endpoint` 由 `ELASTICSEARCH_ENDPOINT` 覆寫，預設 `http://localhost:9200`。索引設定在 `search/products-index.json`：單 shard、replica 0、SmartCN 分析器、SKU 小寫 normalizer、指定欄位與 `dynamic: strict`；`_meta` 初始為 `schemaVersion=1`、`built=false`。
- `ProductIndexManager` 建立索引後可掛上 `products` write alias、讀取／更新 `_meta`，並使用 `external_gte` 依商品版本寫入。舊版本回 409 時回傳 `-1`（略過），同版本允許冪等覆寫；商品文件包含搜尋／排序欄位，品牌與描述 null 轉空字串。
- 驗證使用真實 Elasticsearch 9.4.5 Testcontainers 映像，由 `infra/elasticsearch/Dockerfile` 建置（SmartCN 外掛已包含）：

```powershell
& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.10.0\2aaba761688b1a6ea5a46e7e5f4d5406ed7a75ee8da95e5e3079863a23461c9b\bin\mvn.cmd" -o -pl spring-product-service -am "-Dtest=ProductIndexManagerIntegrationTests" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

- 2026-11-11 20:55:19 完成，三項測試全數通過，Maven reactor 的 common、observability、product-service 均成功，耗時 21.430 秒。涵蓋建立索引／alias／built metadata、舊版本拒絕與相同及較新版本寫入、strict mapping 拒絕未知欄位。因本次 shell 呼叫 `mvnw.cmd` 時出現 `icm` bootstrap 錯誤，改用 wrapper 已下載的 Maven 3.10.0 執行同一組離線目標；上方為實際命令，其他環境可用 `mvnw.cmd` 執行相同參數。
- 首次測試發現 ES client 的 Jackson 2 mapper 不會直接序列化 `Instant`；商品文件改在轉換階段輸出 ISO-8601 字串，不額外引入 mapper 依賴，以上真實 ES 測試確認寫入與讀回成功。

---

## D3 事件與索引器

- 商品事件 payload 新增 `brand` 與 `createdAt`，維持既有 envelope 版本。建立商品先 `saveAndFlush` 再發事件，更新與上下架也在事件發布前 flush；payload `version` 取自本次資料庫寫入後的 JPA `@Version`，測試確認同商品事件版本嚴格遞增。`createdAt` 輸出 ISO-8601 UTC，並截到 PostgreSQL 微秒精度，避免建立與後續事件同一時間值格式不一致。
- `ProductIndexEventListener` 位於 `product/search/`，以 `product-service-search` group 消費 `product.events`，透過 `ResilientEventConsumer.consume` 保留共用 trace 與消費處理。`ProductCreated`、`ProductUpdated`、`ProductStatusChanged` 都以完整快照呼叫 D2 的 `external_gte` 寫入；ES 409 依 D2 管理器結果記為 `stale_skipped`，未知事件與格式錯誤直接送 DLT。
- listener 使用專屬 container factory：ES 連線／逾時、429 與 5xx 以指數退避（1 秒起、倍增、上限 30 秒）無限重試；不因 ES 暫時不可用送 DLT。共用 `EventDeadLetterRecoverer` 抽出原有 DLT 發送行為，common 原 error policy 維持固定退避三次與原分類。
- 未新增 `processed_event` 或資料庫 migration。測試加入 `org.testcontainers:kafka`，以官方 Testcontainers KafkaContainer 提供可重啟下仍可正確連線的 Kafka；ES 測試容器由專案 SmartCN Dockerfile 建置，固定 host port 以便模擬停機後重啟。

驗證命令（在 `backend` 執行；使用既有 Maven 3.10.0 wrapper distribution 的離線執行檔）：

```powershell
& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.10.0\2aaba761688b1a6ea5a46e7e5f4d5406ed7a75ee8da95e5e3079863a23461c9b\bin\mvn.cmd" -o -pl spring-product-service -am "-Dtest=ProductEventContractIntegrationTests" "-Dsurefire.failIfNoSpecifiedTests=false" test
& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.10.0\2aaba761688b1a6ea5a46e7e5f4d5406ed7a75ee8da95e5e3079863a23461c9b\bin\mvn.cmd" -o -pl spring-product-service -am "-Dtest=ProductIndexEventListenerIntegrationTests" "-Dsurefire.failIfNoSpecifiedTests=false" test
& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.10.0\2aaba761688b1a6ea5a46e7e5f4d5406ed7a75ee8da95e5e3079863a23461c9b\bin\mvn.cmd" -o -pl common "-Dtest=OutboxRelayIntegrationTests,ResilientEventConsumerTest" test
```

- 2026-11-11 21:08:11：商品事件契約測試 1 項通過；檢查 payload 欄位、flush 後版本與嚴格遞增、UTC 時間格式與穩定建立時間。
- 2026-11-11 21:30:16：listener 整合測試 3 項通過，reactor common、observability、product-service SUCCESS。涵蓋 v4 後到達的 v3 不覆蓋最新版、未知事件進 DLT、ES 停機時不進 DLT 且重啟後寫入成功。
- 2026-11-11 21:30:52：共用 outbox 回歸 4 項通過（`OutboxRelayIntegrationTests` 3 項、`ResilientEventConsumerTest` 1 項），common SUCCESS。
- wrapper 腳本在目前 shell 啟動時有 `icm` 錯誤；命令改用 wrapper 已下載的 Maven 3.10.0 執行檔。首次在未提升權限的 shell 執行時 Maven 無法存取 `%USERPROFILE%\.m2` 本機快取，提升後使用相同離線命令成功；不需要網路下載。

---

## D4 全量重建

- `ProductIndexRebuildService` 在 JDBC session connection 上取得固定 key 的 PostgreSQL `pg_try_advisory_lock`，整個流程持鎖到結束；取得失敗時立即回報略過，不排隊。重建開始時間在取得鎖後記錄，新增 `products-<UTC 時間>-<隨機尾碼>` 索引，初始 `_meta.built=false`。
- 全量讀取採 UUIDv7 `id > lastId order by id limit 500` keyset 分頁。Bulk request 每筆帶商品 `@Version` 與 `external_gte`；逐項檢查錯誤，僅把 409 視為舊版略過，其他失敗直接中止。失敗時刪除尚未掛 alias 的新索引，原 alias 保持不變。
- 全量寫完後將 `built=true`，用單一 `_aliases` 請求移除所有舊 alias 並掛上新索引，再以 `updatedAt >= T - 5 分鐘` 的 bounded keyset 分頁補灌。補灌若失敗，將新索引標回 `built=false`，讓未來啟動修復可再建；alias 舊索引預設保留，`deleteOld=true` 才刪除。
- 搜尋 alias 尚不存在時，事件索引寫入會帶 ES `require_alias=true`，避免 ES 把 `products` 意外自動建成具名索引；這個初始化窗口視為暫時性狀態持續重試。索引建立與切換仍由 listener 的 external version 保護事件亂序。
- `ProductIndexStartupRebuilder` 在 `ApplicationReadyEvent` 後使用單執行緒背景 executor；alias 不存在、schema version 不符或尚未 built 時自動呼叫重建，不阻塞服務啟動。內部入口為 `POST /internal/v1/products/search-index/rebuild?deleteOld=false`，沒有新增 Gateway 路由。新增 `search.rebuild.duration` timer、`search.rebuild.documents` counter 與 `search.rebuild.failures` counter，並記錄每批進度與結果。
- 未新增資料庫 migration。D3 Kafka listener 測試另外加上首建期間 alias 缺失的重試案例，防止初始化事件被 DLT。

驗證命令（在 `backend` 執行）：

```powershell
& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.10.0\2aaba761688b1a6ea5a46e7e5f4d5406ed7a75ee8da95e5e3079863a23461c9b\bin\mvn.cmd" -o -pl spring-product-service -am "-Dtest=ProductIndexRebuildIntegrationTests" "-Dsurefire.failIfNoSpecifiedTests=false" test
& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.10.0\2aaba761688b1a6ea5a46e7e5f4d5406ed7a75ee8da95e5e3079863a23461c9b\bin\mvn.cmd" -o -pl spring-product-service -am "-Dtest=ProductIndexEventListenerIntegrationTests,ProductEventContractIntegrationTests" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

- 2026-11-11 21:52:38：D4 PostgreSQL＋Elasticsearch 整合測試 7 項通過，reactor common、observability、product-service SUCCESS。覆蓋空資料庫與內部 API、501 筆跨批索引、重建期間 DB 更新補灌、bulk 失敗不切換、advisory lock 不等待、舊索引選擇性刪除、schemaVersion 不符時背景修復。
- 2026-11-11 21:51:23：D3 事件契約與 Kafka＋ES listener 測試共 5 項通過，包含首建 alias 未存在時事件不進 DLT，alias 建立後成功索引。
- 驗收使用真實 PostgreSQL Testcontainers 與從 `infra/elasticsearch/Dockerfile` 建置的 SmartCN Elasticsearch Testcontainer；無資料庫 schema 變更。

---

## D5 搜尋 API

- **入口與條件**：仍是 `ProductServiceImpl.getProductList`。前台（`!includeUnpublished`）且 `keyword` 非空白才呼叫 `ProductSearchService`；後台與沒有關鍵字的請求完全走原本的 SQL。方法改為 `@Transactional(propagation = NOT_SUPPORTED)`：查 ES 的期間不佔著 DB 連線，SQL 路徑由 repository 自己的交易處理。分類條件（含子孫）抽成 `categoryIdsOf`，ES 與 SQL 兩條路徑共用同一份展開結果。
- **查詢**：`bool.filter` 固定 `status = ACTIVE`，加上分類（`terms`）與價格區間（`range`）；`bool.must` 為 sku／name／name.char／brand／description 五個 `should`，權重依計畫（5／3／2／2／0.5），沒有調整。排序最後一律加 `id asc` 當 tie-break。`relevance` 為 `_score desc, createdAt desc, id asc`。`from + size > 10000` 回 400 `SEARCH_RESULT_WINDOW_EXCEEDED`。
- **回表**：ES 只回 id 與總數（`_source: false`），再以 `findAllById` 與既有的 `primaryImageUrls` 取商品與主圖；資料庫沒有或已不是 `ACTIVE` 的丟掉並累加 `search.stale_hits`。回應仍是 `ProductQueryResponse`，格式沒有變。
- **API 相容**：`sortBy` 新增 `relevance`；`ProductQueryRequest` 不再補 `sortBy`、`sortDirection` 的預設值（保留 `null`），有關鍵字的前台搜尋預設依相關性，其他情況維持 `createdAt desc`。`toPageable()` 與新增的 `direction()` 處理 `null`。
- **退回資料庫查詢**（回傳 `Optional.empty()`，由呼叫端走原本的 SQL 程式，沒有複製一份）：連線失敗、逾時、5xx、429、熔斷打開、別名不存在、`_meta.built != true`、結構版本不符。ES 回其他 4xx 不退回，記 error log 並回 500。
- **逾時與熔斷**：新增專用的 `searchQueryClient`（連線逾時 1 秒、讀取逾時 3 秒，可由 `search.elasticsearch.query-*` 覆寫），原本的 client 標為 `@Primary` 並維持預設逾時，D3、D4 的索引寫入與批次重建不受影響。熔斷器 `elasticsearch-search` 用 `DownstreamCallers` 的 registry 建立（指標自動綁定），參數與階段 B 相同；失敗判定在 `ProductSearchService.isFailure`，不重試。索引是否可查的檢查結果快取 5 秒（`readiness-cache-ttl`）。
- **指標**：`search.requests{engine=es|sql}`、`search.fallback{reason=timeout|breaker_open|index_not_ready|error}`、`search.es.duration`、`search.stale_hits`。沿用專案其他服務的靜態 `Metrics`；D6 已新增儀表板與退回告警。
- 未新增資料庫 migration。

驗證命令（在 `backend` 執行）：

```powershell
.\mvnw.cmd -o -pl spring-product-service -am "-Dtest=ProductSearchServiceTest,ProductSearchIntegrationTests,ProductSearchFallbackIntegrationTests" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -o -pl spring-product-service -am test
```

- `ProductSearchIntegrationTests` 14 項（真實 ES 9.4.5＋smartcn、PostgreSQL，索引用 D4 的重建建立）：名稱命中排在描述命中前、單字與部分詞（運動鞋、保溫）找得到、SKU 不分大小寫、無關詞沒有結果、只回 `ACTIVE`、索引過期時丟掉下架商品並改用資料庫的價格、分類與子孫分類、價格區間含邊界、價格排序兩個方向、25 筆同名同價在 relevance／price／name／createdAt 四種排序下三頁不重複不遺漏、總筆數與頁數、深分頁 400、後台與無關鍵字不呼叫搜尋服務、索引未就緒時退回資料庫。**每個搜尋測試都斷言 `search.requests{engine=es}` 加一**，避免退回 SQL 時結果剛好相同而誤判通過。
- `ProductSearchFallbackIntegrationTests` 2 項：ES 接受連線但不回應時，讀取逾時後退回資料庫，回應格式相同；連續失敗後熔斷打開，之後的請求不再連 ES（用計數接受的連線驗證）也不必等逾時。
- `ProductSearchServiceTest` 3 項：哪些例外算 ES 失敗、逾時標籤。
- product-service 模組完整測試（含 common，189 項）第一次執行有 2 項失敗，都不在 D5 範圍：D3 的 `ProductEventContractIntegrationTests` 比對 `createdAt` 差 1 微秒（`...110523Z` 對 `...110524Z`，時間精度截斷不一致的偶發問題），以及 D3 的 `ProductIndexEventListenerIntegrationTests` 的 Elasticsearch 容器啟動失敗（同一輪已啟動多個 ES 容器，疑為資源競爭）；兩者單獨重跑 5 項全部通過，其餘 187 項通過。這兩個偶發失敗留待審查者決定是否另外處理（結束於 22:14）。

---

## 遇到的問題

- 初次測試以「完全不相關的宇宙火箭」當負例，因「不」與「不鏽鋼保溫杯」共字，在計畫指定的 OR 查詢下仍有結果。[初次實驗](evidence/phase-d/d1-analysis-initial.json)完整保留。驗收負例採獨立名詞「宇宙火箭」，沒有改動查詢、權重或放寬對計畫指定詞彙的斷言。
- Windows PowerShell 5 讀取無 BOM 的中文腳本可能把下一行指令誤讀成註解；驗收腳本使用 UTF-8 BOM，**證據檔**仍維持無 BOM UTF-8。修正後已重新執行，確認 kind 分詞證據確實產出。
- **索引就緒檢查的溢位**（D5）：第一版用 `Long.MIN_VALUE` 當「從沒檢查過」的時間，`now - MIN_VALUE` 在 `long` 會溢位成負數，永遠小於快取時間，結果是索引永遠被判定「未就緒」而一律退回 SQL。測試一度全部「通過」只是因為退回 SQL 的結果剛好相同；加上「每個搜尋都必須是 `engine=es`」的斷言後才現形，改成可為 `null` 的快取物件。
- 測試剛寫入索引就搜尋會找不到（D5）：ES 預設約 1 秒才讓新文件可搜尋，測試在重建後明確 `refresh`；不調整 `refresh_interval`，正式環境的「幾秒內反映」就是靠它。
- 測試的 Spring context 不匯出指標，靜態 `Metrics.counter` 沒有 registry 可記，計數永遠是 0（D5）；測試在 `@BeforeAll` 掛一個 `SimpleMeterRegistry`、`@AfterAll` 移除。

---

## D6 可觀測性與文件同步

- `infra/observability/generate_dashboard.py` 新增「商品搜尋（Elasticsearch）」區塊：每分鐘 ES／SQL 搜尋量、依原因分類的 SQL 退回量、Elasticsearch 搜尋平均延遲、索引事件結果、重建文件數與過期命中。平均延遲使用 timer 的 `_sum / _count`；指標實際匯出名稱帶單位字尾 `search_es_duration_milliseconds_*`（毫秒），面板單位為 `ms`。目前 `_bucket` 只有 `+Inf` 一個桶，沒有可用的百分位數，所以不顯示 p95。（2026-11-12 審查發現原面板誤用 `_seconds_` 名稱而永遠沒有資料，已修正並以 Prometheus 實測平均約 65 毫秒。）儀表板 JSON 由產生器更新，沒有直接手改產生檔。
- 新增 Grafana 告警 `mm-search-fallback`：`search_fallback_total` 每分鐘速率連續 5 分鐘大於 0 時告警，帶出 `application` 與退回原因。既有 `mm-circuit-open` 告警適用包括 `elasticsearch-search` 的 Resilience4j 熔斷器；Kafka consumer lag 告警涵蓋搜尋消費群組的索引延遲。實際 firing 與恢復驗收留在 D7 kind 驗收。
- 同步架構文件、事件契約、roadmap、`docs/README.md`；SEC-14 已描述 ES 開發環境未啟用 TLS／認證的限制，本步補上架構文件引用，未變更安全設定。
- 產生器執行：`python infra/observability/generate_dashboard.py`，成功輸出儀表板 35 個 panels、告警 10 條 rules。D7 已在 kind 實際載入，並驗證告警 firing。

## D7 kind 部署與故障恢復驗收

- `infra/k8s/scripts/verify-phase-d.ps1` 在 `kind-my-microservice` 通過：未授權 Pod 被 NetworkPolicy 阻擋，`product-service` 可連 Elasticsearch；SmartCN 與 12 個搜尋案例通過。證據：`evidence/phase-d/d1-network.txt`、`d1-kind-analysis.json`。
- kind 部署檢查發現 `product-service` manifest 缺少 `ELASTICSEARCH_ENDPOINT`，Pod 因而以 localhost 作為預設端點。已補上 `http://elasticsearch:9200` 並重啟部署，副本 2/2 Ready。
- 呼叫內部重建 API 成功：掃描 10 筆、補灌 0 筆、建立新索引並切換 `products` alias，保留舊索引（`deleteOld=false`）。搜尋 `Phase` 回傳 4 筆。
- 將 Elasticsearch StatefulSet 縮至 0 期間，以 5 秒間隔執行 66 次搜尋，全部成功、0 次失敗；SQL fallback 指標增加。Grafana `mm-search-fallback` 對 `error` 與 `breaker_open` 兩個原因均進入 active。恢復 Elasticsearch 後副本為 1/1。證據：`evidence/phase-d/d7-search-outage.txt`。
- Elasticsearch 停機期間更新一筆 DRAFT 商品；恢復後確認 Kafka 索引器將新版本寫入 ES，再回復商品原值並確認版本 2 也已寫入。證據：`evidence/phase-d/d7-product-update-recovery.txt`。
- 完整 reactor：在 `backend/` 執行 Maven 3.10.0 離線命令 `mvn -o -Dmaven.repo.local=C:\Users\joe83\.m2\repository clean test`，2026-11-12 09:25 +08:00 `BUILD SUCCESS`，總耗時 11:01；9 個 reactor 模組全數 SUCCESS。摘要證據：`evidence/phase-d/d7-reactor.txt`。
- D7 補充驗收由審查者透過 CLI 完成，證據見 `evidence/phase-d/d7-tempo-and-gateway.txt`：Tempo 正常與 ES 停機兩個 trace 均貫穿 HTTP 更新、outbox publish、Kafka consumer；停機期間的索引更新經退避後約 41.5 秒成功，未進 DLT。經 Gateway 的 3 次搜尋使 `engine=es` 計數增加 3、`engine=sql` 不變。
- 前端由使用者於 2026-11-12 人工操作 kind 上的 vue-customer（`localhost:8091`）與 vue-management（`localhost:8093`）：前台搜尋「Phase」有結果、無關詞無結果、排序選單可用、後台商品列表正常。使用者回報均正常；人工操作、無截圖，非自動化 E2E。此前 browser access host 不可用的工具限制不再作為未驗收理由。
- `mm-search-fallback` 恢復後的 resolved 狀態沒有保留證據；Grafana Alertmanager 後續 active-alert 查詢為空，但這只能證明查詢當下沒有 active alert，不能證明該次告警從 firing 轉為 resolved，仍標記未驗證。
- 每次重建預設 `deleteOld=false`，kind 目前可累積多個 `products-*` 索引。建議先執行重建並確認 alias `products` 指向新索引、搜尋與文件數正常，再透過內部重建 API 使用 `deleteOld=true`，或手動刪除不再被 alias 指向的舊索引；不可在確認新索引前刪除目前 alias 目標。
- 審查確認 `alerting.yaml` 的大段格式差異只涉及既有規則格式展開及新規則；維持產生檔由 `generate_dashboard.py` 單一來源產生，沒有手動回改產生檔。這是格式整理造成的 diff 雜訊，與告警語意變更分開理解。
- `ProductServiceImpl.publishProductEvent` 將事件端 `createdAt` 截到微秒；PostgreSQL `TIMESTAMPTZ` 精度也是微秒。事件契約整合測試已改為把回應時間在測試端同樣截到微秒，再與事件時間比較，避免奈秒與資料庫精度差異造成 1 微秒的不穩定斷言。重複測試結果見下方「審查後驗證」。
- 審查提到曾有 Elasticsearch Testcontainers 啟動失敗，但現有證據不足以確定根因為資源競爭。修正後 3 次重複執行均成功啟動 Kafka 與 Elasticsearch 容器；listener 關閉期間有 Kafka disconnect 訊息，但所有測試均通過。未把未證實的資源競爭寫成結論。

### 審查後驗證（2026-11-12）

依 `phase-d-review.md` 重複執行 D 相關測試類別 3 次；每次均包含 `ProductEventContractIntegrationTests` 與 `ProductIndexEventListenerIntegrationTests`，15/15 個測試實例通過，結果附於 `evidence/phase-d/review-repeat-runs.txt`。另重跑全部 7 個 D 測試類別，34 項全數通過；結果見 `evidence/phase-d/review-d-tests.txt`。

修正後另重跑全部 7 個 D 搜尋／事件測試類別，共 34 個測試，0 failures、0 errors，`BUILD SUCCESS`，耗時 04:38；證據摘要與完整日誌見 `evidence/phase-d/review-d-tests.txt`、`review-all-d.log`。

`alerting.yaml` 僅新增 `mm-search-fallback`；將產生檔解析為 JSON，排除新規則後與基線比較，9 條既有規則皆語意相同。保留產生器的 canonical JSON 輸出格式，不手動壓縮產生檔；因此 review 指出的變化是格式差異，不含既有告警規則的內容修改。

---

## 已知限制

- SmartCN 對部分繁體中文仍逐字分詞；`name.char` 能補召回，但目前 OR 子句可能過度召回。D5 相關性測試要覆蓋長句與共字負例，若要改變計畫中的查詢語意，應先記錄計畫變更並討論。
- 瀏覽器驗證為使用者人工操作、無截圖；非自動化 E2E。告警恢復狀態尚無轉為 resolved 的直接證據。
- 未實際啟動 Kibana，僅確認 profile 與映像版本設定；D1 的分詞驗證直接使用 ES HTTP API。

---

## 最不確定、希望審查者重點看的地方

- D5：`match` 子句預設是 OR，長句或含常見字的關鍵字（例如「不」）會召回很多相關性很低的商品（D1 已記錄）。目前靠權重與排序壓低，沒有改查詢語意（計畫沒有授權）。是否要加 `minimum_should_match` 或 `operator: and` 要不要另開計畫變更討論。
- D5：`from + size > 10000` 的檢查放在查 ES 之前，所以 ES 掛掉時深分頁一樣回 400，而退回的 SQL 其實可以翻更深；這是依計畫的「一律 400」，但行為上有點不一致。
- D5：熔斷器是每個副本各一個，`search.fallback{reason=breaker_open}` 不會在副本之間共用（和階段 B 相同的限制）。
- D5：`ProductQueryRequest` 的預設排序改為在 service 端決定，既有前端沒有改動；前端搜尋頁的排序選單預設「最新」實際上會變成相關性（因為預設值不送出），這是計畫預期的行為，但值得在 D7 的前端驗收時確認使用者看到的是否合理。
- 繁體中文逐字分詞與 OR 查詢的誤召回；512Mi heap 在完整目錄重建的實際速度與高更新量補灌期間是否足夠；D3 對 retained 舊事件允許新增的 `brand`、`createdAt` 缺省並轉為空品牌／null 時間，需留意後續消費舊資料時建立完整搜尋快照的效果。前兩項尚未進行正式容量測試。
- D3 測試曾出現 `createdAt` 微秒精度比對與 ES 容器啟動的不穩定情形；D7 完整 reactor 已全數通過，但尚未做重複執行或容量測試。
