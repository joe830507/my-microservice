# 文件缺口檢討

- 日期：2026-11-13
- 範圍：repo 內全部 44 份 Markdown（`docs/`、根目錄與前端 README、`infra/k8s/README.md`），並對照 `infra/observability/alerting.yaml`
- 方式：靜態閱讀；以下是需要補上的文件與修正，交給 Codex 處理後再審查

整體來說，文件已經相當完整：系統導讀、架構參考、事件契約、各後端模組類別導讀、前端元件導讀、各階段計畫與實作紀錄、安全清單都有，也有一致的維護規則（`docs/README.md` 的「文件維護」）。以下四項是目前的缺口，依優先順序排列。

## 總覽

| # | 優先 | 類型 | 項目 |
|---|---|---|---|
| 1 | 高 | 新文件 | 12 條告警只有 DLT 一條有處理手冊 |
| 2 | 中 | 修正 | 安全總表缺 SEC-14、SEC-15 |
| 3 | 低 | 修正 | 文件索引沒有收錄最佳化審查與本文件 |
| 4 | 中 | 新文件 | 「暫緩」項目分散在多份文件，沒有一處能看全 |

共同要求（沿用 `docs/README.md`）：繁體中文、UTF-8 無 BOM、相對路徑連結；同一主題更新既有章節，不拆小檔；只寫有依據的內容，沒驗證過的不要宣稱驗證過。

---

## 1.（高）告警處理手冊

現況：`infra/observability/alerting.yaml` 有 12 條告警規則（由 `infra/observability/generate_dashboard.py` 產生），每條只有一行 `summary`。只有「Kafka 事件進入 DLT」在 `docs/architecture/event-contracts.md` 的「DLT 處理與重放」有完整處理步驟，其他 11 條告警響了之後，沒有文件說明要看哪裡、怎麼判斷、怎麼處理。

告警清單（依 `alerting.yaml` 順序）：

1. 服務 5xx 錯誤率偏高
2. 服務延遲偏高（p95 > 2 秒）
3. 排程執行失敗
4. 服務沒有回報指標
5. 熔斷器打開
6. 副本數少於 2
7. 預扣被拒偏多
8. Kafka consumer lag 偏高
9. 搜尋持續退回 SQL
10. Kafka 事件進入 DLT
11. 通知信件寄送失敗（FAILED）
12. 通知信件積壓

要做的事：

- 新增 `docs/operations/alert-runbook.md`，並在 `docs/README.md` 的分類表加一列 `operations/`（維運：告警處理、日常操作）。
- 每條告警一節，固定包含：
  - **觸發條件**：照抄 `alerting.yaml` 的查詢、門檻與持續時間（`for`），不要自己改寫數字。
  - **代表什麼**：對使用者或業務的影響（例如 lag 偏高代表付款後訂單晚變成 PAID）。
  - **先看哪裡**：儀表板 `my-microservice-overview` 的哪個面板、Loki／Tempo 的查詢方式、相關 metric 名稱。
  - **常見原因與處理**：依原因分流；需要指令的，Compose 與 kind 各給一個範例（格式比照 event-contracts 的 DLT 章節）。
  - **相關文件**：連到系統導讀「8. 故障時會發生什麼」、事件契約、對應模組的類別導讀。
- 「Kafka 事件進入 DLT」不要重寫，簡短說明後連到 event-contracts 的「DLT 處理與重放」章節。
- 需特別寫清楚的連動：
  - consumer lag：第 19 點修正後，暫時性錯誤會無限重試並卡住 partition，lag 上升可能代表 DB／下游故障，而不是消費太慢；要先查 consumer log 裡的重試訊息。
  - 預扣被拒偏多：區分「真的缺貨」與「被刷」，對照 Gateway 限流與庫存。
  - 通知信件積壓／FAILED：對照 `email_message` 的狀態與 lease 欄位（`SENDING`、`lease_until`），以及 SMTP 逾時設定。
  - 熔斷器打開：列出熔斷名稱與它保護的下游（見 `spring-api-gateway.md`、order／payment 的類別導讀）。
- 指標名稱與面板名稱要以 `generate_dashboard.py`、`alerting.yaml` 與程式碼為準，逐一核對；如果能在 kind 或本機 Prometheus 實際查詢，請在文件末尾記錄哪些查詢實際執行過，沒執行的就寫沒執行。

## 2.（中）安全總表缺 SEC-14、SEC-15

現況：`docs/security/known-issues.md` 內文有「SEC-14 Elasticsearch 尚未啟用認證與 TLS」與「SEC-15 通知服務的測試 SMTP 與 Email 個資保護」，但最上面的「總表」只列到 SEC-13。這份文件標明上線前必讀，只看總表會漏掉這兩項。

要做的事：

- 在總表補上 SEC-14、SEC-15 兩列，嚴重度、狀態與預計處理依內文判斷，格式比照 SEC-13（錨點連結要能正確跳到章節）。
- 順便確認 SEC-13～15 三節在文件中的位置：目前排在「驗證過沒有問題的」「還沒有檢查的」「探測遺留的測試資料」之後，若要移到 SEC-12 後面，章節錨點與其他文件的引用要一併檢查。

## 3.（低）文件索引沒有收錄最佳化審查

現況：`docs/implementation-plans/codebase-optimization-review.md` 與 `evidence/codebase-optimization-validation.md` 沒有被任何文件連結，`docs/README.md` 與根目錄 `README.md` 都找不到入口。

要做的事：

- `docs/README.md` 的「實作計畫」表加一列最佳化審查（狀態：20 項完成、兩輪修正後審查結案、暫緩項目見第 4 項的清單），以及本文件（`documentation-gaps-review.md`）。
- 第 1、4 項新增的文件也要加進索引。
- 依慣例在「文件維護」加一段本次整理的紀錄（異動文件數、連結檢查結果、未執行的驗證）。

## 4.（中）暫緩項目的集中清單

現況：「暫緩」代表雛型完成後要補，不是放棄，但目前分散在：

- `middleware-roadmap.md`：階段 E 的 E7 真實信箱驗證、註冊確認信（SEC-04）、通知上線前的歷史收件人／訂單資料、真實 SMTP 認證與 TLS。
- `codebase-optimization-review.md`：第 7 點後端獨立縮圖（方案二）、第 13 點統計 API、第 18 點 CDS／AOT cache、第 20 點自動退款（與階段 F 相關）。
- `security/known-issues.md`：各 SEC 項目中「上線前要補」的部分，例如 SEC-13 Kafka 認證、SEC-14 ES 認證與 TLS。
- 各模組計畫與實作紀錄中零星的暫緩說明。

要做的事：

- 新增 `docs/implementation-plans/deferred-items.md`，用一張表列出所有暫緩項目：項目、來源文件（連到原章節）、暫緩原因、要補的前提或觸發條件、預估影響範圍。
- 要掃過全部文件找出「暫緩」，不要只列上面這些；`middleware-roadmap.md` 的「暫不排入」是決定不做，不要混進來（可以在清單開頭說明兩者差別）。
- 原文件的暫緩說明保留，只在原處加一個連到清單的連結，不要搬移內容。
- E7 真實信箱驗證需要使用者提供信箱，清單只寫「執行時再向使用者確認收件信箱」，不要把任何實際信箱寫進 repo。

---

## 不需要補的（已檢視）

- 後端專用 README：根目錄 README 已經涵蓋後端執行方式，兩個前端也有各自的 README。
- API 文件：已有 springdoc（預設關閉，見 SEC-09），`microservice-architecture.md` 也列了各服務 API。
- 貢獻指南、ADR：單人開發，決策已記錄在路線圖「已定的決定」與各計畫中。
- `observability-implementation-plan.md` 的「告警規則 5 條」：那是階段 A 當時的驗收紀錄，依 `docs/README.md` 的規則，歷史數字不回頭改。

## 完成後請回報

- 第 2、3 項合成一個 commit；第 1、4 項各一個 commit。
- 附上本機連結與章節錨點檢查、UTF-8 無 BOM 檢查、`git diff --check` 的結果。
- 第 1 項註明哪些告警查詢實際執行過；決定不做或改變做法的項目，在本文件對應段落下方註明原因。
