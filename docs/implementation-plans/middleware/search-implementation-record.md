# 搜尋（Elasticsearch）實作紀錄（階段 D）

> 日期：2026-11-11
> 分支：`feature/search-phase-d`（從階段 C 後續計畫分支切出）
> 對應：[實作計畫](search-implementation-plan.md)、[roadmap](middleware-roadmap.md)
> 狀態：D1–D3 完成；D4–D7 尚未開始

## 內容

| Step | 內容與狀態 |
|---|---|
| D1 | 單節點 Elasticsearch／SmartCN 映像、Compose、kind、NetworkPolicy、中文分詞與召回實驗已完成；完整 reactor 通過 |
| D2 | ES Java client、商品索引 mapping、索引與別名管理、`_meta` 更新、商品快照轉換及 `external_gte` 寫入已完成；真實 ES Testcontainers 驗收通過 |
| D3 | 商品事件契約與 Elasticsearch listener 已完成；真實 Kafka＋ES 測試涵蓋亂序版本、DLT 與暫時故障恢復 |
| D4–D7 | 待實作；目前商店搜尋仍使用既有 SQL |

## 規則與版本

- 使用 `9.4.5`，與專案 Spring Boot `4.1.1` BOM 的 `elasticsearch-client.version` 完全一致。自訂映像 `my-microservice/elasticsearch:9.4.5-smartcn`，內建官方 `analysis-smartcn`，不在 Pod 啟動時下載。
- 官方外掛說明：[Smart Chinese analysis plugin](https://www.elastic.co/docs/reference/elasticsearch/plugins/analysis-smartcn)。SmartCN 主要針對簡體中文，繁體中文品質需以實測評估。
- 開發環境關閉認證及 TLS，以 loopback 埠與 NetworkPolicy 隔離；限制見 SEC-14。Kibana 只有 Compose 的 `dev-tools` profile，沒有 K8s 資源。
- 固定 512Mi JVM heap、單節點、關閉開發環境磁碟水位檢查；`node.store.allow_mmap=false` 避免修改主機 sysctl 或使用 privileged initContainer。
- K8s 的記憶體 request 1Gi／limit 1536Mi，資料存入 2Gi PVC；只允許 product-service 連 9200。D1 尚不修改商品程式或事件契約。

## 驗證結果

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
- 2026-11-11 21:30:16：listener 整合測試 3 項通過，reactor common、observability、product-service SUCCESS。涵蓋 v4 後到達的 v3 不覆蓋最新版、未知事件進 DLT、ES 停機時不進 DLT且重啟後寫入成功。
- 2026-11-11 21:30:52：共用 outbox 回歸 4 項通過（`OutboxRelayIntegrationTests` 3 項、`ResilientEventConsumerTest` 1 項），common SUCCESS。
- wrapper 腳本在目前 shell 啟動時有 `icm` 錯誤；命令改用 wrapper 已下載的 Maven 3.10.0 執行檔。首次在未提升權限的 shell 執行時 Maven 無法存取 `%USERPROFILE%\.m2` 本機快取，提升後使用相同離線命令成功；不需要網路下載。

| 範例 | smartcn | standard |
|---|---|---|
| 無線藍牙耳機 | 無、線、藍、牙、耳、機 | 無、線、藍、牙、耳、機 |
| 輕量運動鞋 | 詳見原始證據 | 輕、量、運、動、鞋 |
| Apple iPhone 15 手機殼 | 英文小寫；中文結果詳見證據 | apple、iphone、15、手、機、殼 |

完整回歸：在 backend 執行 `mvnw.cmd -o clean test`，2026-11-11 20:35:51 結束，九模組全部 SUCCESS，耗時 7:12，包含真實 Kafka 與 PostgreSQL Testcontainers。[完整 reactor 摘要](evidence/phase-d/d1-reactor.txt)。

## 遇到的問題

- 初次測試以「完全不相關的宇宙火箭」當負例，因「不」與「不鏽鋼保溫杯」共字，在計畫指定的 OR 查詢下仍有結果。[初次實驗](evidence/phase-d/d1-analysis-initial.json)完整保留。驗收負例採獨立名詞「宇宙火箭」，沒有改動查詢、權重或放寬對計畫指定詞彙的斷言。
- Windows PowerShell 5 讀取無 BOM 的中文腳本可能把下一行指令誤讀成註解；驗收腳本使用 UTF-8 BOM，**證據檔**仍維持無 BOM UTF-8。修正後已重新執行，確認 kind 分詞證據確實產出。

## 已知限制

- SmartCN 對部分繁體中文仍逐字分詞；`name.char` 能補召回，但目前 OR 子句可能過度召回。D5 相關性測試要覆蓋長句與共字負例，若要改變計畫中的查詢語意，應先記錄計畫變更並討論。
- 尚未實作全量重建或搜尋 API；D3 完成不代表 D4–D7 完成。
- 未實際啟動 Kibana，僅確認 profile 與映像版本設定；D1 的分詞驗證直接使用 ES HTTP API。

## 最不確定、希望審查者重點看的地方

繁體中文逐字分詞與 OR 查詢的誤召回；512Mi heap 在後續重建與雙副本索引器的負載下是否足夠；D3 對 retained 舊事件允許新增的 `brand`、`createdAt` 缺省並轉為空品牌／null 時間，需留意後續消費舊資料時建立完整搜尋快照的效果。前兩項目前只有小型實驗，容量尚未實測。
