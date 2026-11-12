# 文件索引

## 分類

| 資料夾 | 放什麼 |
|---|---|
| `architecture/` | 整體架構：服務切分、API、全域慣例、服務之間的互動 |
| `implementation-plans/` | 實作步驟、進度與實際驗證結果；分 `backend/`（各服務與共用模組）、`middleware/`（中介軟體各階段）、`vue-customer/`、`vue-management/`，跨層的後端部署計畫放在根目錄 |
| `design/` | 單一功能或技術選型的設計、評估，還沒排進實作計畫的也放這裡 |
| `security/` | 已知的安全問題：現象、驗證方式、處理方向與處理狀態 |

新增文件時依上表分類，使用相對路徑連結、繁體中文與 UTF-8 無 BOM。

先讀架構與安全事項，再進入對應計畫。計畫保留要求與決策，實作紀錄保存結果、限制與證據；`phase-*-review.md` 是審查當時的發現，後續處理以實作紀錄為準。歷史的「現況」與測試數字不代表目前狀態，不因整理文件而重新宣稱驗證通過。

## 架構

| 文件 | 內容 |
|---|---|
| [microservice-architecture.md](architecture/microservice-architecture.md) | 服務清單、port 分配、各服務 API、Gateway 路由、下單流程、建立順序；**先讀這份** |
| [event-contracts.md](architecture/event-contracts.md) | 事件格式、版本、交易、去重、重試與消費行為的依據 |

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
| [product-image-upload-implementation-plan.md](implementation-plans/backend/product-image-upload-implementation-plan.md) | 完成：商品圖片上傳（相容 S3 的物件儲存，本機用 SeaweedFS），商品能正常上架 |
| [vue-management/](implementation-plans/vue-management/README.md) | 完成（Step 0–12）：後台前端（Vue 3 + TS + Element Plus），登入、商品、圖片、庫存、訂單、使用者 |
| [vue-customer/](implementation-plans/vue-customer/README.md) | 完成（Step 0–12）：顧客用的商店前台（Vue 3 + TS），瀏覽、購物車、下單、模擬付款、我的訂單 |
| [backend-deployment-implementation-plan.md](implementation-plans/backend-deployment-implementation-plan.md) | 完成：後端 6 個服務與資料庫、Redis、物件儲存、兩個前端放進 K8s（kind 驗證）；NetworkPolicy、Secret；含實際攔截測試 |
| [middleware-roadmap.md](implementation-plans/middleware/middleware-roadmap.md) | 中介軟體導覽、階段狀態、決策與暫緩範圍；各階段計畫、紀錄與證據由此進入 |
| [implementation-guidelines.md](implementation-plans/middleware/implementation-guidelines.md) | 階段 D／E 共用流程、提交、文件、測試與誠實回報原則 |


## 設計與評估

| 文件 | 狀態 |
|---|---|
| [product-service-middleware-evaluation.md](design/product-service-middleware-evaluation.md) | 原始技術評估、取捨與延後理由；已採用方案與進度見路線圖 |
| [product-image-upload-design.md](design/product-image-upload-design.md) | 商品圖片上傳（MinIO / S3，本機改用 SeaweedFS）；已實作 |

## 安全

| 文件 | 內容 |
|---|---|
| [known-issues.md](security/known-issues.md) | 已知的安全問題與 SEC 編號追蹤、驗證過沒有問題的項目、還沒檢查的範圍；**上線前必讀** |
