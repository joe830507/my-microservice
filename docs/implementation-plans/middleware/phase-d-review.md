# 階段 D 實作審查與待修項目

> 審查日期：2026-11-12
> 對象：分支 `feature/search-phase-d` 的階段 D（Elasticsearch 搜尋，D1–D7），對照 [search-implementation-plan.md](search-implementation-plan.md) 與 [middleware-roadmap.md](middleware-roadmap.md)「階段 D」
> 範圍：重跑 D 的 7 個測試類別（34 項全數通過）、檢查 kind 叢集即時狀態、閱讀 `ProductSearchService` 與 D7 證據。**未重跑完整 reactor**（以 `evidence/phase-d/d7-reactor.txt` 為準）

## 整體評價

程式與證據相符，可保留：

- `ProductSearchService`：退回條件（逾時、5xx、429、熔斷、索引未就緒）與計畫一致；其他 4xx 不退回；`from + size > 10000` 回 400；排序固定附 `id` tie-break；`sortBy` 有白名單。
- 回表以資料庫為準，索引過期的商品會被丟掉並累加 `search.stale_hits`。
- kind 即時狀態：ES green、`products` alias 指向單一索引、`_meta` 為 `built=true`／`schemaVersion=1`、`product-service` 兩個副本已有 `ELASTICSEARCH_ENDPOINT`。
- D7 停機證據（66 次搜尋 0 失敗、`mm-search-fallback` 對 `error` 與 `breaker_open` 進入 active、停機期間更新的商品恢復後已寫入）與程式行為一致。

以下問題請依優先順序處理。

## 待修項目

> 修正進度：第 1 項已由審查者補上 Tempo、Gateway 證據及使用者人工操作確認；第 2–6 項已依下方審查後紀錄處理。階段 D 結案，告警 resolved 證據仍未驗證。詳見 `search-implementation-record.md`。

### P1：階段 D 不能結案

**1. D7 前端與 trace 驗收沒有證據**
紀錄明寫「vue-customer 搜尋頁、vue-management 商品列表的瀏覽器操作」與「Tempo 中確認後台更新 → outbox → Kafka → 索引的 trace」尚未完成，因此階段 D 仍未結案。這兩項是計畫 D7 的驗收項目，不能標成完成。
→ 補做並把證據放進 `evidence/phase-d/`；補完前 roadmap 與紀錄的狀態維持「D7 部分完成」。補做前端時，請一併確認 D5 紀錄提到的行為：搜尋頁排序選單預設「最新」，實際會變成相關性。
→ 若瀏覽器自動化仍無法使用，改由人工操作並截圖，或在紀錄明確寫「此項改為不驗收」及理由，不要留在模糊狀態。

### P2：文件與驗證

**2. `search-implementation-record.md` 第 20 行表格欄數錯誤**
D7 那列有三欄（多了「部分完成」），其他列都是兩欄，Markdown 表格會壞掉。
→ 改成兩欄，把「部分完成」併進內容欄。

**3. 告警恢復沒有驗證**
`d7-search-outage.txt` 只有 `mm-search-fallback` firing，沒有 ES 恢復後告警回到 resolved 的記錄。計畫只要求 firing，所以不算違規。
→ 補一次恢復後的告警狀態（或在紀錄寫明沒有驗證），讓「退回結束後告警會自動消失」有依據。

**4. 舊索引累積，沒有清理方式的說明**
重建預設 `deleteOld=false`，叢集上目前已有 3 個 `products-*` 索引，每次重建都會多一個。
→ 在紀錄的「已知限制」寫明：舊索引需用 `deleteOld=true` 或手動刪除，並說明建議流程（確認搜尋正常後再刪）。不需要新增自動清理。

### P3：小項

**5. `alerting.yaml` 的格式雜訊**
產生器把既有規則從單行改成多行展開，diff 有 160 行，實際只新增 `mm-search-fallback` 一條。內容沒問題。
→ 二選一：讓產生器維持原本的輸出格式，或在 commit 訊息說明這是格式變更，並把格式變更與新增規則分成兩個 commit，方便審查。

**6. 偶發測試失敗尚未處理**
D5 時出現過兩個偶發失敗：`ProductEventContractIntegrationTests` 的 `createdAt` 差 1 微秒，以及 `ProductIndexEventListenerIntegrationTests` 的 ES 容器啟動失敗。本次單獨重跑都通過，但沒有重複執行驗證。
→ 至少把 `createdAt` 的比對方式說清楚（測試端與事件端都截到微秒），並重複執行 3–5 次確認穩定；容器啟動失敗若判定為資源競爭，請記在紀錄。

## 需要你決定（不是修正項目）

這些是紀錄「最不確定」段落裡提過、尚未決定的事，不要在修正時順手改：

- OR 查詢過度召回（含常見字「不」的關鍵字）：是否加 `minimum_should_match` 或 `operator: and`，屬於查詢語意變更，需先改計畫。
- ES 掛掉時深分頁一律 400，但退回的 SQL 其實可以翻更深。
- 熔斷器每個副本各一個，`breaker_open` 不會跨副本共用（與階段 B 相同限制）。

## 修完後

- 重跑 D 相關測試，`search-implementation-record.md` 更新日期與結果。
- 第 1 項完成前，不要把階段 D 標成完成，也不要合併。

---

## 結案更新（2026-11-12，審查者驗收後）

第 1 項（D7 前端與 trace）已補齊，**階段 D 可以結案**。請依下列內容更新文件，不要再寫「不驗收」或「待補」。

### 已取得的證據

- **Tempo trace**：`evidence/phase-d/d7-tempo-and-gateway.txt` 第 1 節。正常與 ES 停機兩個 trace，都貫穿 HTTP `PUT /products/{id}` → `outbox publish` → `product.events process`；停機那筆以退避重試，約 41 秒後 ES 恢復成功，未進 DLT。
- **經 Gateway 的搜尋 API**：同檔第 2 節。`engine=es` 加 3、`engine=sql` 不變，代表前端實際呼叫的路徑由 ES 回應。
- **前端瀏覽器操作**：使用者在 2026-11-12 以人工方式操作 kind 上的 vue-customer（`localhost:8091`）與 vue-management（`localhost:8093`）：前台搜尋「Phase」有結果、無關詞顯示無結果、排序選單可用、後台商品列表正常。**使用者回報全部正常**。這是人工驗證，沒有截圖；紀錄請如實寫「人工操作、無截圖」，不要寫成自動化 E2E。

### 需要修改的位置

| 檔案 | 位置 | 改成 |
| ---- | ---- | ---- |
| `search-implementation-record.md` | 第 6 行狀態、第 20 行 D7 列 | D1–D7 完成；D7 列維持兩欄 |
| 同上 | D7 章節「尚未完成」「列為不驗收」兩段 | 改寫為已驗證：Tempo trace、Gateway 搜尋、人工瀏覽器操作；刪除「不驗收」的說法 |
| 同上 | 第 186 行附近「不替代尚未完成的…驗收」 | 刪除「尚未完成」的語意 |
| 同上 | 「已知限制」的「D7 部分驗收完成…」 | 刪除，或改成「瀏覽器驗證為人工操作、無截圖」 |
| `search-implementation-plan.md` | 第 7 行狀態、第 264 行驗收摘要 | 改為 D1–D7 完成，階段 D 結案 |
| `middleware-roadmap.md` | 第 6 行狀態、第 136 行標題、第 148 行 D7 | 改為 D 完成；D7 列標「完成」並指向證據 |
| `docs/README.md` | 第 40、41 行 | 改為完成（階段 D 完成） |
| `phase-d-review.md` | 第 20 行修正進度 | 第 1 項改為已處理 |

### 仍不要順手改的事

- 告警 resolved 的證據仍沒有，維持「未驗證」的誠實寫法，不要補寫成通過。
- 「需要你決定」那三項（OR 查詢過度召回、深分頁一致性、熔斷器不跨副本）維持待決，列為階段 D 結案後的後續事項即可。

### 結案前

- 這批文件與證據都還沒 commit；請把證據檔（`d7-*.txt`、`review-*.txt`）一併納入。
- 結案後，3 個 port-forward 可關閉（使用者會通知）。
