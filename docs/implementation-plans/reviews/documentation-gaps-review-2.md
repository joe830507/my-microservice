# 文件缺口檢討（第二輪）

- 日期：2026-11-16
- 範圍：repo 內版控中的 51 份 Markdown，並少量對照程式（`ProductClient`、`infra/scripts/`、`.gitignore`、`.github/`）
- 方式：靜態閱讀；沒有執行連結檢查、測試或建置。以下交給 Codex 處理後再審查
- 前一輪：[文件缺口檢討](documentation-gaps-review.md)（2026-11-13，四項已結案）

前一輪補上了告警手冊、安全總表、索引與暫緩清單。這一輪的重點是三件事：架構參考有跟程式不一致的地方、追蹤文件的數字過期，以及測試與本機開發的知識散落在各實作紀錄裡。

## 總覽

| # | 優先 | 類型 | 項目 |
|---|---|---|---|
| 1 | 高 | 修正 | 架構參考的下單流程圖與程式不一致 |
| 2 | 高 | 修正 | 架構參考缺 product-service 的 API 表與 notification 說明 |
| 3 | 低 | 修正 | 文件索引的分類說明過期 |
| 4 | 低 | 修正 | 系統缺口評估的過期數字 |
| 5 | 低 | 修正 | 管理後台紀錄的參考圖說明不一致 |
| 6 | 高 | 新文件 | 測試策略與測試現況 |
| 7 | 中 | 新文件 | 本機開發與疑難排解 |
| 8 | 中 | 結構 | 「文件維護」流水帳移出文件索引 |
| 9 | 低 | 結構 | 安全清單「還沒有檢查的」加上編號 |

共同要求沿用 `docs/README.md`：繁體中文、UTF-8 無 BOM、相對路徑連結；同一主題更新既有章節，不拆小檔；只寫有依據的內容，沒有驗證過的不要宣稱驗證過。歷史驗收紀錄與當時的數字不回頭改。

---

## 1.（高）下單流程圖與程式不一致

> 2026-11-16 完成註記：流程圖第 1 步改為 `/api/v1/products/batch` 並補說明。對照 `ProductClient`、`InventoryClient`、payment `OrderClient` 的路徑，以及 order／payment／inventory／notification 的 `@KafkaListener`，另發現並修正三處：order 的 `/internal/v1/orders/{id}/paid` 已沒有呼叫方（payment 只呼叫 GET）、`order.events` 的消費者漏了 payment（只處理 `OrderCancelled`）、`payment.events` 漏了 notification（只處理 `PaymentFailed`）；`product.events` 的消費者改為 product 自己的搜尋索引 listener。「雛型階段」節標為歷史。

現況：`docs/architecture/microservice-architecture.md`「服務互動：下單流程」的第 1 步寫 order 呼叫 `GET /internal/v1/products/batch`。實際上 `backend/spring-order-service/src/main/java/com/mymicroservice/order/clients/ProductClient.java` 第 43 行呼叫的是 `/api/v1/products/batch`。同一份文件「本機 port 分配」下方的說明，以及[暫緩清單](../deferred-items.md)的「商品批次讀取移至 internal API」，都說搬到 internal 是之後的事。

要做的事：

- 把流程圖第 1 步改成實際路徑，並在圖下方用一句話連到暫緩清單的那一項。
- 流程圖其餘各步（第 2、4 步的 internal 路徑，第 5～7 步的事件名稱與 topic）逐一對照 order、payment、inventory 的 client 與 listener 程式。有不一致就一起修，並在完成註記列出核對了哪些類別。
- 同一份文件的「雛型階段：同步 REST」與「階段 C：事件驅動」兩節，確認讀者看得出哪一段是歷史、哪一段是現況；看不出來就在節首加一行說明，不改寫歷史內容。

## 2.（高）架構參考缺 product-service 的 API 表

> 2026-11-16 完成註記：依 `ProductController`、`ProductImageController`、`CategoryController`、`InternalProductSearchController` 與 `ProductQueryRequest` 新增 product-service API 表（含角色），並新增 notification-service 一節；其他四個服務的 API 表逐列對照 controller，沒有發現漏列。未啟動服務實測端點。

現況：「各服務最小 API」只有 user、inventory、order、payment 四個服務與 Gateway 路由。product-service 是來源檔最多的服務（76 個），它的 API 只出現在「服務清單」的一格文字裡。notification-service 沒有對外 API，但文件沒有說明這一點。

要做的事：

- 新增「product-service」一節，格式比照其他服務。內容以 controller 程式為準，涵蓋商品、分類、圖片（含 presigned 上傳流程的端點）、關鍵字搜尋，以及內部的索引重建 API。每一列標明需要的角色（公開／登入／ADMIN／internal）。
- 新增「notification-service」一小節，說明沒有對外業務 API、由哪些事件觸發，並連到[通知類別導讀](../../class-relationships/spring-notification-service.md)。
- 順便核對其他四個服務的 API 表有沒有漏掉後來新增的端點（例如訂單出貨／完成、付款回呼）。只補缺漏，不重排既有內容。
- 「服務清單」中 product-service 那一格的長說明，在 API 表完成後可以縮短成一句話加上節內連結。

## 3.（低）文件索引的分類說明過期

> 2026-11-16 完成註記：改寫 `implementation-plans/` 一列；新文件已加進索引與根 README。

現況：`docs/README.md` 分類表的 `implementation-plans/` 一列，還寫「跨層的後端部署計畫放在根目錄」。部署計畫已經搬到 `backend/`，這一列也沒有提到 `reviews/`、`evidence/`、`dev-workflow/`。

要做的事：改寫這一列，跟下方「進行中的追蹤」一節的說明一致。本文件與第 6、7 項的新文件也要加進索引與根目錄 `README.md` 的「文件」表。

## 4.（低）系統缺口評估的過期數字

> 2026-11-16 完成註記：移除「（28 項）」；「25 個 PR」改為標明日期 2026-11-13。

現況：`docs/implementation-plans/system-gaps-assessment.md`

- 開頭寫暫緩清單「28 項」。暫緩清單 2026-11-13 重新分類後，「處理時機」表的加總是 22 項。
- G-01 寫「目前已經累積 25 個 PR」，現在已經到 #29。

要做的事：

- 「28 項」改成不寫死數字（例如只留連結），避免之後再過期。
- 「25 個 PR」是 2026-11-13 的現況證據，保留原數字並加上日期即可，不要改成新的數字。
- `docs/README.md`「文件維護」中的「28 項」是歷史紀錄，不改。

## 5.（低）管理後台紀錄的參考圖說明不一致

> 2026-11-16 完成註記：第 339 行補上「只留在本機，不放進 repo」；沒有變動圖片或 `.gitignore`。

現況：`.gitignore` 把 `docs/pictures/` 排除（設計參考圖是別人的作品，只留在本機）。`docs/implementation-plans/vue-management/README.md` 第 415 行引用 `dashboard_01.png` 時有註明「只留在本機，不放進 repo」，第 339 行引用登入頁參考圖時沒有註明，讀者會以為檔案遺失。

要做的事：第 339 行補上同樣的註明。不要把圖片加進版控。

## 6.（高）測試策略與測試現況

> 2026-11-16 完成註記：新增 [testing-overview.md](../dev-workflow/testing-overview.md)。實際執行：`common`（67 測試通過，51 秒）與兩個前端的 Vitest（vue-customer 344、vue-management 267 通過）；其他後端模組的數字是靜態計數，未執行。掃描發現 CI 計畫 P1～P4 以外的兩點，**僅記錄、未修改計畫或程式**：F1 瀏覽器測試用 `channel: 'chrome'`，與 CI 計畫安裝 chromium 的步驟不一致；F2 固定 dev server port。另補充 P4 實際是四個測試類別都會建置 ES 映像、固定 port 是 19200／19201。F1 已由使用者決定維持 `channel: 'chrome'`，CI 計畫已更新（使用 runner 內建 Chrome，不安裝瀏覽器；第一次執行驗證）；P4 的說明也已改正。

現況：測試分層只在[系統導讀](../../architecture/system-guide.md)第 10 節有一張四列的表。各模組有哪些測試、哪些需要 Docker、哪些依賴本機狀態，都沒有集中記錄。[CI 計畫](../dev-workflow/ci-implementation-plan.md)的 P1～P4 就是在寫計畫時才臨時查出來的。

這份文件是 G-01 CI 的輸入，要在 CI 動工前完成。

要做的事：

- 新增 `docs/implementation-plans/dev-workflow/testing-overview.md`。
- **後端**：每個模組一節（9 個模組），用表格列出測試類別。欄位：類別名稱、類型（單元／切片／整合）、使用的 Testcontainers 或外部資源（PostgreSQL、Redis、Kafka、ES、Mailpit、WireMock）、使用的 profile、備註。
- **前端**：兩個應用各一節，列出 `package.json` 中跟測試、lint、格式、型別檢查有關的 script，各自的用途，以及會不會修改檔案；Vitest、免後端的 Playwright（`test:browser`）、需要後端的 E2E（`test:e2e`）分開說明前置條件。
- **環境依賴**獨立一節，逐項列出並標明來源：
  - 沒有使用 test profile 的測試類別（全部掃過，不只 CI 計畫 P1 提到的那一個）。
  - 使用固定 host port 的測試。
  - 即時建置映像或在容器裡執行建置的測試（CI 計畫 P3、P4）。
  - 依賴 `~/.m2`、`secrets/` 或其他不進版控的檔案的測試。
- **怎麼執行**：單一模組、全部、離線（`-o`）的指令，以及 `-am` 的理由；可以連到系統導讀與根 README，不要重複整段內容。
- 測試數量與執行時間：只寫這次實際執行得到的數字，並註明日期與環境。沒有執行的模組寫「未執行」，不要引用舊紀錄的數字當成現況。
- 如果掃描時發現 CI 計畫 P1～P4 以外的新問題，**不要直接修程式**，在本文件對應段落下方列出，並回報由 Claude 決定要不要加進 CI 計畫。
- 系統導讀第 10 節「測試分層與成本」加上連到新文件的連結，原表保留。

## 7.（中）本機開發與疑難排解

> 2026-11-16 完成註記：新增 [local-development.md](../../operations/local-development.md)。notification 的預設連線值對照 `application.yml`、`local-backend.sh`、`docker-compose.yml`；**指令沒有實際執行**（撰寫時 compose 基礎設施沒有在執行）。疑難排解表每列連回來源。`local-backend.sh` 原先沒有修改；使用者隨後決定採用 `WITH_NOTIFICATION=1`，已加入腳本（只做 `bash -n` 語法檢查，未實際啟動），這是本輪唯一的非文件變更。

現況：根目錄 `README.md` 有啟動步驟，但：

- `infra/scripts/local-backend.sh` 不會啟動 notification-service。根 README 與系統導讀都只寫「需另外啟動」，沒有寫怎麼啟動。
- 常見的失敗情況散落在各實作紀錄：缺 `secrets/` 時 context 啟動失敗、Windows Docker Desktop 的 host port mapping、ES 映像要先建置、沒加 `-am` 會用到過期的 `common`、Node 版本限制。

要做的事：

- 新增 `docs/operations/local-development.md`，並在 `docs/README.md`「維運」表加一列。
- 內容：
  1. notification-service 在本機的啟動方式（指令、需要的環境變數、依賴的 Kafka／DB／Mailpit）。以 `application.yml` 與 `local-backend.sh` 的寫法為準；指令要實際執行過一次，沒有執行就註明沒有執行。
  2. `local-backend.sh` 的子指令與 `OBSERVE=1` 的效果，log 與 PID 放在哪裡。
  3. 疑難排解表：現象、原因、處理方式、來源文件。只收錄文件或程式裡有依據的情況，每一列連回來源。
  4. 開發資料庫中的測試資料（連到[安全清單「探測遺留的測試資料」](../../security/known-issues.md#探測遺留的測試資料)），不執行任何清除。
- 根 README「本機執行」第 3 步與系統導讀「開始執行前」加上連結，原文保留。
- 評估要不要讓 `local-backend.sh` 支援啟動 notification-service：**這次不改腳本**，只在文件末尾寫下評估結果，由使用者決定。

## 8.（中）「文件維護」流水帳移出文件索引

> 2026-11-16 完成註記：新增 [CHANGELOG.md](../../CHANGELOG.md)，原有 2026-11-12～13 的紀錄逐段原文搬入（含「整理驗證」一段），文件索引只留規則與連結；已結案審查文件中對「文件維護」的舊引用維持原文。

現況：`docs/README.md` 的「文件維護」一節有 30 行逐次整理紀錄，比索引本身還長，而且每次整理都會再增加。

要做的事：

- 新增 `docs/CHANGELOG.md`，把逐次紀錄原文搬過去（新的在上，內容一字不改）。
- `docs/README.md`「文件維護」只保留維護規則（最後兩段「後續同一功能的補充……」的原則，加上本文件的共同要求），並連到 `CHANGELOG.md`。
- 本輪的整理紀錄直接寫進 `CHANGELOG.md`。
- 搬移後檢查有沒有其他文件引用「`docs/README.md` 的文件維護」（前一輪檢討有提到），依現況更新說法；已結案的審查文件內文不改，只在必要時加註。

## 9.（低）安全清單「還沒有檢查的」加上編號

> 2026-11-16 完成註記：六項依序加上 CHK-01～06，章節標題不變；暫緩清單兩列改用編號引用，狀態沒有更動。

現況：`docs/security/known-issues.md`「還沒有檢查的」有六項，沒有編號。暫緩清單只能整包引用成「剩餘安全驗證工作」，其中 Maven 依賴一項又另外拆給 G-03，對照起來不方便。

要做的事：

- 六項依序加上 `CHK-01`～`CHK-06` 編號，內容不改，章節標題不改（既有錨點要保持有效）。
- 暫緩清單的兩列（「Maven 依賴漏洞」與「Maven 依賴以外」）改用編號引用。
- 這是文件編號，不是新的安全檢查結論，不要更動任何一項的狀態。

---

## 這一輪不做的（已檢視，留待之後）

| 項目 | 原因 |
|---|---|
| 各服務資料模型總覽（資料表、約束、索引） | 有價值，但 G-13 地址簿的設計會先動到 user 與 order 的 schema；跟 G-13 設計文件一起寫 |
| 部署／升級／回滾與備份還原 runbook | 依賴 G-02（映像檔版本）與 G-04（備份）的決定，現在寫只能寫出 kind 的手動步驟 |
| G-13 地址簿設計文件 | 依使用者決定，排在開發流程與測試基礎之後 |
| CI 計畫的完成紀錄 | G-01 尚未動工，不是文件缺口 |

## 完成後請回報

- Commit 分法：第 1、2 項一個 commit；第 3、4、5、9 項一個 commit；第 6、7、8 項各一個 commit。
- 每一項完成後，在本文件對應段落的標題下方加上「完成註記」（格式比照前一輪），寫明做了什麼、對照了哪些程式、哪些沒有驗證。
- 附上全庫本機連結與章節錨點檢查、UTF-8 無 BOM 檢查、`git diff --check` 的結果。
- 第 6 項註明哪些模組的測試實際執行過；第 7 項註明哪些指令實際執行過。
- 決定不做或改變做法的項目，在對應段落下方註明原因。
- 除了文件以外不修改任何程式、腳本或設定；過程中發現的程式問題只記錄、不修正。

## 本次完成與驗證紀錄（2026-11-16）

- 提交：第 1～2 項、第 3／4／5／9 項、第 6 項（含系統導讀連結）、第 7／8 項各一個 commit。
- 全庫 55 份 Markdown（含新文件）的 1,228 個本機連結與章節錨點檢查通過（自寫腳本，錨點規則仿 GitHub，非 GitHub 實際渲染）；UTF-8 無 BOM、無替代字元通過；`git diff --check` 通過。
- 實際執行的測試只有 `common` 與兩個前端 Vitest；沒有執行其他後端模組、前端 lint／type-check／build／Playwright，沒有啟動任何服務，沒有渲染 Mermaid。
- 除文件外，只有 `infra/scripts/local-backend.sh` 加了 `WITH_NOTIFICATION=1`（使用者決定）。
- F1 已處理，沒有開放問題。
