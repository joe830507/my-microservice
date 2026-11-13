# 系統缺口評估

核對日期：2026-11-13。對照 repo 現況，列出**尚未出現在任何追蹤清單**的缺口。已追蹤的項目不重複列入：

- [暫緩項目集中清單](deferred-items.md)（28 項）
- [安全限制 SEC-01～15 與「還沒有檢查的」](../security/known-issues.md)
- [路線圖「暫不排入」](middleware/middleware-roadmap.md#暫不排入記錄理由)

本文件只做盤點與建議，**不是實作計畫，也不承諾工期**。每一項要動工前，先另開分支、寫計畫，再依[實作共用流程](middleware/implementation-guidelines.md)進行。「現況證據」欄記錄當日的查核方式，實作前請重新確認。

## 總表

| 編號 | 類別 | 缺口 | 建議優先度 |
|---|---|---|---|
| G-01 | 工程流程 | CI 流水線 | 高 |
| G-02 | 工程流程 | 映像檔發佈與版本追溯 | 中 |
| G-03 | 工程流程 | 持續的依賴／映像漏洞掃描 | 中 |
| G-04 | 維運 | 資料庫備份、還原與演練 | 高 |
| G-05 | 維運 | 自動擴展（HPA） | 低 |
| G-06 | 維運 | 對外入口與 TLS 終結設計 | 中 |
| G-07 | 維運 | 環境分層（dev／staging／prod overlay） | 中 |
| G-08 | 維運 | Secret 管理與輪替 | 中 |
| G-09 | 維運 | SLO 與錯誤預算 | 低 |
| G-10 | 測試 | 壓力與效能測試 | 中 |
| G-11 | 測試 | 跨服務事件契約測試 | 中 |
| G-12 | 測試 | 可重複的故障演練 | 低 |
| G-13 | 業務 | 收件地址與物流資訊 | 高 |
| G-14 | 業務 | 退款與售後流程 | 中 |
| G-15 | 業務 | 帳號刪除與個資匿名化 | 中 |
| G-16 | 業務 | 後台操作歷程查詢 | 低 |
| G-17 | 業務 | 其他電商常見功能 | 低（排在開發流程之後） |

優先度的判斷依據：「高」表示目前流程上已經有斷點，或日後補做的成本會隨時間增加；「低」表示到了特定規模或上線前才需要。

## 一、工程流程

### G-01 CI 流水線

- **狀態**：實作計畫見 [G-01 CI 流水線實作計畫](dev-workflow/ci-implementation-plan.md)（2026-11-13 建立）。
- **現況證據**：`.github/` 底下只有 `modernize/java-upgrade/hooks`，沒有 `.github/workflows`，也沒有其他 CI 設定。
- **影響**：後端 `./mvnw clean test`（含 Testcontainers）、前端 Vitest、type-check、lint 都靠手動執行。PR 合併前沒有自動檢查，目前已經累積 25 個 PR。
- **建議範圍**：
  1. PR 觸發：後端依變更模組執行 `-pl <module> -am test`，`common` 有變更時跑全部。
  2. 兩個前端各自執行 `npm ci`、type-check、lint、`test:unit`。
  3. Playwright E2E 需要完整後端，先不放進 PR 必跑，可改成手動或排程觸發。
  4. 快取 Maven 與 npm 依賴。
- **注意**：gh 目前沒有授權，branch protection 要由使用者在 GitHub 網頁上設定。

### G-02 映像檔發佈與版本追溯

- **現況證據**：`infra/k8s/services/*.yaml` 的映像檔都寫死 `my-microservice/<service>:0.1.0`，只用 kind 在本機載入，沒有 registry。
- **影響**：無法從執行中的 Pod 回推是哪一個 commit，回滾只能重新建置。
- **建議範圍**：CI 建置後推到 registry（例如 GHCR），以 commit SHA 當標籤，manifest 改用 kustomize `images:` 覆寫標籤；映像檔加上 OCI label（`org.opencontainers.image.revision`）。
- **相依**：G-01。

### G-03 持續的依賴／映像漏洞掃描

- **現況證據**：沒有 Dependabot、Renovate、OWASP dependency-check、Trivy 設定。[安全清單「還沒有檢查的」](../security/known-issues.md#還沒有檢查的)記錄了 Maven 依賴沒掃，但那是一次性檢查，這裡缺的是持續機制。
- **建議範圍**：Dependabot（Maven、npm、GitHub Actions、Dockerfile 基底映像），CI 加入 Trivy 掃描映像檔，有 Critical 弱點時讓建置失敗。
- **相依**：G-01；跟 known-issues 的一次性檢查分開處理。

## 二、維運與可靠性

### G-04 資料庫備份、還原與演練

- **現況證據**：repo 中沒有 `pg_dump`、WAL 封存、pgBackRest、Velero 等設定或文件。
- **影響**：每個服務各自有一個 PostgreSQL，資料遺失後沒有恢復路徑。另外，只把**單一服務**還原到過去的時間點時，跨服務狀態會不一致，例如 order 回到過去，但 inventory 的預扣與 Kafka 事件仍是新的。
- **建議範圍**：
  1. 先定 RPO／RTO 目標（雛型可以寬鬆，但要寫下來）。
  2. 定期備份與保留期限；kind 環境至少要能示範 `pg_dump`／restore。
  3. 寫一份還原 runbook，說明單一服務還原後如何處理 Outbox 未送事件、`processed_event` 去重表，以及跨服務對帳。
  4. 實際演練一次並留下證據。
- **相依**：可以跟[通知 Email 個資保留](deferred-items.md)一起決定備份中個資的保留期限。

### G-05 自動擴展（HPA）

- **現況證據**：七個 Deployment 都固定 `replicas: 2`，沒有 HorizontalPodAutoscaler。
- **建議範圍**：先做 G-10 取得負載數據，再決定指標（CPU 或 Kafka consumer lag）與上下限。consumer 數量受 topic partition 數限制，擴展上限要對齊 partition。
- **相依**：G-10；kind 需要另外安裝 metrics-server。

### G-06 對外入口與 TLS 終結設計

- **現況證據**：沒有 Ingress 或 Gateway API 資源。[暫緩清單](deferred-items.md)有「TLS 終結處 HSTS」，但前提「TLS 在哪裡終結」本身還沒有設計。
- **建議範圍**：決定入口（ingress-nginx 或 Gateway API），規劃 TLS 憑證來源（cert-manager）、兩個前端與 `/api` 的路由，以及 `ClientIpResolver` 信任哪一層的 `X-Forwarded-For`。
- **相依**：完成後才能處理 HSTS 暫緩項目；跟 G-07 一起做。

### G-07 環境分層

- **現況證據**：`infra/k8s/kustomization.yaml` 只有一份，沒有 base／overlays 結構。
- **影響**：[SEC-03](../security/known-issues.md#sec-03-開發環境的預設設定不能帶上線)指出開發設定不能帶上線，但目前沒有結構可以把它們分開。
- **建議範圍**：拆成 `base/` 加上 `overlays/kind`（現況）與 `overlays/prod`（範本），把副本數、資源限制、映像檔標籤、Mailpit 是否部署等差異放進 overlay。

### G-08 Secret 管理與輪替

- **現況證據**：Secret 由 `infra/k8s/scripts/create-secrets.sh` 在本機產生後直接套用，沒有 Sealed Secrets、External Secrets、Vault 等機制。
- **建議範圍**：選一種可以進版控或對接外部保管的方式；寫下 DB 密碼、JWT 金鑰、物件儲存金鑰的輪替步驟。JWT 金鑰輪替的實際演練已列在 known-issues，這裡補的是整體流程與工具。

### G-09 SLO 與錯誤預算

- **現況證據**：已經有 12 條告警與 runbook，但沒有服務等級目標，告警門檻沒有可以對照的依據。
- **建議範圍**：先替關鍵使用者路徑（瀏覽、搜尋、建單、付款回呼）定可用性與延遲 SLO，再檢查現有告警是否改成 burn-rate 告警。
- **相依**：G-10 的數據可以協助定門檻。

## 三、測試層級

### G-10 壓力與效能測試

- **現況證據**：沒有 k6、Gatling、JMeter、Locust 等腳本。[最佳化審查](reviews/codebase-optimization-review.md)的驗證是靠 EXPLAIN 和推理，沒有負載下的數據。
- **建議範圍**：以 k6 撰寫情境：
  1. 熱門商品同時建單，驗證庫存不超賣，並量測條件式更新的鎖競爭。
  2. 搜尋與列表的吞吐量與 P95。
  3. Gateway 限流門檻是否合理。
  4. Outbox relay 在突發量下的延遲與 consumer lag。
- **注意**：kind 單機的數字只能做相對比較，不能當成容量規劃依據。

### G-11 跨服務事件契約測試

- **現況證據**：事件格式只寫在[事件契約](../architecture/event-contracts.md)，沒有 Pact、Spring Cloud Contract 或 schema 驗證，也沒有 Schema Registry。
- **影響**：producer 改了欄位或型別，consumer 的測試仍然會通過，要等到執行期才會發現。
- **建議範圍**：最輕量的做法是把每個事件的 JSON Schema（或範例 payload）放進 `common`，producer 測試驗證「產出的事件符合 schema」，consumer 測試驗證「範例 payload 可以反序列化」。
- **相依**：無；可以先挑 `OrderCreated`、`PaymentSucceeded` 試做。

### G-12 可重複的故障演練

- **現況證據**：階段 C～E 做過一次性的停機驗證（ES、SMTP、Kafka 等，證據在 `middleware/evidence/`），但沒有可以重複執行的劇本。
- **建議範圍**：把既有的驗證步驟整理成腳本（可以延續 `verify-phase-*.ps1` 的形式），至少涵蓋：Kafka 停機後 Outbox 補送、ES 停機退回 SQL、下游服務停機時 Gateway 熔斷。

## 四、業務功能

### G-13 收件地址與物流資訊

- **現況證據**：order-service 的程式碼與 Flyway migration 中沒有任何 address 欄位；後台可以把訂單標成出貨，但訂單沒有寄送目的地。
- **影響**：這是業務流程上真正的斷點，出貨這一步實際上無法執行。
- **使用者決定（2026-11-13）**：
  1. 做**會員地址簿**，會員可以管理多筆地址。
  2. 第一次購物（地址簿是空的）時，在結帳頁直接填寫地址。
  3. 結帳頁提供「記住這個地址」勾選，勾選後把這筆地址存進地址簿；之後結帳可以從地址簿挑選。
- **建議範圍**：
  1. user-service 新增地址簿 API（`/api/v1/users/me/addresses` 的 CRUD），限制每人筆數，並可設定預設地址。
  2. 建單時帶入收件人、電話、地址，寫進訂單快照，不引用地址簿的資料列，之後修改或刪除地址簿不影響既有訂單。
  3. 「記住這個地址」由前台在建單成功後呼叫地址簿 API，不讓 order-service 跨服務寫入 user 的資料；存檔失敗不影響訂單，只提示使用者。
  4. 出貨時記錄物流商與追蹤編號。
  5. 前台結帳表單與地址簿頁、後台訂單詳情、出貨通知信都要顯示。
  6. 地址與電話是個資，要一併考慮 G-15 與 log 遮罩。
- **相依**：地址簿頁會用到顧客「帳號頁」，可以跟[暫緩清單](deferred-items.md)的「個人資料修改／改密碼／忘記密碼」一起規劃頁面結構。

### G-14 退款與售後流程

- **現況證據**：`PaymentEventListener` 在「已取消的訂單收到付款」時只寫一行 `manual refund review required` 的 error log，沒有退款狀態或後續流程。[暫緩清單](deferred-items.md)的「自動退款」是條件式候選，階段 F（真實金流）明確不做。
- **建議範圍**：即使維持模擬金流，也至少需要：
  1. 「待人工退款」的狀態與後台清單，取代只寫 log。
  2. 退款事件，並在退款完成後歸還庫存（如果適用）。
  3. 是否支援退貨或部分退款，由使用者決定。
- **相依**：跟暫緩清單的「自動退款」合併評估，避免重複規劃。

### G-15 帳號刪除與個資匿名化

- **現況證據**：user-service 沒有刪除或停用帳號的 API，也沒有匿名化處理；暫緩清單只涵蓋通知 Email 的保留期限。
- **影響**：個資分散在 user、order（將來的 G-13 地址）、notification（`user_contact`）三處，日後才補，需要回頭追所有副本。
- **建議範圍**：定義 `UserDeleted` 事件，各服務自行匿名化或刪除自己持有的副本；訂單保留交易紀錄，但移除可識別欄位。
- **相依**：G-13 的地址欄位設計時就要考慮匿名化。

### G-16 後台操作歷程查詢

- **現況證據**：`common/audit/HeaderAuditorAware` 加上各服務的 `JpaAuditingConfig`，只記錄最後一次的 `createdBy`／`updatedBy`；沒有變更歷史表，管理後台也沒有歷程頁面。
- **影響**：無法回答「誰在什麼時候把價格從 A 改成 B」。
- **建議範圍**：對價格、庫存調整、訂單狀態、角色變更保留變更歷史（可以用 Hibernate Envers 或自訂歷史表），並在後台提供查詢。

### G-17 其他電商常見功能

優惠券、商品評價、發票、運費計算。

**使用者決定（2026-11-13）**：將來要做，但排在開發流程與測試基礎（G-01～G-03、G-10～G-12）建立之後。屆時再從這裡移出，各自建立計畫。

## 建議順序

使用者決定（2026-11-13）：**先建立開發流程與測試基礎，再做新的業務功能。**

1. **開發流程**：G-01 CI → G-03 漏洞掃描 → G-02 映像檔發佈。G-02、G-03 都依賴 G-01。
2. **測試基礎**：G-11 契約測試 → G-10 壓測 → G-12 故障演練。G-10 的數據再回饋給 G-05、G-09。
3. **G-13 收件地址與地址簿**：第一個新業務功能，可以直接在新的 CI 與契約測試保護下開發；設計時一起考慮 G-15。
4. **G-04 備份與還原**：服務與資料庫數量多，最容易被忽略、出事時代價最高。
5. 其餘項目在要對外部署前（G-06、G-07、G-08）處理；G-14、G-17 排在以上完成之後。
