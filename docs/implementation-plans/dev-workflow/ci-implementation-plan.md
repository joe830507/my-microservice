# G-01 CI 流水線實作計畫

建立日期：2026-11-13。來源是[系統缺口評估 G-01](../system-gaps-assessment.md#g-01-ci-流水線)。依使用者決定，開發流程與測試基礎排在新業務功能之前，這是第一項。

由 Codex 實作，Claude 審查。依「實作步驟」的順序，每一步一個 commit；完成後在本文件最後的「完成紀錄」填寫結果。

## 目標

PR 送出後自動執行後端測試、前端檢查與測試；只要有一項失敗，PR 上就看得到紅燈。上一輪審查（[T-01～T-03 審查](../reviews/deferred-now-tasks-review.md) R1）發生過「只跑部分測試、漏掉既有測試失敗」的情況，CI 要讓這種情況在合併前就被擋下來。

## 範圍

**這次要做**：

- `.github/workflows/ci.yml`：PR 與 `main` 的自動檢查。
- 後端：依變更的模組執行 `./mvnw -pl <module> -am test`，含 Testcontainers 整合測試。
- 前端（兩個應用）：lint、格式檢查、型別檢查、Vitest、建置、免後端的 Playwright 瀏覽器測試。
- K8s manifest 能不能正常 render。
- 一個彙總結果的 job，作為 branch protection 唯一的必要檢查。
- 修正下面「CI 的前置問題」，讓測試在乾淨環境也能跑。

**這次不做**：

| 項目 | 原因 |
|---|---|
| 映像檔建置與推送 registry | G-02 |
| 依賴與映像漏洞掃描 | G-03 |
| 需要完整後端的 Playwright E2E（`test:e2e`） | 需要啟動全部服務與管理員帳號，成本高；之後評估改用排程或手動觸發 |
| kind 部署驗證 | 成本高；維持在本機手動驗證 |
| 依賴快取以外的建置加速（例如 ES 測試映像快取） | 先量測第一版的執行時間再決定 |

## CI 的前置問題

撰寫計畫時查核現況，發現以下問題會讓測試在乾淨的 CI 環境失敗或不穩定。要在加入 workflow 之前或同時處理。

### P1 `UserEventContractIntegrationTests` 依賴本機 secrets

- **位置**：`backend/spring-user-service/src/test/java/com/mymicroservice/user/UserEventContractIntegrationTests.java`
- **現象**：這個測試類別沒有 `@ActiveProfiles("test")`，所以會讀 `application.yml` 的預設值 `file:./secrets/jwt-*.pem`。`secrets/` 不進版控，CI 上沒有這兩個檔案，context 會啟動失敗。上一輪審查在乾淨的 git worktree 跑 user-service 測試時已經實際重現：`FileNotFoundException: secrets\jwt-public.pem`。
- **修正**：加上 `@ActiveProfiles("test")`，改用 `src/test/resources/jwt/` 的測試金鑰。其他 user-service 測試類別都已經有 test profile。
- **驗證**：在一個沒有 `secrets/` 的乾淨 clone（或 git worktree）裡跑 `./mvnw -pl spring-user-service -am test`，全部通過。

### P2 前端的 lint 指令會自動修改檔案

- **位置**：兩個前端的 `package.json`
- **現象**：`lint:oxlint` 是 `oxlint . --fix`，`lint:eslint` 是 `eslint . --fix --cache`，`format` 是 `prettier --write`。CI 上執行這些指令會直接改檔，然後回報成功，問題不會被抓到。
- **修正**：新增不修改檔案的檢查版指令，原本的指令保留給本機開發使用：

  ```json
  "lint:check": "oxlint . && eslint .",
  "format:check": "prettier --check --experimental-cli src/"
  ```

  oxlint、eslint、prettier 的實際參數以現有設定為準（例如 `--experimental-cli` 要不要保留）。
- **注意**：第一次執行檢查版指令，可能會發現既有的 lint 或格式問題。修正這些問題要**另外一個 commit**，並在完成紀錄中列出修了哪些檔案；不可以用關閉規則的方式讓檢查通過。

### P3 notification 測試在容器裡跑 Maven，Linux 上可能有權限問題

- **位置**：`backend/spring-notification-service/src/test/java/com/mymicroservice/notification/messaging/NotificationEmailIntegrationTests.java`（`findMavenDistribution()` 與建置容器的部分）
- **現象**：這個測試會啟動 `maven:3.9-eclipse-temurin-21` 容器，把 runner 的 `~/.m2/repository` 以**可寫入**方式掛載進去，並使用 `~/.m2/wrapper/dists/apache-maven-3.10.0` 底下的 Maven，以離線模式（`-o`）建置。在 Windows 的 Docker Desktop 上沒有問題，但在 Linux runner 上：
  1. 容器以 root 執行，寫進 `~/.m2/repository` 的檔案會屬於 root，之後 `actions/setup-java` 儲存快取時可能因為權限而失敗。
  2. 只有在 `./mvnw` 先執行過、wrapper 已經下載 Maven 之後，才找得到這個 Maven。
- **處理方式**：先照常在 CI 執行，確認實際結果：
  - 如果測試與快取都正常，在完成紀錄中寫下實際結果即可。
  - 如果有權限問題，優先調整測試：用容器內的暫存目錄當 Maven repository，只把 runner 的 repository **唯讀**掛載進去當作來源；或讓容器以 runner 的 UID 執行。不可以把這個測試跳過或從 CI 排除。修正前先回報，由 Claude 審查做法。

### P4 product 的 Elasticsearch 測試會即時建置映像

- **位置**：`backend/spring-product-service/src/test/java/com/mymicroservice/product/search/ProductIndexEventListenerIntegrationTests.java`
- **現象**：`ProductIndexEventListenerIntegrationTests`、`ProductIndexManagerIntegrationTests`、`ProductIndexRebuildIntegrationTests`、`ProductSearchIntegrationTests` 四個類別都會用 `infra/elasticsearch/Dockerfile`（路徑相對於模組目錄）即時建置含 SmartCN 外掛的 ES 映像；其中 `EventListener` 與 `Rebuild` 兩個類別使用固定的 host port（分別是 19200、19201）。每次 CI 都要重新建置，會增加執行時間；GitHub 的 runner 一次只跑一個 job，固定 port 不會衝突。
- **處理方式**：這次不處理，在完成紀錄寫下 product 模組的實際執行時間，作為之後是否快取映像的依據。

## Workflow 設計

### 觸發條件與共用設定

```yaml
on:
  pull_request:
    branches: [main]
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true
```

- `push` 到 `main` 時，不做變更判斷，執行全部檢查，確認合併後的 `main` 本身是綠的。
- 所有第三方 action（`actions/checkout`、`actions/setup-java`、`actions/setup-node`、`actions/upload-artifact` 等）使用實作時的最新主版本，並**用 commit SHA 釘選**，後面加註解標明版本，例如 `uses: actions/checkout@<sha> # v5.x.x`。
- 不使用需要 secret 的步驟；PR 的 workflow 不需要任何 secret。

### Job 一覽

| Job | 內容 | 逾時 |
|---|---|---|
| `changes` | 判斷這次變更影響哪些後端模組、前端應用、K8s manifest | 5 分鐘 |
| `backend`（matrix） | 每個受影響的模組各跑一次 `-pl <module> -am test` | 40 分鐘 |
| `frontend`（matrix） | 每個受影響的前端應用跑完整檢查 | 20 分鐘 |
| `k8s-manifests` | `kubectl kustomize infra/k8s` 能成功 render | 5 分鐘 |
| `ci-result` | 彙總以上結果；branch protection 只要求這一項 | 5 分鐘 |

### `changes`：變更判斷

用 shell 比對 `git diff --name-only`，不引入第三方的 paths-filter action。PR 時比對 `origin/main...HEAD`（`actions/checkout` 需要 `fetch-depth: 0`）；`push` 到 `main` 與 `workflow_dispatch` 時一律輸出全部。

輸出兩個 JSON 陣列，給後面的 matrix 使用，並各加一個布林值，讓沒有變更時可以跳過 job：

| 變更的路徑 | 後端模組 | 前端應用 | K8s |
|---|---|---|---|
| `backend/common/**`、`backend/observability/**`、`backend/pom.xml`、`backend/.mvn/**`、`backend/mvnw*` | 全部 9 個模組 | — | — |
| `backend/<module>/**` | 該模組 | — | — |
| `frontend/<app>/**` | — | 該應用 | — |
| `infra/k8s/**` | — | — | 是 |
| `infra/elasticsearch/**` | `spring-product-service` | — | — |
| `.github/workflows/**` | 全部 | 全部 | 是 |
| 只有 `docs/**`、`*.md` | 都不跑 | 都不跑 | 不跑 |

「全部 9 個模組」是 `backend/pom.xml` 的 `<module>` 清單：`common`、`observability`、7 個服務。`common` 本身也要列入 matrix，因為它有自己的測試（目前 67 項）。

### `backend`：每個模組一個 job

```text
setup-java：Temurin 21，cache: maven
./mvnw -B -ntp -pl <module> -am test
失敗時上傳 <module>/target/surefire-reports 與 failsafe-reports（如果有）
```

- `fail-fast: false`：一個模組失敗時，其他模組照樣跑完，一次看到全部問題。
- `-am` 會連帶建置 `common`，跟本機的做法一致。
- GitHub 提供的 `ubuntu-latest` runner 內建 Docker，Testcontainers 不需要額外設定。不要設定 `TESTCONTAINERS_RYUK_DISABLED`。

### `frontend`：每個應用一個 job

在 `frontend/<app>` 目錄執行：

```text
setup-node：Node 22（符合 engines 的 ^22.18.0），cache: npm，cache-dependency-path 指向該應用的 package-lock.json
npm ci
npm run lint:check
npm run format:check
npm run type-check
npx vitest run
npm run build-only
（不安裝瀏覽器：使用 runner 內建的 Google Chrome，見下方說明）
npm run test:browser
失敗時上傳 playwright-report 與 test-results
```

- `vitest` 要用 `vitest run`，避免進入 watch 模式。
- `test:browser` 是兩個應用都有的免後端瀏覽器測試（`playwright.optimization.config.ts`，API 全部用 route 攔截）。
- **瀏覽器通道（使用者決定 2026-11-16）**：兩份設定都是 `channel: 'chrome'`，維持不變，CI 使用 `ubuntu-latest` 內建的 Google Chrome，**不執行** `playwright install`。這是依 GitHub 映像內建 Chrome 的推論，尚未驗證：第一次執行時先在 job 裡印出 `google-chrome --version`，若找不到 Chrome，回報後再決定改用 `browser-actions/setup-chrome` 之類的步驟或 `npx playwright install --with-deps chrome`，不要自行改設定檔的 `channel`。
- 瀏覽器測試的 dev server 固定使用 port（vue-customer 5184、vue-management 5185，`reuseExistingServer: true`），兩個應用在各自獨立的 job 執行，不會衝突；不要把兩個應用放進同一個 job 並行執行。
- 前端測試現況與環境依賴見[測試策略與測試現況](testing-overview.md)。
- **不跑** `test:e2e`，它需要完整後端與管理員帳號（見「範圍」）。

### `k8s-manifests`

`ubuntu-latest` 內建 `kubectl`。執行 `kubectl kustomize infra/k8s > /dev/null`，只確認 manifest 能 render，不連線叢集。

### `ci-result`：彙總

```yaml
ci-result:
  if: always()
  needs: [changes, backend, frontend, k8s-manifests]
```

逐一檢查 `needs.*.result`：`success` 與 `skipped` 算通過，`failure` 與 `cancelled` 算失敗，最後以結束碼回報。

這樣做的原因：被變更判斷跳過的 job 不會產生狀態，如果把每個 matrix job 都設成必要檢查，只改文件的 PR 會一直卡在「等待檢查」。只要求 `ci-result` 一項，規則就固定了。

## 實作步驟

每一步一個 commit，順序如下：

1. **P1**：`UserEventContractIntegrationTests` 加上 test profile。在沒有 `secrets/` 的乾淨 worktree 跑 user-service 整個模組，確認通過。
2. **P2**：兩個前端新增 `lint:check`、`format:check`。
3. **P2 既有問題**（有的話）：修正檢查版指令發現的既有 lint 或格式問題。
4. **Workflow**：新增 `.github/workflows/ci.yml`。
5. **在 GitHub 上驗證**：推送分支後，由使用者開 PR（gh 目前沒有授權）。這個 PR 修改了 `.github/workflows/**`，所以會觸發全部檢查。確認以下各點後，結果寫進完成紀錄：
   - 9 個後端模組、2 個前端應用、K8s 全部通過，`ci-result` 為綠燈。
   - P3 的實際結果（測試與快取儲存是否正常）。
   - 每個 job 的執行時間，特別是 product 模組（P4）。
   - **故意失敗的驗證**：暫時推一個讓某個測試失敗的 commit，確認 `ci-result` 變紅，再用 revert commit 還原。兩個 commit 都保留在歷史中，並寫進完成紀錄。
6. **P3 修正**（如果第 5 步發現問題）：依 P3 的處理方式修正，再重跑一次。
7. **文件**：
   - 根目錄 `README.md` 的「測試」一節說明 CI 跑哪些檢查，以及新的 `lint:check`、`format:check`。
   - [系統缺口評估](../system-gaps-assessment.md) G-01 註記「已完成」並連到本文件。
   - [暫緩清單](../deferred-items.md)「隨開發流程」的兩項重新評估並寫下結論：「Gateway 測試設定共用與 Docker 分離」（CI 上有 Docker，原本的觸發條件是否還成立）、「Gateway Boot／Cloud 升級」（CI 已經建好，可以排入）。
   - [文件索引](../../README.md)加入本文件。

## 使用者需要手動做的事

gh 目前沒有授權，以下由使用者在 GitHub 網頁上設定：

- **Branch protection**（Settings → Branches → `main`）：開啟「Require status checks to pass before merging」，必要檢查只選 `ci-result`；建議同時開啟「Require branches to be up to date before merging」。
- 這一步要等 workflow 至少執行過一次，`ci-result` 才會出現在選單中。

## 驗收條件

- [ ] `test:browser` 在 CI 上使用 runner 內建的 Chrome 通過，job 記錄印出 Chrome 版本。
- [x] 在沒有 `secrets/` 的乾淨環境，user-service 整個模組測試通過（P1）。
- [x] 兩個前端的 `lint:check`、`format:check` 不會修改檔案，且目前全部通過（P2）。
- [ ] 第一次完整執行：9 個後端模組、2 個前端、K8s 全部通過，`ci-result` 綠燈。
- [ ] 故意失敗的 commit 讓 `ci-result` 變紅，revert 後恢復綠燈。
- [ ] 只改 `docs/` 的 PR，後端與前端 job 都被跳過，`ci-result` 仍是綠燈（可以在下一個文件 PR 驗證，結果補進完成紀錄）。
- [ ] P3 的實際結果寫進完成紀錄；如果有修正，經過 Claude 審查。
- [ ] 每個 job 的執行時間寫進完成紀錄。
- [ ] 所有 action 都用 commit SHA 釘選。

## 完成紀錄

2026-11-16，分支 `feature/ci-pipeline`。步驟 1～4 與步驟 7 的文件已完成；**步驟 5（GitHub 驗證）、步驟 6（P3 修正，視需要）與 branch protection 尚未進行**，需要使用者開 PR 後才有結果。

### 已完成

| 步驟 | 結果 |
|---|---|
| 1 P1 | `UserEventContractIntegrationTests` 加上 `@ActiveProfiles("test")`。在沒有 `secrets/` 的乾淨 git worktree 執行 `./mvnw -B -ntp -pl spring-user-service -am test`：**170 項通過，2 分 35 秒**，log 顯示該測試以 `"test"` profile 啟動 |
| 2 P2 | 兩個前端新增 `lint:check`（`oxlint . && eslint .`）與 `format:check`（`prettier --check --experimental-cli src/`），保留 `--experimental-cli`，沿用現有設定。注意：既有的 `npm run lint` 用 `run-s "lint:*"`，萬用字元也會比對到 `lint:check`，所以 `lint` 在自動修正後會再跑一次檢查，無害 |
| 3 P2 既有問題 | `lint:check` 與 `format:check` 發現：vue-management `dashboard/__tests__/store.spec.ts`（3 處）、`products/images/__tests__/resize.spec.ts`（4 處）的 `vi.fn()` 缺型別參數（`vitest/require-mock-type-parameters`），補上型別參數修正；vue-customer `checkout/messages.ts` 一行過長字串依 prettier 換行。**沒有關閉任何規則**。修正後兩個前端的 `lint:check`、`format:check`、vue-management 的 `type-check` 與受影響的 31 項 Vitest 通過 |
| 4 workflow | 新增 `.github/workflows/ci.yml`，內容依「Workflow 設計」；四個 action 用 commit SHA 釘選（`actions/checkout` v7.0.1、`setup-java` v6.3.0、`setup-node` v7.1.0、`upload-artifact` v7.0.2，SHA 來自各 repo 的輕量標籤）。與計畫的差異見下 |

### 與計畫的差異

- **不使用 jq**：`changes` 與 `ci-result` 用純 shell／grep 處理，讓邏輯能在本機測試。
- **`mvnw` 沒有執行權限**（git 模式 100644），workflow 先 `chmod +x mvnw`，沒有更動 repo 內的檔案模式。
- **瀏覽器**：依使用者決定（2026-11-16），維持 `channel: 'chrome'`，不安裝瀏覽器，並多一個印出 `google-chrome --version` 的步驟。

### 本機驗證（不是 GitHub 執行結果）

- `changes` 的判斷腳本從 workflow 取出，在本機以拋棄式分支模擬七種情境：只改文件、改 `common`、改單一服務、改 `infra/elasticsearch/`、改 `infra/k8s/`、改 workflow、改單一前端，輸出的模組、前端與 k8s 旗標全部符合「`changes`：變更判斷」表。
- **沒有**用 actionlint 或 GitHub 驗證 workflow 語法；沒有執行 matrix、`fromJSON`、`ci-result` 的實際行為。

### 第一次 GitHub 執行的失敗與修正（2026-11-16，使用者提供 notification 與 vue-management 兩份 log）

只看到這兩份 log，其他 job 的結果還沒有回報。

| Job | 現象 | 原因 | 修正 |
|---|---|---|---|
| backend／`spring-notification-service`（**P3 實際發生**） | `NotificationEmailIntegrationTests` 7 項中 2 項錯誤（共 12 項、2 錯誤），各等了約 10 分鐘（類別耗時 1,217 秒）。容器內的離線 Maven 報 `Cannot access central ... in offline mode and the artifact maven-jar-plugin:jar:3.5.1 has not been downloaded from it before` | CI 只執行 `test`，runner 的 `~/.m2` 沒有 `package` 階段的外掛；測試在容器內以 `-o` 執行 `package`，所以找不到。**不是權限問題**；Maven wrapper 的發行版有找到 | workflow 在 notification 模組先以相同範圍執行 `-pl spring-notification-service -am -DskipTests package` 暖機，不修改測試、不排除測試。**尚未在 GitHub 驗證**；容器以 root 寫入 `~/.m2`，之後 `setup-java` 儲存快取是否因權限失敗還要看下次的 post step |
| frontend／`vue-management`，`test:browser` | `browser-tests/resize.spec.ts` 報 `page.evaluate: Execution context was destroyed, most likely because of a navigation` | **推測**：乾淨環境第一次載入時 Vite 重新優化依賴而整頁重載。本機清掉 `node_modules/.vite` 後**沒有重現**（通過），所以這是推論 | 測試用 `expect(...).toPass()` 包住 `goto` 與 `evaluate`，重載時重試（30 秒）。本機 `lint:check`、`format:check`、`type-check`、`test:browser` 通過；**尚未在 GitHub 驗證** |

兩份 log 在 `docs/logs/`（使用者放入，未納入版控）。

### 修正後重跑的結果（2026-11-16，PR #31，`pull_request` synchronize，來自使用者提供的兩張截圖）

狀態 Success，**總耗時 8 分 11 秒**。各 job 耗時：

| Job | 耗時 | Job | 耗時 |
|---|---|---|---|
| `changes` | 5 秒 | `backend (spring-payment-service)` | 1 分 23 秒 |
| `backend (common)` | 56 秒 | `backend (spring-notification-service)` | 2 分 14 秒 |
| `backend (observability)` | 13 秒 | `backend (spring-order-service)` | 1 分 58 秒 |
| `backend (spring-api-gateway)` | 1 分 15 秒 | `backend (spring-user-service)` | 2 分 12 秒 |
| `backend (spring-inventory-service)` | 1 分 45 秒 | `backend (spring-product-service)` | **7 分 54 秒**（P4 的基準，決定整體耗時） |
| `frontend (vue-customer)` | 50 秒 | `frontend (vue-management)` | 1 分 15 秒 |
| `ci-result` | 3 秒 | `k8s-manifests` | 7 秒（使用者補充截圖確認通過） |

- 前端 Vitest 摘要：vue-customer 30 檔、344 測試通過；vue-management 26 檔、267 測試通過，與本機 2026-11-16 的結果一致。
- 兩個前端的 `test:browser` 在 job 內通過（job 為綠燈），上一節兩項修正在 GitHub 上生效；notification 的離線建置暖機也生效（該 job 2 分 14 秒通過，上一輪是 2 項錯誤、各等約 10 分鐘）。
- **截圖看不到的**：`google-chrome --version` 的輸出；notification job 的 `setup-java` post step 是否有快取儲存警告；各測試數量。這些要再回報。
- P4 初步結論：product 的 7 分 54 秒是其他模組的 3～4 倍，是整體耗時的瓶頸；是否快取 ES 映像，等拿到該 job 內「建置映像」實際花多少時間再決定，目前不處理。

### 待在 GitHub 驗證（步驟 5）

- [x] 9 個後端模組、2 個前端通過，`ci-result` 綠燈，各 job 時間已記錄；K8s job 7 秒通過，確認有執行。
- [ ] P3：測試已通過；**快取儲存是否有警告待確認**。
- [ ] `google-chrome --version` 有輸出，`test:browser` 使用內建 Chrome 通過。
- [ ] 故意失敗的 commit 讓 `ci-result` 變紅，再 revert。
- [ ] 只改 `docs/` 的 PR，後端與前端 job 跳過、`ci-result` 綠燈。
- [ ] 使用者設定 branch protection，必要檢查只選 `ci-result`。
