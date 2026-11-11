# 韌性與水平擴充（階段 B）實作紀錄

> 完成日期：2026-11-11
> 分支：`feature/middleware-roadmap`
> 對應：[middleware-roadmap.md](middleware-roadmap.md)「階段 B」
> 狀態：B1–B5 完成；單元／整合測試通過，並在 kind（2 個副本）上實測

目標：下游慢或掛掉時，上游快速失敗而不是一起卡住；每個服務跑 2 個副本，排程不重複執行，更新與單一 Pod 消失時不中斷服務。

## 內容

| Step | 內容 | 位置 |
| ---- | ---- | ---- |
| B1 | order-service 呼叫 product、inventory 加重試與熔斷 | `common` 的 `DownstreamCaller`、order 的 `ProductClient`／`InventoryClient` |
| B2 | payment-service 呼叫 order-service 同樣處理 | payment 的 `OrderClient` |
| B3 | Gateway 每個下游一個熔斷器，失敗時回統一格式的 503／504 | `spring-api-gateway` 的 `resilience/`、`application.yml` |
| B4 | 排程用 ShedLock 保證每一輪只有一個副本執行 | `common` 的 `SchedulingLockAutoConfiguration`、4 個服務的 `shedlock` 表 |
| B5 | 2 個副本、滾動更新、優雅關閉、PodDisruptionBudget | `infra/k8s/services/` |

### 重試與熔斷的規則（B1、B2）

`DownstreamCaller`（common）包 Resilience4j 的核心函式庫（Spring Boot starter 還沒支援 Boot 4，所以不用它的自動設定，自己包一層；指標用 `resilience4j-micrometer` 綁到 Micrometer）。

| 情況 | 算失敗（熔斷計數） | 重試（只有冪等的呼叫） |
| ---- | :--: | :--: |
| 連線被拒（ConnectException） | ✅ | ✅ |
| 502／503／504 | ✅ | ✅ |
| 其他 5xx | ✅ | ❌ |
| 連線逾時、讀取逾時 | ✅ | ❌（已經等了整個逾時時間） |
| 4xx（庫存不足、訂單不存在…） | ❌（業務結果） | ❌ |
| 熔斷打開（CallNotPermittedException） | — | ❌ |

- 重試：最多 3 次嘗試，第一次重試前等 100ms，之後加倍。冪等的呼叫才重試：查商品、查訂單（GET）、以 orderId 為冪等鍵的預扣／釋放／確認、order 的 paid 通知
- 熔斷：最近 20 次呼叫、至少 10 次之後，失敗率達 50% 就打開；打開 10 秒後放 3 個請求試探。設定在 `order.services.resilience`、`payment.services.resilience`（預設值在 `ResilienceProperties`）
- 熔斷打開時 `InventoryClient.reserve` 回新的 `UNAVAILABLE`：請求根本沒有送出，**確定沒有預扣**，所以不必呼叫釋放（`UNKNOWN` 才需要釋放）
- 每個副本各自一個熔斷器，狀態不共用：2 個副本時各自累積自己的失敗

### Gateway（B3）

- 每個路由有 `CircuitBreaker` filter，同一個下游共用一個熔斷器（名字 = 服務名稱，login、register 與 user-service 是同一個）
- **只有連不上、逾時這類例外算失敗**；下游回的 HTTP 狀態碼（含 5xx）原樣轉給用戶端、不算失敗：那些是業務錯誤（例如 `PRODUCT_SERVICE_UNAVAILABLE`），前端依 `code` 顯示訊息，不能被通用的 503 蓋掉
- 失敗時 forward 到 `/fallback/unavailable`，依原因回 503 `SERVICE_UNAVAILABLE`（熔斷打開、連不上）或 504 `GATEWAY_TIMEOUT`，格式和其他錯誤相同（ProblemDetail + `code`）
- Spring Cloud 預設的 TimeLimiter 是 1 秒，會砍掉正常但慢的請求：明確設成 15 秒（比 HTTP 的 response-timeout 10 秒長，讓 HTTP 層先觸發）

### 排程鎖（B4）

- `app.scheduling.lock.table` 設了才啟用（沒有排程的 product-service 不受影響）；鎖放在各服務自己的 PostgreSQL（`<schema>.shedlock`，Flyway migration 建立），用資料庫的時間（`usingDbTime`），不依賴各 Pod 的時鐘
- 5 支排程各加 `@SchedulerLock`：`lockAtMostFor` 5 分鐘（副本當機時鎖最久這麼久後失效）、`lockAtLeastFor` 10 秒（多個副本在同一秒觸發同一個 cron，先做完的放掉鎖之後，另一個副本也不能再拿到；所有排程的間隔都在 15 秒以上）
- 這些排程原本就是冪等的（條件式 UPDATE），鎖是為了不重複工作、不重複呼叫下游，不是為了正確性

### K8s（B5）

- 6 個服務各 2 個副本，`maxUnavailable: 0`、`maxSurge: 1`（先起新的、就緒後才關舊的）；`topologySpreadConstraints` 盡量分開節點（kind 單節點用 `ScheduleAnyway`）；每個服務一個 PodDisruptionBudget（`minAvailable: 1`）
- 優雅關閉：`SERVER_SHUTDOWN=graceful`（處理中的請求最多做 20 秒）、`preStop` 先 `sleep 8`、`terminationGracePeriodSeconds: 40`。Pod 被刪除時，Service 移除端點和 SIGTERM 是同時發生的，不先等一下，滾動更新時會有少數請求打到正在關閉的 Pod
- 指標加 `service.instance.id`（K8s 的 `HOSTNAME`，也就是 Pod 名稱）：少了它，兩個副本的同名指標互相覆蓋，Prometheus 看起來只有一個

## 驗證結果

| 項目 | 結果 |
| ---- | ---- |
| `DownstreamCaller` 單元測試 | 11 個：各種例外的重試與否、4xx 不計失敗、熔斷打開／半開關閉、熔斷器依名字獨立 |
| order：product、inventory 整合測試 | 6 個（WireMock）：失敗一次後恢復有重試、一直失敗時熔斷打開並停止呼叫、庫存不足不重試也不計失敗、熔斷打開時不釋放 |
| payment：order-service 整合測試 | 6 個 |
| Gateway 整合測試 | 5 個：下游的 5xx 原樣轉發且不開熔斷、連線失敗開熔斷後不再轉送、一個服務的熔斷不影響另一個、逾時回 504 |
| 排程鎖整合測試（order） | 4 個：同一輪第二次被略過、另一個副本拿著鎖時略過、不同排程互不影響、4 個執行緒同時只執行 1 次 |
| kind：2 個副本 | 兩個前端的 E2E 通過；`shedlock` 表的 `locked_by` 是不同的 Pod 名稱 |
| kind：滾動重啟 product、gateway、order 的同時持續請求 | 287 個請求全部 200（沒有加優雅關閉前，重啟 gateway 時有 2 個 503） |
| kind：`product-service` 縮到 0 個副本 | POST /orders 與經 Gateway 的 GET，約 10 次失敗後熔斷器打開，之後回 503 只要約 0.02 秒（打開前每次要等 2 秒的連線逾時） |
| Grafana | 儀表板新增「韌性與副本」一列（副本數、熔斷器狀態與失敗率、被拒絕的呼叫）；告警新增「熔斷器打開」「副本數少於 2」 |

## 遇到的問題

1. **重試連線逾時會讓每個請求多等 4 秒**：第一版連 `HttpConnectTimeoutException` 也重試。K8s 上 Service 沒有可用的 Pod 時，連線不是被拒絕而是逾時（2 秒），3 次嘗試要 6 秒。改成只重試「連線被拒」，所有逾時都不重試；JDK 的 `HttpConnectTimeoutException` 內部還包著一個 `ConnectException`，所以要先檢查整條 cause 鏈有沒有逾時
2. **ShedLock 刪掉資料列後就拿不到鎖**：它記得「這個鎖已經有資料列」，之後只 UPDATE。測試要讓鎖過期（`lock_until` 改成過去），不能 `DELETE`；而且 `lock_until` 是 UTC 的 `TIMESTAMP`，寫測試用 `timezone('utc', now())`，用 `now()` 會依連線的時區寫入（JVM 不是 UTC 時就算錯）
3. **`@SchedulerLock` 讓連續呼叫 `run()` 的既有測試被略過**：`lockAtLeastFor` 內第二次呼叫會被當成另一個副本的同一輪。測試的 `application.properties` 設 `app.scheduling.lock.at-least-for=PT0S`，鎖的測試自己打開
4. **熔斷器是每個副本各一個**：2 個副本時，30 個請求各副本只看到約 15 個，所以要 20 次以上才全部快速失敗。要共用狀態需要把狀態放 Redis 之類的共用儲存，雛型階段先接受
5. **連續跑兩個 E2E 偶爾失敗**：後台的 E2E 先登入多次，接著商店的註冊後自動登入被 Gateway 的登入限流（每 IP 約每分鐘 10 次）擋下。等一分鐘再跑，不是副本造成的

## 已知限制

- 熔斷器與重試的狀態各副本獨立，不會同步
- `lockAtLeastFor` 10 秒是為了蓋住副本之間的時鐘差；cron 間隔改到 10 秒以內時要一起調整
- 沒有對 `GET`/`POST /orders` 之外的 API 做超時以外的保護（例如併發上限、bulkhead）；目前流量不需要
- 優雅關閉的 `preStop` 固定等 8 秒：Pod 更新與縮減都會多這 8 秒（服務啟動本來就要 20 秒左右）
