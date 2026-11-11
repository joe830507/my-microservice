# 可觀測性（階段 A）實作紀錄

> 完成日期：2026-11-11
> 分支：`feature/middleware-roadmap`
> 對應：[middleware-roadmap.md](middleware-roadmap.md)「階段 A」
> 狀態：A1–A6 完成；本機（compose）與 kind 都已實際驗證

目標：一張訂單從建單、預扣、付款到確認，可以用**一個 traceId** 在 Grafana 上看到完整的呼叫鏈、各段耗時與對應的 log。

## 架構

```
服務（6 個）──OTLP/HTTP :4318──▶ OpenTelemetry Collector ─┬─▶ Prometheus（指標）
 指標、追蹤、log                  （grafana/otel-lgtm 一個容器）├─▶ Tempo（追蹤）
                                                           └─▶ Loki（log）
                                                                  ▲
                                                           Grafana :3000（儀表板、告警、Explore）
```

- 三種資料都用 OTLP **推**到 collector；另外每個服務保留 `/actuator/prometheus` 當除錯用（拉取式，Gateway 對外封鎖，只在叢集內可連）
- 匯出**預設關閉**（`OBSERVABILITY_EXPORT_ENABLED`）：沒有 collector 的環境（單元測試、單獨啟動服務）不會一直重試與報錯。追蹤本身永遠開著，所以 log 裡永遠有 traceId
- 本機與 K8s 用同一個映像檔、同一份儀表板與告警規則檔

## 內容

| 位置 | 內容 |
| ---- | ---- |
| `backend/observability/` | 新模組（沒有業務程式碼）：依賴（OpenTelemetry starter、Prometheus registry、Logback appender）、`observability.yml`（共用設定）、`logback-spring.xml`、把 OpenTelemetry SDK 交給 appender 的 auto-configuration |
| 6 個服務的 `pom.xml`、`application.yml` | 依賴 `observability`，並 `spring.config.import: classpath:observability.yml`。Gateway 是 reactive、不依賴 common，所以這些不放在 common |
| 業務指標 | `orders.created{result}`、`orders.paid`、`orders.cancelled{reason}`、`payments.completed{result}`、`reservations{result}`（用 `Metrics.counter`，不改任何建構子） |
| `infra/docker-compose.yml` | `observability` 服務（`grafana/otel-lgtm:0.40.0`），埠只綁 127.0.0.1：Grafana 3000、OTLP 4317／4318 |
| `infra/observability/` | `generate_dashboard.py`（產生儀表板 JSON 與告警規則）、`dashboards/my-microservice-overview.json`、`alerting.yaml`、`dashboards.yaml`、K8s 的 `k8s-deployment.yaml` 與 `kustomization.yaml` |
| `infra/k8s/` | 6 個服務加環境變數、`create-secrets.sh` 多產生 Grafana 的 admin 密碼、`network-policies*.yaml` 開放 OTLP |
| `infra/scripts/local-backend.sh` | 本機啟動／停止 6 個服務（`OBSERVE=1` 同時送到 compose 的 Grafana） |

### 本機使用

```bash
docker compose -f infra/docker-compose.yml up -d            # 含 observability
(cd backend && ./mvnw -DskipTests package)
OBSERVE=1 infra/scripts/local-backend.sh start               # 約 1 分鐘後 http://localhost:8080/actuator/health 變 UP
# Grafana：http://localhost:3000   → Dashboards → my-microservice 總覽；Explore → Tempo / Loki
infra/scripts/local-backend.sh stop                          # 重新打包前要先停（Windows 會鎖住 jar）
```

### K8s 使用

```bash
kubectl --context kind-my-microservice -n my-microservice port-forward svc/observability 3000:3000   # 匿名可看；要改才需要 admin 密碼
kubectl -n my-microservice get secret observability -o jsonpath='{.data.grafana-admin-password}' | base64 -d
```

## 進度

| Step | 內容 | 狀態 |
| ---- | ---- | ---- |
| A1 | 指標：`/actuator/prometheus` 與 OTLP 指標 | 完成 |
| A2 | 追蹤：Gateway → 服務、RestClient 呼叫都傳遞 `traceparent` | 完成 |
| A3 | log：OTLP 匯出到 Loki，帶 traceId／spanId；`json-logs` profile 輸出結構化 JSON | 完成 |
| A4 | 本機 compose：otel-lgtm | 完成 |
| A5 | K8s：同一套放進 `infra/observability/`，NetworkPolicy（進與出）開放 | 完成 |
| A6 | 儀表板與告警規則 | 完成 |

## 驗證結果

| 項目 | 結果 |
| ---- | ---- |
| 一次建單的追蹤 | `api-gateway → order-service`，其下 `product-service`（含 Redis `mget`）與 `inventory-service`，4 個服務在同一個 trace（本機與 kind 都看到） |
| log ↔ trace | 請求處理中產生的 log，主控台的括號裡有 `[traceId-spanId]`，Loki 的同一筆有 `trace_id`、`span_id`，用它到 Tempo 查得到同一條 trace |
| 6 個服務的指標、追蹤、log | 都進到 Prometheus、Tempo、Loki（`service_name` 標籤） |
| 儀表板 | 21 個面板，每一個查詢都用 Prometheus API 實際執行過，有資料的都有結果（沒有事件的數字是 0） |
| 告警規則 | 5 條由 provisioning 載入（本機與 kind 都是） |
| NetworkPolicy（kind，Calico） | 後端服務 → observability 4317／4318 通；→ 3000 不通；沒有授權的 Pod、前端、資料庫 → observability 不通；observability 往外（叢集內其他服務、叢集外）不通 |
| 測試 | 後端全部測試通過；兩個前端的 E2E 在本機與 kind 上都通過 |

## 遇到的問題

1. **Gateway 的 Boot 版本是 4.0.9（Spring Cloud 需要），其他服務是 4.1.1**：Gateway 管理的 OpenTelemetry 是 1.55.0，比 logback appender（2.28）需要的 1.62.0 舊。記錄「含例外」的 log 時 `NoSuchMethodError: LogRecordBuilder.setException`，而且這個錯誤發生在 Micrometer 發佈指標的執行緒裡，**整個指標匯出就此中斷**（Gateway 的指標完全沒有，其他資料看起來都正常，只有 kind 上發現，因為本機沒有觸發含例外的 log）。解法：Gateway 的 pom 設 `opentelemetry.version=1.62.0`，並加測試（`OpenTelemetryVersionAlignmentTest`）檢查 API 有這個方法
2. **Spring Cloud 的 BOM 把 `opentelemetry-semconv` 管理在 1.37.0**：appender 啟動時 `NoSuchFieldError: OtelAttributes.OTEL_EVENT_NAME`。Gateway 的 pom 明確指定 1.41.1
3. **Spring Boot 不會把 OpenTelemetry SDK 交給 Logback 的 OTEL appender**：只有 `logback-spring.xml` 裡有 appender 不夠，log 不會送出。`OpenTelemetryLogbackAutoConfiguration` 在 SDK 建好之後呼叫 `OpenTelemetryAppender.install(...)`
4. **`logback-spring.xml` 不能 include Boot 的 `base.xml`**：它是相容舊版的設定，不支援結構化格式（`logging.structured.format.console` 沒有作用），還會多開一個寫到 `/tmp/spring.log` 的檔案 appender。改成 include `defaults.xml` 與 `console-appender.xml`，結構化 JSON 用 profile `json-logs`（`<springProfile>` 切換 encoder）；啟用時 Banner 也要走 logger（`spring.main.banner-mode=log`），否則 JSON 裡會混著一段非 JSON 的行
5. **服務比 observability 先啟動時，前幾十秒會有 `Connect timed out` 的匯出錯誤**：collector 還沒就緒，匯出器會自動重試，之後正常。不影響服務；之後若要消除，可以讓匯出失敗只記 debug（階段 B 一起調）
6. **kind 的 `kubectl port-forward` 與 compose 的物件儲存都用本機 8333**：兩個同時存在時圖片的 E2E 會隨機失敗。跑對著 kind 的 E2E 前先 `docker stop my-microservice-object-storage`，跑完再 `docker compose up -d object-storage`
7. **本機重新打包失敗（`Unable to rename ...jar`）**：服務還在跑，Windows 鎖住 jar；`infra/scripts/local-backend.sh stop` 先停

## 已知限制

- Collector 沒有認證，只靠 NetworkPolicy 限制誰連得到；Grafana 在 K8s 上匿名為 Viewer，沒有對外（只用 port-forward）
- K8s 上的資料放 `emptyDir`，Pod 重啟就沒了；本機 compose 有 volume。正式環境改用各自部署的 Prometheus、Tempo、Loki（見 roadmap 的待決事項）
- 取樣率目前 100%（`TRACING_SAMPLING_PROBABILITY`）；正式環境要調低
- 沒有 JDBC 的 span（只有 HTTP、Redis、排程）；要看 SQL 耗時可以之後加 `datasource-micrometer`
- 平常的 INFO log 很少在請求內產生，所以 Loki 裡帶 `trace_id` 的主要是 warn／error；需要時可以加請求 log
- 儀表板在 Grafana 上改了不會存回 repo：改 `infra/observability/generate_dashboard.py` 再執行
