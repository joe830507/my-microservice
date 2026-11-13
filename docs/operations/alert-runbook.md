# 告警處理手冊

核對日期：2026-11-13。適用目前本機 Compose 與隔離 kind 示範環境；命令自 repo 根目錄執行，Shell 範例為 sh。先選對環境，不把範例的 namespace、DB 帳號或服務名稱直接套到其他環境。

## 使用方式與共用查詢

規則來源是 [alerting.yaml](../../infra/observability/alerting.yaml)，由 [generate_dashboard.py](../../infra/observability/generate_dashboard.py) 產生；面板名稱對照[儀表板 JSON](../../infra/observability/dashboards/my-microservice-overview.json)。以下保留原始 A 查詢，B 為 last reduce，C 比較門檻；規則組每 1m 評估，A 查詢相對時間範圍為 900s 至現在。`for: 0s` 代表沒有額外持續等待，不等於即時推播。十二條皆 noDataState=OK、execErrState=Error；空集合與 NaN 不可解讀成業務零錯誤。

先記錄告警 uid、時間、service_name／name／instance、近期部署及影響，再看 Grafana 儀表板 `/d/my-microservice-overview`，時間範圍覆蓋觸發前後。面板的統計視窗可能與告警不同，判斷是否觸發以本節列出的原查詢為準。

Loki Explore 使用 `{service_name="order-service"} | detected_level=~"warn|error"`，替換為受影響服務；找特定例外可接 `|= "文字"`。取得 log 的 trace_id 後，在 Tempo Explore 選 Trace ID 查同一追蹤；或用 TraceQL `{ resource.service.name = "order-service" }` 縮小服務與時間。獨立排程／SMTP trace 不一定接在原 HTTP trace 上；無 trace 不代表工作沒執行。不要貼完整事件 payload、Email、token、SMTP 憑證或信件內文到公開工單。

### 基本唯讀命令

Compose 檔主要跑基礎設施，Java 服務由 IDE／JAR 或 local-backend.sh 啟動；不能用 `docker compose logs order-service` 假設存在 Java 容器。查看對應 IDE／JAR log，或腳本所記錄的 log 檔。以下提供基礎設施與 kind 對照：

```sh
# Compose：DB、broker、監控等狀態；服務 log 依實際啟動方式查
 docker compose -f infra/docker-compose.yml ps
 docker compose -f infra/docker-compose.yml logs --since 15m observability
# kind：明確 context，避免誤操作其他叢集
kubectl --context kind-my-microservice -n my-microservice get pods
kubectl --context kind-my-microservice -n my-microservice logs deployment/order-service --all-pods=true --since=15m
kubectl --context kind-my-microservice -n my-microservice get events --sort-by=.lastTimestamp
```

以下每條先做唯讀診斷，修復依證據決定；不要為了消除告警直接重設 offset、刪資料、清空告警 counter 或重建全部服務。恢復後核對使用者操作、對應 metric、積壓與重送影響，持續觀察至少涵蓋告警原視窗及 for；工單記錄原因、處理與未驗證部分。

## 1. 服務 5xx 錯誤率偏高

- **觸發條件**：uid `mm-5xx-rate`；B=last(A)，C `> 0.05`，`for: 5m`。原始 A 查詢：

```promql
sum by (service_name) (rate(http_server_requests_milliseconds_count{uri!~"/actuator.*",status=~"5.."}[5m])) / sum by (service_name) (rate(http_server_requests_milliseconds_count{uri!~"/actuator.*"}[5m]))
```

- **代表什麼**：5xx 表示請求在服務端失敗，可能影響建單、登入等主流程；比例不表示失敗筆數，大流量／小樣本都需看分母。
- **先看哪裡**：「5xx 錯誤率」、「請求數（每秒）」、「warn / error」；同時依 HTTP uri／status 縮小，讀對應 Tempo。 所述 Loki／Tempo 用法見[共用查詢](#使用方式與共用查詢)。
- **常見原因與處理**：先由 service_name 找實際失敗服務。若 DB／連線池錯誤，檢查 DB 與等待連線；若下游 503／熔斷，依第 5 節。版本部署後才發生則對照錯誤碼與變更，修正或依部署流程回復版本；不要把 4xx 混入此告警。

```sh
# Compose
docker compose -f infra/docker-compose.yml logs --since 15m order-db
# kind
kubectl --context kind-my-microservice -n my-microservice logs deployment/order-service --all-pods=true --since=15m
```

- **相關文件**：[故障影響](../architecture/system-guide.md#8-故障時會發生什麼)、[事件契約](../architecture/event-contracts.md)、[spring-order-service 導讀](../class-relationships/spring-order-service.md)。

## 2. 服務延遲偏高（p95 > 2 秒）

- **觸發條件**：uid `mm-p95-latency`；B=last(A)，C `> 2000`，`for: 5m`。原始 A 查詢：

```promql
histogram_quantile(0.95, sum by (le, service_name) (rate(http_server_requests_milliseconds_bucket{uri!~"/actuator.*"}[5m])))
```

- **代表什麼**：p95 超過 2000 毫秒，代表使用者等待增加，不代表每個請求都慢；histogram 無樣本時可為 NaN。
- **先看哪裡**：「延遲 p95」、「最慢的 API（p95，依 uri）」、「服務之間的呼叫（出去，p95）」、「資料庫連線（使用中 / 等待中）」及 JVM 面板。 所述 Loki／Tempo 用法見[共用查詢](#使用方式與共用查詢)。
- **常見原因與處理**：先定位 uri，再沿 trace 找時間花在 DB、HTTP、CPU／GC 或重試。連線池 pending 高先查長交易／DB；下游慢查 timeout 與 retry，單次 timeout 不等於總請求期限。避免直接放大 pool／timeout 掩蓋瓶頸。

```sh
# Compose
docker compose -f infra/docker-compose.yml logs --since 15m product-db
# kind
kubectl --context kind-my-microservice -n my-microservice describe pod -l app=order-service
```

- **相關文件**：[故障影響](../architecture/system-guide.md#8-故障時會發生什麼)、[事件契約](../architecture/event-contracts.md)、[spring-order-service 導讀](../class-relationships/spring-order-service.md)。

## 3. 排程執行失敗

- **觸發條件**：uid `mm-scheduled-job-failed`；B=last(A)，C `> 0`，`for: 0s`。原始 A 查詢：

```promql
sum by (service_name, code_namespace) (increase(tasks_scheduled_execution_milliseconds_count{outcome!="SUCCESS"}[10m]))
```

- **代表什麼**：例外逃出受觀測的排程方法；逾時取消、預扣回收、token 清理或 outbox 發布可能延遲。排程內自行 catch 的單筆失敗不一定計入這個 outcome。
- **先看哪裡**：「排程執行（每 5 分鐘）」、「資料庫連線（使用中 / 等待中）」；Loki 依 service_name 與 code_namespace 找 job 例外。 所述 Loki／Tempo 用法見[共用查詢](#使用方式與共用查詢)。
- **常見原因與處理**：DB／鎖表不可用先恢復連線並檢查 migration／schema；ShedLock 正常跳過不等於失敗。查 job 自身 log、排程是否停用及下一輪執行，不直接刪 shedlock 列。單筆被 catch 的失敗另查業務積壓。

```sh
# Compose
docker compose -f infra/docker-compose.yml logs --since 15m inventory-db
# kind
kubectl --context kind-my-microservice -n my-microservice logs deployment/inventory-service --all-pods=true --since=15m
```

- **相關文件**：[故障影響](../architecture/system-guide.md#8-故障時會發生什麼)、[事件契約](../architecture/event-contracts.md)、[spring-inventory-service 導讀](../class-relationships/spring-inventory-service.md)。

## 4. 服務沒有回報指標

- **觸發條件**：uid `mm-no-metrics`；B=last(A)，C `> 120`，`for: 1m`。原始 A 查詢：

```promql
time() - max by (service_name) (timestamp(process_cpu_usage))
```

- **代表什麼**：process_cpu_usage 最後樣本時間距現在超過 120 秒，可能服務／OTLP collector 路徑異常；不能只憑此判定程序已停。
- **先看哪裡**：「各服務的副本」、「JVM 堆積使用量」與「warn / error」；Explore 查 process_cpu_usage，並看 observability collector log。 所述 Loki／Tempo 用法見[共用查詢](#使用方式與共用查詢)。
- **常見原因與處理**：先比 health／Pod 與 collector 是否運作、OTLP endpoint／NetworkPolicy 是否改動。此查詢受 Prometheus lookback 與 exporter 時間戳影響，序列完全消失會得到空集合且 noDataState=OK；服務正常但 metric 停送也可能是監控路徑故障，需另查，而非以告警 OK 證明健康。

```sh
# Compose
docker compose -f infra/docker-compose.yml logs --since 15m observability
# kind
kubectl --context kind-my-microservice -n my-microservice logs deployment/observability --since=15m
```

- **相關文件**：[故障影響](../architecture/system-guide.md#8-故障時會發生什麼)、[事件契約](../architecture/event-contracts.md)、[observability 導讀](../class-relationships/observability.md)。

## 5. 熔斷器打開

- **觸發條件**：uid `mm-circuit-open`；B=last(A)，C `> 0`，`for: 1m`。原始 A 查詢：

```promql
max by (name) (resilience4j_circuitbreaker_state{state="open"})
```

- **代表什麼**：至少一個同名熔斷器 open，呼叫可能立即失敗；每個副本狀態獨立，此告警 max by(name) 合併了服務與副本。
- **先看哪裡**：「打開中的熔斷器」、「熔斷器狀態（1 = 打開）」、「熔斷器失敗率」、「被熔斷拒絕的呼叫（每 5 分鐘）」；查原始 metric 加 service_name／instance 分辨呼叫方。 所述 Loki／Tempo 用法見[共用查詢](#使用方式與共用查詢)。
- **常見原因與處理**：依下方名稱表找下游，查健康、連線、timeout 與 log；恢復下游後等待 half-open 探測，再觀察失敗率。不要只重啟呼叫方強制清狀態。相同 product-service 名稱可能來自 Gateway 或 order，需依原始 labels 判斷。

```sh
# Compose
docker compose -f infra/docker-compose.yml ps
# kind
kubectl --context kind-my-microservice -n my-microservice get pods -l app=product-service
```

- **相關文件**：[故障影響](../architecture/system-guide.md#8-故障時會發生什麼)、[事件契約](../architecture/event-contracts.md)、[spring-api-gateway 導讀](../class-relationships/spring-api-gateway.md)。

## 6. 副本數少於 2

- **觸發條件**：uid `mm-replicas-low`；B=last(A)，C `< 2`，`for: 3m`。原始 A 查詢：

```promql
count by (service_name) (count by (service_name, instance) (last_over_time(process_cpu_usage[1m])))
```

- **代表什麼**：最近一分鐘仍回報 process_cpu_usage 的 instance 少於 2 持續三分鐘，冗餘降低；這是遙測副本數，不是 Kubernetes Ready replicas。
- **先看哪裡**：「運作中的副本數」、「各服務的副本」；以 Pod ready、deployment status 與 collector log 交叉確認。 所述 Loki／Tempo 用法見[共用查詢](#使用方式與共用查詢)。
- **常見原因與處理**：kind 查 CrashLoopBackOff、OOMKilled、Pending、探針、映像或 rollout；依原因恢復資源／設定。Compose／IDE 單實例本來可能低於門檻，需記為該環境預期而非盲目擴兩倍。服務所有序列消失可能不出現數值 0，不能代替 Kubernetes 可用性監控。

```sh
# Compose
docker compose -f infra/docker-compose.yml ps
# kind
kubectl --context kind-my-microservice -n my-microservice get deployment order-service -o wide
```

- **相關文件**：[故障影響](../architecture/system-guide.md#8-故障時會發生什麼)、[事件契約](../architecture/event-contracts.md)、[observability 導讀](../class-relationships/observability.md)。

## 7. 預扣被拒偏多

- **觸發條件**：uid `mm-reservation-rejected`；B=last(A)，C `> 20`，`for: 0s`。原始 A 查詢：

```promql
sum(increase(reservations_total{result="rejected"}[10m]))
```

- **代表什麼**：十分鐘內預扣 rejected 超過 20，顧客可能無法下單；這不是 HTTP 5xx，可能是正常缺貨。
- **先看哪裡**：「預扣被拒（庫存不足）」、「庫存預扣結果（每分鐘）」、「訂單與付款（每分鐘）」及 Gateway／order／inventory log。 所述 Loki／Tempo 用法見[共用查詢](#使用方式與共用查詢)。
- **常見原因與處理**：先用訂單錯誤的 SKU 查 available／reserved 與近期銷售；真的缺貨由營運補貨或下架，reserved 高時查逾時回收／付款事件 lag。若同來源高頻建單、預扣後不付款，查 Gateway 來源 IP、429 與來源信任設定；目前 login／register 有限流，不要宣稱已有建單專用限流，需要時另評估入口防濫用。不要只因 counter 高就增加庫存。

```sh
# Compose
docker compose -f infra/docker-compose.yml logs --since 15m inventory-db
# kind
kubectl --context kind-my-microservice -n my-microservice logs deployment/api-gateway --all-pods=true --since=15m
```

- **相關文件**：[故障影響](../architecture/system-guide.md#8-故障時會發生什麼)、[事件契約](../architecture/event-contracts.md)、[spring-inventory-service 導讀](../class-relationships/spring-inventory-service.md)。

## 8. Kafka consumer lag 偏高

- **觸發條件**：uid `mm-kafka-consumer-lag`；B=last(A)，C `> 1000`，`for: 5m`。原始 A 查詢：

```promql
max by (service_name) (kafka_consumer_fetch_manager_records_lag_max)
```

- **代表什麼**：消費端最大 partition lag 超過 1000 持續五分鐘；付款後訂單變 PAID、庫存確認與通知可能延遲，metric 是最大 lag 而非所有 topic 總和。
- **先看哪裡**：儀表板「warn / error」、「搜尋索引事件（每分鐘）」及「訂單與付款（每分鐘）」；目前沒有專用 Kafka lag 面板，直接在 Prometheus Explore 查本節 metric，再依 client／topic／partition labels 展開。 所述 Loki／Tempo 用法見[共用查詢](#使用方式與共用查詢)。
- **常見原因與處理**：先查 consumer log 的重試例外。最佳化第 19 點後，暫時性 DB／5xx 錯誤無限退避，會卡住該 partition 保序，lag 增加不一定是吞吐不足；搜尋索引也會因 ES 暫時故障持續重試。先恢復 DB／ES／網路，再查 group 的 assignment／lag；只有無重試錯誤且確實容量不足才評估副本與 partition，避免 reset offsets 跳過付款事件。

```sh
# Compose
docker compose -f infra/docker-compose.yml exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group order-service
# kind
kubectl --context kind-my-microservice -n my-microservice exec kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group order-service
```

- **相關文件**：[故障影響](../architecture/system-guide.md#8-故障時會發生什麼)、[事件契約](../architecture/event-contracts.md)、[common 導讀](../class-relationships/common.md)。

## 9. 搜尋持續退回 SQL

- **觸發條件**：uid `mm-search-fallback`；B=last(A)，C `> 0`，`for: 5m`。原始 A 查詢：

```promql
sum by (application, reason) (rate(search_fallback_total[1m]))
```

- **代表什麼**：search_fallback_total 的一分鐘 rate 持續大於零；搜尋仍回應，但相關性／匹配範圍退為 SQL 名稱 LIKE，並提高 DB 負載。
- **先看哪裡**：「搜尋引擎使用量（每分鐘）」、「搜尋退回 SQL（每分鐘，依原因）」、「Elasticsearch 搜尋平均延遲」、「搜尋過期命中（每分鐘）」與 product log。 所述 Loki／Tempo 用法見[共用查詢](#使用方式與共用查詢)。
- **常見原因與處理**：依 reason 區分 timeout、breaker_open、index_not_ready、error；查 ES 健康、products alias、schemaVersion／built 與 startup rebuild log。查詢失敗不是一律重建；修連線／容量後再判斷是否需內部 rebuild API，保留舊索引，勿直接 delete products。其他 4xx 查詢錯誤可能是 bug 並回 500，不一定走 fallback。

```sh
# Compose
docker compose -f infra/docker-compose.yml logs --since 15m elasticsearch
# kind
kubectl --context kind-my-microservice -n my-microservice logs deployment/product-service --all-pods=true --since=15m
```

- **相關文件**：[故障影響](../architecture/system-guide.md#8-故障時會發生什麼)、[事件契約](../architecture/event-contracts.md)、[spring-product-service 導讀](../class-relationships/spring-product-service.md)。

## 10. Kafka 事件進入 DLT

- **觸發條件**：uid `mm-kafka-dlt`；B=last(A)，C `> 0`，`for: 0s`。原始 A 查詢：

```promql
sum(increase(events_dlt_published_total[5m]))
```

- **代表什麼**：五分鐘內至少一筆事件成功發至 DLT，可能是格式錯誤或業務衝突，不能靠重啟當作處理完畢。
- **先看哪裡**：目前無專用 DLT 面板；Prometheus Explore 查 events_dlt_published_total，再用「warn / error」查消費服務與來源 topic。 所述 Loki／Tempo 用法見[共用查詢](#使用方式與共用查詢)。
- **常見原因與處理**：完整的查看、headers 判讀、責任分流、人工退款、重放預覽與確認流程統一見[DLT 處理與重放](../architecture/event-contracts.md#dlt-處理與重放)，此處不複製命令或另建一套處理流程。

- **相關文件**：[故障影響](../architecture/system-guide.md#8-故障時會發生什麼)、[事件契約](../architecture/event-contracts.md)、[common 導讀](../class-relationships/common.md)。

## 11. 通知信件寄送失敗（FAILED）

- **觸發條件**：uid `mm-notification-failed`；B=last(A)，C `> 0`，`for: 0s`。原始 A 查詢：

```promql
sum(increase(notification_emails_total{result="failed"}[10m]))
```

- **代表什麼**：十分鐘內新增 failed 結果，表示永久 SMTP 錯誤或重試耗盡；FAILED 不會被 Dispatcher 自動重領。counter 不是資料表中 FAILED 總數。
- **先看哪裡**：「寄信結果（每分鐘，依範本與結果）」、「SMTP 平均寄送耗時」；Loki 查 notification-service，Tempo 可找 notification.email.send。 所述 Loki／Tempo 用法見[共用查詢](#使用方式與共用查詢)。
- **常見原因與處理**：查下方唯讀 email_message SQL 的 last_error／attempts。認證、地址、SMTP 5xx 等可直接終止；連線／4xx 通常先重試，預設八次達 FAILED。確認 SMTP host／port、認證／TLS 與 connection/read/write timeout（預設 3000／5000／5000ms）。先修根因，需補寄時核對收信與業務需求再設計受控處理，不能直接把所有 FAILED 改回 PENDING。

```sh
# Compose
docker compose -f infra/docker-compose.yml logs --since 15m mailpit
# kind
kubectl --context kind-my-microservice -n my-microservice logs deployment/notification-service --all-pods=true --since=15m
```

- **相關文件**：[故障影響](../architecture/system-guide.md#8-故障時會發生什麼)、[事件契約](../architecture/event-contracts.md)、[spring-notification-service 導讀](../class-relationships/spring-notification-service.md)。

## 12. 通知信件積壓

- **觸發條件**：uid `mm-notification-backlog`；B=last(A)，C `> 600`，`for: 5m`。原始 A 查詢：

```promql
max(notification_email_oldest_pending_seconds)
```

- **代表什麼**：最舊 PENDING 自 created_at 起已超過 600 秒且持續五分鐘；與 next_attempt_at 是否到期不同，重試等待也包含在內。
- **先看哪裡**：「待寄信件數」、「最舊待寄信件已等待（秒）」、「寄信結果（每分鐘，依範本與結果）」及 SMTP 耗時。 所述 Loki／Tempo 用法見[共用查詢](#使用方式與共用查詢)。
- **常見原因與處理**：先查 SQL 狀態分布、next_attempt_at、SENDING lease_until／lease_token。PENDING 累積可能 SMTP 重試或 order_ref／user_contact 缺失；查 consumer lag，再查服務啟動前歷史投影缺口。有效 SENDING 租約要等，過期者由下一輪領取回收；固定兩分鐘租約沒有續期，不要人工清 token 造成雙寄。兩個 gauge 只看 PENDING，SENDING 堆積可能不觸發此告警，需 SQL 補查。

```sh
# Compose
docker compose -f infra/docker-compose.yml exec notification-db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT status,count(*) FROM notification_service.email_message GROUP BY status;"'
# kind
kubectl --context kind-my-microservice -n my-microservice exec notification-db-0 -- sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT status,count(*) FROM notification_service.email_message GROUP BY status;"'
```

- **相關文件**：[故障影響](../architecture/system-guide.md#8-故障時會發生什麼)、[事件契約](../architecture/event-contracts.md)、[spring-notification-service 導讀](../class-relationships/spring-notification-service.md)。

## 熔斷名稱與下游對照

| 呼叫方 | metric 的 name | 保護對象 |
|---|---|---|
| Gateway | user-service | 登入、註冊及使用者路由，共用同名熔斷器 |
| Gateway | product-service／inventory-service／order-service／payment-service | 各同名服務路由 |
| order-service | product-service／inventory-service | 同步查商品／庫存預扣與補償 HTTP |
| payment-service | order-service | 建付款前同步查訂單 |
| product-service | elasticsearch-search | 前台 ES 查詢，open 時退 SQL；不是背景索引／重建的熔斷 |

來源：[Gateway YAML](../../backend/spring-api-gateway/src/main/resources/application.yml)、[ProductClient](../../backend/spring-order-service/src/main/java/com/mymicroservice/order/clients/ProductClient.java)、[InventoryClient](../../backend/spring-order-service/src/main/java/com/mymicroservice/order/clients/InventoryClient.java)、[OrderClient](../../backend/spring-payment-service/src/main/java/com/mymicroservice/payment/clients/OrderClient.java)、[ProductSearchService](../../backend/spring-product-service/src/main/java/com/mymicroservice/product/search/ProductSearchService.java)。JWKS 有獨立 timeout，但不因此新增一個名為 jwks 的熔斷器。

## 通知資料庫補查

透過上節 Compose／kind 的 notification-db psql 執行下列唯讀 SQL；不查 email 或內文，不在終端輸出 SMTP 密碼。預設帳號以容器環境為準，不把 Secret 值寫入文件。

```sql
SELECT status,count(*) FROM notification_service.email_message GROUP BY status;
SELECT id,template,status,attempts,next_attempt_at,last_error,skip_reason,
       lease_until,lease_token,created_at,sent_at
FROM notification_service.email_message
WHERE status IN ('PENDING','SENDING','FAILED','SKIPPED')
ORDER BY created_at,id LIMIT 50;
```

SENT 只代表 SMTP 呼叫成功後狀態寫回，不保證收件匣展示；FAILED／SKIPPED 是終止狀態。有效 token 限制過期工作者寫回，但 SMTP 與 DB 不原子，重送仍可能雙寄。缺投影的 SKIPPED 預設第五次終止；恢復投影不會自動啟動 SKIPPED。細節見[通知類別導讀](../class-relationships/spring-notification-service.md)與[SMTP／個資限制](../security/known-issues.md#sec-15-通知服務的測試-smtp-與-email-個資保護)。

## 本次驗證與限制

實際查詢時間 `2026-11-13T04:17:23.352793+00:00`，context `kind-my-microservice`，透過 observability Pod 的 localhost:9090/api/v1/query 對全部十二個原始 A 查詢做 instant query。[結構化結果](../implementation-plans/evidence/documentation-alert-queries.json)只保存序列數與值，不保存個資／token。

| 告警 uid | API 結果 | 序列數／值摘要 |
|---|---|---|
| mm-5xx-rate | success | 0；空集合 |
| mm-p95-latency | success | 1；NaN |
| mm-scheduled-job-failed | success | 0；空集合 |
| mm-no-metrics | success | 7；4.376000165939331、3.690000057220459、2.1579999923706055、0.377000093460083、4.564000129699707、10.650000095367432、5.434000015258789 |
| mm-circuit-open | success | 0；空集合 |
| mm-replicas-low | success | 7；2、2、2、2、2、2、2 |
| mm-reservation-rejected | success | 0；空集合 |
| mm-kafka-consumer-lag | success | 4；NaN、NaN、NaN、NaN |
| mm-search-fallback | success | 0；空集合 |
| mm-kafka-dlt | success | 1；0 |
| mm-notification-failed | success | 0；空集合 |
| mm-notification-backlog | success | 1；0 |

十二條查詢皆 success，沒有注入故障，沒有驗證 Grafana C 門檻、for 計時、通知接收或告警恢復；空集合／NaN 不表示測得零錯誤。Compose 命令、Loki／Tempo 範例、通知 SQL 與各修復步驟未在本次實際執行，僅靜態核對來源。未重跑服務測試、部署或修改告警規則。
