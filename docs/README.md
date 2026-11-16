# 文件索引

第一次接觸專案，先讀[系統導讀：從一筆訂單理解整個專案](architecture/system-guide.md)。它面向懂程式但不熟悉本系統的讀者，依序說明服務分工、資料邊界、核心流程、故障行為與程式閱讀入口。

## 分類

| 資料夾 | 放什麼 |
|---|---|
| `architecture/` | 整體架構：服務切分、API、全域慣例、服務之間的互動 |
| `component-relationships/` | 前端元件關係與職責：路由、頁面／共用元件、store、API 及操作流程 |
| `class-relationships/` | 類別關係圖與職責說明：依後端模組整理類別分工、依賴、建立方式與程式閱讀入口 |
| `implementation-plans/` | 實作步驟、進度與實際驗證結果；分 `backend/`（各服務、共用模組與後端部署計畫）、`middleware/`（中介軟體各階段）、`vue-customer/`、`vue-management/`、`dev-workflow/`（CI 與測試等開發流程）、`reviews/`（已結案的審查）、`evidence/`（驗證證據）；根目錄只放仍在更新的追蹤文件 |
| `design/` | 單一功能或技術選型的設計、評估，還沒排進實作計畫的也放這裡 |
| `operations/` | 維運：告警處理、日常診斷與操作界線 |
| `security/` | 已知的安全問題：現象、驗證方式、處理方向與處理狀態 |

新增文件時依上表分類，使用相對路徑連結、繁體中文與 UTF-8 無 BOM。

文件的逐次整理紀錄見[文件變更紀錄](CHANGELOG.md)。

先讀系統導讀與安全事項，再依問題進入後端類別／前端元件導讀，或查閱對應計畫與驗收紀錄。計畫保留要求與決策，實作紀錄保存結果、限制與證據；原始審查已收進各階段紀錄末尾，保留審查當時的發現，後續處理以紀錄前段為準。歷史的「現況」與測試數字不代表目前狀態，不因整理文件而重新宣稱驗證通過。

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
| [spring-payment-service 類別關係圖與職責說明](class-relationships/spring-payment-service.md) | 20 個來源檔的分工、建立／回呼交易、取消事件、付款狀態與八個 migration |
| [spring-product-service 類別關係圖與職責說明](class-relationships/spring-product-service.md) | 76 個來源檔的職責、快取、圖片補償、搜尋與八個 migration |
| [spring-user-service 類別關係圖與職責說明](class-relationships/spring-user-service.md) | 37 個來源檔的職責、token 輪替／撤銷、登入保護與九個 migration |

## 前端元件關係與職責

| 文件 | 內容 |
|---|---|
| [前端元件文件索引](component-relationships/README.md) | 圖例、範圍與維護方式 |
| [vue-customer 元件關係與職責](component-relationships/vue-customer.md) | 商店路由、元件、狀態、結帳冪等與付款流程 |
| [vue-management 元件關係與職責](component-relationships/vue-management.md) | 管理路由、權限、分類／商品、圖片直傳、庫存與訂單操作 |

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
| [backend-deployment-implementation-plan.md](implementation-plans/backend/backend-deployment-implementation-plan.md) | 完成：後端 6 個服務與資料庫、Redis、物件儲存、兩個前端放進 K8s（kind 驗證）；NetworkPolicy、Secret；含實際攔截測試 |
| [middleware-roadmap.md](implementation-plans/middleware/middleware-roadmap.md) | 中介軟體導覽、階段狀態、決策與暫緩範圍；各階段計畫、紀錄與證據由此進入 |
| [implementation-guidelines.md](implementation-plans/middleware/implementation-guidelines.md) | 階段 D／E 共用流程、提交、文件、測試與誠實回報原則 |

### 進行中的追蹤

`implementation-plans/` 根目錄只放仍在更新的追蹤文件；已結案的審查放在 `reviews/`，各服務與部署計畫放在 `backend/`，開發流程與測試基礎（G-01～G-03、G-10～G-12）的計畫放在 `dev-workflow/`。

| 文件 | 內容 |
|---|---|
| [deferred-items.md](implementation-plans/deferred-items.md) | 暫緩項目依處理時機分類（現在／隨開發流程／隨業務功能／看壓測數據／需求出現時／上線前），含來源、原因及影響 |
| [deferred-now-tasks.md](implementation-plans/deferred-now-tasks.md) | 暫緩清單中「現在」處理的實作任務；T-01～T-03 已完成 |
| [system-gaps-assessment.md](implementation-plans/system-gaps-assessment.md) | 尚未列入任何追蹤清單的缺口（G-01～17）：CI、備份、契約測試、收件地址等，含建議順序 |
| [dev-workflow/testing-overview.md](implementation-plans/dev-workflow/testing-overview.md) | 測試策略與現況：各模組測試類別、外部資源、環境依賴（CI 前置問題的依據） |
| [dev-workflow/ci-implementation-plan.md](implementation-plans/dev-workflow/ci-implementation-plan.md) | G-01 CI 流水線：前置問題 P1～P4、workflow 設計、實作步驟與驗收條件 |

### 審查紀錄（`reviews/`）

| 文件 | 內容 |
|---|---|
| [codebase-optimization-review.md](implementation-plans/reviews/codebase-optimization-review.md) | 20 項完成、兩輪修正後審查結案；[驗證證據](implementation-plans/evidence/codebase-optimization-validation.md)，暫緩範圍見[集中清單](implementation-plans/deferred-items.md) |
| [documentation-gaps-review.md](implementation-plans/reviews/documentation-gaps-review.md) | 四項文件缺口與完成註記，含告警手冊、安全總表、索引與暫緩清單 |
| [documentation-gaps-review-2.md](implementation-plans/reviews/documentation-gaps-review-2.md) | 第二輪文件缺口九項（2026-11-16）：架構參考與程式不一致、測試現況、本機開發；待 Codex 處理 |
| [deferred-now-tasks-review.md](implementation-plans/reviews/deferred-now-tasks-review.md) | T-01～T-03 審查：R1～R7 修正與複審驗證結果 |


## 維運

| 文件 | 內容 |
|---|---|
| [local-development.md](operations/local-development.md) | 本機啟動 notification-service、`local-backend.sh` 行為與疑難排解表 |
| [alert-runbook.md](operations/alert-runbook.md) | 12 條告警的原始查詢／門檻／持續時間、診斷與處理；含本次 kind 查詢結果及未驗證界線 |

## 設計與評估

| 文件 | 狀態 |
|---|---|
| [product-service-middleware-evaluation.md](design/product-service-middleware-evaluation.md) | 原始技術評估、取捨與延後理由；已採用方案與進度見路線圖 |

## 安全

| 文件 | 內容 |
|---|---|
| [known-issues.md](security/known-issues.md) | 已知的安全問題與 SEC 編號追蹤、驗證過沒有問題的項目、還沒檢查的範圍；**上線前必讀** |

## 文件維護

逐次整理紀錄在[文件變更紀錄](CHANGELOG.md)，這裡只放維護規則。

後續同一功能的補充直接更新既有章節，避免再拆成逐步小檔。跨服務架構、事件契約、安全清單與共用規範保持獨立，讓其他文件可共用引用；搜尋與通知的計畫、實作紀錄分開，以區分驗收要求與實測結果。

每次文件整理在變更紀錄加一段：異動文件數、連結與編碼檢查結果，以及沒有執行的驗證；歷史的「現況」與數字不回頭改。
