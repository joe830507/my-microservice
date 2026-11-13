# 文件索引

第一次接觸專案，先讀[系統導讀：從一筆訂單理解整個專案](architecture/system-guide.md)。它面向懂程式但不熟悉本系統的讀者，依序說明服務分工、資料邊界、核心流程、故障行為與程式閱讀入口。

## 分類

| 資料夾 | 放什麼 |
|---|---|
| `architecture/` | 整體架構：服務切分、API、全域慣例、服務之間的互動 |
| `class-relationships/` | 類別關係圖與職責說明：依後端模組整理類別分工、依賴、建立方式與程式閱讀入口 |
| `implementation-plans/` | 實作步驟、進度與實際驗證結果；分 `backend/`（各服務與共用模組）、`middleware/`（中介軟體各階段）、`vue-customer/`、`vue-management/`，跨層的後端部署計畫放在根目錄 |
| `design/` | 單一功能或技術選型的設計、評估，還沒排進實作計畫的也放這裡 |
| `security/` | 已知的安全問題：現象、驗證方式、處理方向與處理狀態 |

新增文件時依上表分類，使用相對路徑連結、繁體中文與 UTF-8 無 BOM。

先讀架構與安全事項，再進入對應計畫。計畫保留要求與決策，實作紀錄保存結果、限制與證據；原始審查已收進各階段紀錄末尾，保留審查當時的發現，後續處理以紀錄前段為準。歷史的「現況」與測試數字不代表目前狀態，不因整理文件而重新宣稱驗證通過。

## 架構

| 文件 | 內容 |
|---|---|
| [microservice-architecture.md](architecture/microservice-architecture.md) | 架構參考：服務清單、port 分配、各服務 API、Gateway 路由、下單流程、建立順序 |
| [event-contracts.md](architecture/event-contracts.md) | 事件格式、版本、交易、去重、重試與消費行為的依據 |

## 類別關係與職責

| 文件 | 內容 |
|---|---|
| [類別關係文件索引](class-relationships/README.md) | 圖例、分類與維護方式；依後端模組逐步補齊 |
| [common 類別關係圖與職責說明](class-relationships/common.md) | 32 個來源檔的職責、Spring 接線、類別依賴與對照測試 |
| [observability 類別關係圖與職責說明](class-relationships/observability.md) | 一個自訂類別與共用設定的分工、Logback 接線、指標／追蹤／日誌資料流 |
| [spring-api-gateway 類別關係圖與職責說明](class-relationships/spring-api-gateway.md) | 13 個來源檔的分工、兩種過濾鏈、JWT／header／限流／熔斷與錯誤回應 |
| [spring-inventory-service 類別關係圖與職責說明](class-relationships/spring-inventory-service.md) | 25 個來源檔的分工、庫存預扣交易與狀態圖、事件與排程入口、六個 migration |
| [spring-notification-service 類別關係圖與職責說明](class-relationships/spring-notification-service.md) | 12 個來源檔的分工、事件投影／去重、SMTP 租約與重試、五個 migration |
| [spring-order-service 類別關係圖與職責說明](class-relationships/spring-order-service.md) | 28 個來源檔的分工、建單補償／冪等、訂單狀態、付款事件與九個 migration |

## 實作計畫

依建立順序排列：

| 文件 | 狀態 |
|---|---|
| [backend-common-implementation-plan.md](implementation-plans/backend/backend-common-implementation-plan.md) | 共用模組：錯誤格式、稽核、身分 header、`@CurrentUserId`、`@RequireRoles` |
| [user-service-implementation-plan.md](implementation-plans/backend/user-service-implementation-plan.md) | 完成：註冊、登入、JWT、refresh token、登入限流 |
| [api-gateway-implementation-plan.md](implementation-plans/backend/api-gateway-implementation-plan.md) | 完成：路由、JWT 驗簽、身分 header、封鎖 `/internal/**`、登入限流 |
| [inventory-service-implementation-plan.md](implementation-plans/backend/inventory-service-implementation-plan.md) | 完成：庫存、預扣、確認、釋放、預扣逾時釋放 |
| [order-service-implementation-plan.md](implementation-plans/backend/order-service-implementation-plan.md) | 完成：建單、查詢、取消、付款通知、付款逾時 |
| [payment-service-implementation-plan.md](implementation-plans/backend/payment-service-implementation-plan.md) | 完成：建立付款、模擬金流回呼、通知重試；含完整下單流程的實測 |
| [product-image-upload-implementation-plan.md](implementation-plans/backend/product-image-upload-implementation-plan.md) | 商品圖片上傳的設計、實作與驗收（相容 S3，本機用 SeaweedFS）；完成並可正常上架 |
| [vue-management/](implementation-plans/vue-management/README.md) | 完成（Step 0–12）：後台前端（Vue 3 + TS + Element Plus），登入、商品、圖片、庫存、訂單、使用者 |
| [vue-customer/](implementation-plans/vue-customer/README.md) | 完成（Step 0–12）：顧客用的商店前台（Vue 3 + TS），瀏覽、購物車、下單、模擬付款、我的訂單 |
| [backend-deployment-implementation-plan.md](implementation-plans/backend-deployment-implementation-plan.md) | 完成：後端 6 個服務與資料庫、Redis、物件儲存、兩個前端放進 K8s（kind 驗證）；NetworkPolicy、Secret；含實際攔截測試 |
| [middleware-roadmap.md](implementation-plans/middleware/middleware-roadmap.md) | 中介軟體導覽、階段狀態、決策與暫緩範圍；各階段計畫、紀錄與證據由此進入 |
| [implementation-guidelines.md](implementation-plans/middleware/implementation-guidelines.md) | 階段 D／E 共用流程、提交、文件、測試與誠實回報原則 |


## 設計與評估

| 文件 | 狀態 |
|---|---|
| [product-service-middleware-evaluation.md](design/product-service-middleware-evaluation.md) | 原始技術評估、取捨與延後理由；已採用方案與進度見路線圖 |

## 安全

| 文件 | 內容 |
|---|---|
| [known-issues.md](security/known-issues.md) | 已知的安全問題與 SEC 編號追蹤、驗證過沒有問題的項目、還沒檢查的範圍；**上線前必讀** |

## 文件維護

2026-11-13 補上 spring-order-service 類別導讀，涵蓋 28 個 Java 來源檔、九個內部 record、一個內部 enum、九個 migration 與七張 Mermaid 圖，說明建單快照／補償、冪等、狀態交易、付款事件與逾時排程。三份異動文件的 88 個本機連結、全部 39 個主程式／資源／設定檔與 UTF-8 無 BOM 已核對，圖形區塊結構檢查通過；未重新執行服務測試或渲染 Mermaid。

2026-11-13 補上 spring-notification-service 類別導讀，涵蓋 12 個 Java 來源檔、三個內部 record、五個 migration、五份信件模板與六張 Mermaid 圖，說明事件投影、交易去重、租約與 SMTP 重試。三份異動文件的 62 個本機連結、全部 24 個主程式／資源／設定檔與 UTF-8 無 BOM 已核對，圖形區塊結構檢查通過；未重新執行服務測試或渲染 Mermaid。

2026-11-13 補上 spring-inventory-service 類別導讀，涵蓋 25 個 Java 來源檔、三個內部 record、六個 migration 與七張 Mermaid 圖，說明數量與狀態轉換、交易／冪等及 HTTP／事件／排程入口。三份異動文件的 78 個本機連結（含章節引用）、全部 33 個來源／設定檔與 UTF-8 無 BOM 已核對，圖形區塊結構檢查通過；未重新執行服務測試或渲染 Mermaid。

2026-11-13 補上 spring-api-gateway 類別導讀，涵蓋 13 個 Java 來源檔、內部限流 Config、YAML／Maven 接線與六張 Mermaid 圖。三份異動文件的 62 個本機連結（含章節引用）、全部 15 個主程式／設定檔、七條路由共用五個熔斷名稱與 UTF-8 無 BOM 已核對，圖形區塊結構檢查通過；未執行 Mermaid 渲染或重新執行服務測試。

2026-11-13 補上 observability 類別與設定導讀，涵蓋一個 Java 類別、三個資源與 Maven 設定，包含四張 Mermaid 圖。三份異動文件的 54 個本機連結（含章節引用）、七個應用的依賴／設定匯入與 UTF-8 無 BOM 已核對，圖形區塊結構檢查通過；未執行 Mermaid 渲染、應用程式測試或 Collector 端到端驗收。

2026-11-13 新增 `class-relationships/` 文件類型與 common 導讀，涵蓋 32 個 Java 主程式來源檔、9 張 Mermaid 圖與重要內部型別。三份異動文件的 91 個本機連結（含章節引用）、UTF-8 無 BOM 與圖形區塊結構已檢查；未執行 Mermaid 渲染或重新執行應用程式測試。

2026-11-12 新增系統導讀，作為跨服務的閱讀入口，文件數增為 25 份。內容已核對建單、付款、身分驗證、搜尋、通知與本機啟動程式；本次 2 份異動文件的 53 個本機連結、13 個章節引用及 UTF-8 編碼檢查通過，未重新執行應用程式測試或部署。

2026-11-12 整合：Markdown 由 55 份減為 24 份。前後台各自的 Step 0–12 與後台 API 實測收進各自 README；C／D／E 原始審查收進對應實作紀錄；圖片上傳設計收進實作文件。31 份被完整取代的舊檔已移除，引用改為章節連結，決策、限制、測試結果與原始證據保留。

後續同一功能的補充直接更新既有章節，避免再拆成逐步小檔。跨服務架構、事件契約、安全清單與共用規範保持獨立，讓其他文件可共用引用；搜尋與通知的計畫、實作紀錄分開，以區分驗收要求與實測結果。

整理驗證：逐行對照 31 份舊檔，除標題層級、引用路徑與重複導覽外，正文與 10 段程式碼區塊均保留；246 個本機連結及 114 個章節引用通過檢查。24 份 Markdown 通過 UTF-8 無 BOM／替代字元檢查，`git diff --check` 通過。前後台 E2E 設定與環境變數錯誤提示中的文件路徑同步更新，四個檔案的 Prettier 檢查通過；未重跑應用程式測試。
