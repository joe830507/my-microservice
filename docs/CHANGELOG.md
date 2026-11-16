# 文件變更紀錄

文件整理與審查的逐次紀錄，新的在上。維護規則見[文件索引](README.md#文件維護)。歷史紀錄中的「現況」、文件數與測試數字是當時的結果，不代表目前狀態。

2026-11-16 G-01 CI 實作（分支 `feature/ci-pipeline`）：更新 CI 計畫完成紀錄、根 README 的 CI 說明、缺口評估 G-01 狀態與暫緩清單兩項 Gateway 的重新評估；GitHub 驗證尚未進行。

2026-11-16 文件缺口第二輪（[審查](implementation-plans/reviews/documentation-gaps-review-2.md)）：更正架構參考與程式不一致之處並補 product-service／notification-service 的 API 說明；新增[測試策略與測試現況](implementation-plans/dev-workflow/testing-overview.md)與[本機開發與疑難排解](operations/local-development.md)；本紀錄由文件索引搬出；`local-backend.sh` 加上 `WITH_NOTIFICATION=1`（只做語法檢查）；安全清單「還沒有檢查的」加上 CHK-01～06 編號。全庫 55 份 Markdown 的 1,228 個本機連結與章節錨點、UTF-8 無 BOM、`git diff --check` 檢查通過；實際只執行了 `common` 與兩個前端的 Vitest，其餘後端測試、前端 lint／type-check／build／Playwright 與服務啟動、Mermaid 渲染均未執行。

2026-11-13 完成文件缺口四項：新增 12 條告警手冊及 kind 原始查詢結果、集中 28 項暫緩／條件式工作、補 SEC-14／15 總表並整理 SEC-13～15 位置、補最佳化與文件審查入口。共異動 25 個檔案（24 份 Markdown、一份查詢 JSON）；全庫 47 份 Markdown 的 1,141 個本機連結、210 個章節引用與 UTF-8 無 BOM 檢查通過，git diff --check 通過。12 條告警的查詢／門檻／for 已對照產生設定，全部原始 A 查詢在 kind Prometheus 成功執行，但部分結果空集合或 NaN；未做故障注入、告警 firing／恢復／通知驗收，亦未執行手冊的修復命令、Loki／Tempo／通知 SQL、服務測試、建置或部署。

2026-11-13 同步根 README、架構／系統導讀、模組計畫與紀錄的程式導讀入口；補上兩個前端的專案 README，取代管理端 Vite 範本，保留歷史驗收內容。22 份異動文件的 307 個本機連結（含明確章節錨點）、UTF-8 無 BOM 與差異空白檢查通過；前端 scripts 已對照 package.json，未重新執行測試、建置或文件圖形渲染。

2026-11-13 新增 component-relationships 前端文件分類，完成 vue-customer／vue-management 元件關係與職責導讀，涵蓋 52／51 個正式應用來源檔、各五個入口／套件／代理設定檔與七張 Mermaid 圖，整理路由、狀態、驗證、結帳付款及後台圖片／庫存流程。四份異動文件的 175 個本機連結、來源覆蓋、UTF-8 無 BOM 與圖形區塊結構檢查通過；未重新執行前端測試、建置或渲染 Mermaid。

2026-11-13 補上 spring-user-service 類別導讀，涵蓋 37 個 Java 來源檔、兩個內部 record、九個 migration 與八張 Mermaid 圖，說明註冊／登入、JWT／JWKS、cookie、refresh 輪替／撤銷、登入失敗計數及管理員初始化。三份異動文件的 108 個本機連結、全部 49 個主程式／資源／設定檔與 UTF-8 無 BOM 已核對，圖形區塊結構檢查通過；未重新執行服務測試或渲染 Mermaid。

2026-11-13 補上 spring-product-service 類別導讀，涵蓋 76 個 Java 來源檔、五個內部 record、一個內部例外類別、八個 migration 與十張 Mermaid 圖，說明商品／分類／圖片、Redis 快取、圖片物件補償、搜尋與索引重建。三份異動文件的 145 個本機連結、全部 87 個主程式／資源／設定檔與 UTF-8 無 BOM 已核對，圖形區塊結構檢查通過；未重新執行服務測試或渲染 Mermaid。

2026-11-13 補上 spring-payment-service 類別導讀，涵蓋 19 個 Java 來源檔、一個內部 record、七個 migration 與六張 Mermaid 圖，說明有效付款唯一性、回呼交易、事件傳遞與舊通知機制退役。三份異動文件的 73 個本機連結、全部 28 個主程式／資源／設定檔與 UTF-8 無 BOM 已核對，圖形區塊結構檢查通過；未重新執行服務測試或渲染 Mermaid。

2026-11-13 補上 spring-order-service 類別導讀，涵蓋 28 個 Java 來源檔、九個內部 record、一個內部 enum、九個 migration 與七張 Mermaid 圖，說明建單快照／補償、冪等、狀態交易、付款事件與逾時排程。三份異動文件的 88 個本機連結、全部 39 個主程式／資源／設定檔與 UTF-8 無 BOM 已核對，圖形區塊結構檢查通過；未重新執行服務測試或渲染 Mermaid。

2026-11-13 補上 spring-notification-service 類別導讀，涵蓋 12 個 Java 來源檔、三個內部 record、五個 migration、五份信件模板與六張 Mermaid 圖，說明事件投影、交易去重、租約與 SMTP 重試。三份異動文件的 62 個本機連結、全部 24 個主程式／資源／設定檔與 UTF-8 無 BOM 已核對，圖形區塊結構檢查通過；未重新執行服務測試或渲染 Mermaid。

2026-11-13 補上 spring-inventory-service 類別導讀，涵蓋 25 個 Java 來源檔、三個內部 record、六個 migration 與七張 Mermaid 圖，說明數量與狀態轉換、交易／冪等及 HTTP／事件／排程入口。三份異動文件的 78 個本機連結（含章節引用）、全部 33 個來源／設定檔與 UTF-8 無 BOM 已核對，圖形區塊結構檢查通過；未重新執行服務測試或渲染 Mermaid。

2026-11-13 補上 spring-api-gateway 類別導讀，涵蓋 13 個 Java 來源檔、內部限流 Config、YAML／Maven 接線與六張 Mermaid 圖。三份異動文件的 62 個本機連結（含章節引用）、全部 15 個主程式／設定檔、七條路由共用五個熔斷名稱與 UTF-8 無 BOM 已核對，圖形區塊結構檢查通過；未執行 Mermaid 渲染或重新執行服務測試。

2026-11-13 補上 observability 類別與設定導讀，涵蓋一個 Java 類別、三個資源與 Maven 設定，包含四張 Mermaid 圖。三份異動文件的 54 個本機連結（含章節引用）、七個應用的依賴／設定匯入與 UTF-8 無 BOM 已核對，圖形區塊結構檢查通過；未執行 Mermaid 渲染、應用程式測試或 Collector 端到端驗收。

2026-11-13 新增 `class-relationships/` 文件類型與 common 導讀，涵蓋 32 個 Java 主程式來源檔、9 張 Mermaid 圖與重要內部型別。三份異動文件的 91 個本機連結（含章節引用）、UTF-8 無 BOM 與圖形區塊結構已檢查；未執行 Mermaid 渲染或重新執行應用程式測試。

2026-11-12 新增系統導讀，作為跨服務的閱讀入口，文件數增為 25 份。內容已核對建單、付款、身分驗證、搜尋、通知與本機啟動程式；本次 2 份異動文件的 53 個本機連結、13 個章節引用及 UTF-8 編碼檢查通過，未重新執行應用程式測試或部署。

2026-11-12 整合：Markdown 由 55 份減為 24 份。前後台各自的 Step 0–12 與後台 API 實測收進各自 README；C／D／E 原始審查收進對應實作紀錄；圖片上傳設計收進實作文件。31 份被完整取代的舊檔已移除，引用改為章節連結，決策、限制、測試結果與原始證據保留。

整理驗證：逐行對照 31 份舊檔，除標題層級、引用路徑與重複導覽外，正文與 10 段程式碼區塊均保留；246 個本機連結及 114 個章節引用通過檢查。24 份 Markdown 通過 UTF-8 無 BOM／替代字元檢查，`git diff --check` 通過。前後台 E2E 設定與環境變數錯誤提示中的文件路徑同步更新，四個檔案的 Prettier 檢查通過；未重跑應用程式測試。
