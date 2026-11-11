# 文件索引

## 分類

| 資料夾 | 放什麼 |
|---|---|
| `architecture/` | 整體架構：服務切分、API、全域慣例、服務之間的互動 |
| `implementation-plans/` | 每個服務（或共用模組）的實作步驟、進度與實際驗證結果 |
| `design/` | 單一功能或技術選型的設計、評估，還沒排進實作計畫的也放這裡 |

新增文件時依上表放進對應的資料夾；文件之間用相對路徑連結。

## 架構

| 文件 | 內容 |
|---|---|
| [microservice-architecture.md](architecture/microservice-architecture.md) | 服務清單、port 分配、各服務 API、Gateway 路由、下單流程、建立順序；**先讀這份** |

## 實作計畫

依建立順序排列：

| 文件 | 狀態 |
|---|---|
| [backend-common-implementation-plan.md](implementation-plans/backend-common-implementation-plan.md) | 共用模組：錯誤格式、稽核、身分 header、`@CurrentUserId`、`@RequireRoles` |
| [user-service-implementation-plan.md](implementation-plans/user-service-implementation-plan.md) | 完成：註冊、登入、JWT、refresh token、登入限流 |
| [api-gateway-implementation-plan.md](implementation-plans/api-gateway-implementation-plan.md) | 完成：路由、JWT 驗簽、身分 header、封鎖 `/internal/**`、登入限流 |
| [inventory-service-implementation-plan.md](implementation-plans/inventory-service-implementation-plan.md) | 完成：庫存、預扣、確認、釋放、預扣逾時釋放 |
| [order-service-implementation-plan.md](implementation-plans/order-service-implementation-plan.md) | 完成：建單、查詢、取消、付款通知、付款逾時 |
| [payment-service-implementation-plan.md](implementation-plans/payment-service-implementation-plan.md) | 完成：建立付款、模擬金流回呼、通知重試；含完整下單流程的實測 |
| [product-image-upload-implementation-plan.md](implementation-plans/product-image-upload-implementation-plan.md) | 完成：商品圖片上傳（相容 S3 的物件儲存，本機用 SeaweedFS），商品能正常上架 |
| [vue-management/](implementation-plans/vue-management/README.md) | 進行中（Step 10 完成，剩下部署與 E2E）：後台前端（Vue 3 + TS + Element Plus），登入、商品、圖片、庫存、訂單、使用者 |
| [vue-customer/](implementation-plans/vue-customer/README.md) | 進行中（Step 0–6 完成：後端前置問題、專案骨架、HTTP 層與登入狀態、註冊登入、商品列表、商品詳情、購物車）：顧客用的商店前台（Vue 3 + TS），瀏覽、購物車、下單、模擬付款、我的訂單 |

## 設計與評估

| 文件 | 狀態 |
|---|---|
| [product-service-middleware-evaluation.md](design/product-service-middleware-evaluation.md) | product-service 的中介軟體評估；Redis 已導入，其餘評估中 |
| [product-image-upload-design.md](design/product-image-upload-design.md) | 商品圖片上傳（MinIO / S3，本機改用 SeaweedFS）；已實作 |
