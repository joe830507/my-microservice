# 文件索引

## 分類

| 資料夾 | 放什麼 |
|---|---|
| `architecture/` | 整體架構：服務切分、API、全域慣例、服務之間的互動 |
| `implementation-plans/` | 實作步驟、進度與實際驗證結果；分 `backend/`（各服務與共用模組）、`middleware/`（中介軟體各階段）、`vue-customer/`、`vue-management/`，跨層的後端部署計畫放在根目錄 |
| `design/` | 單一功能或技術選型的設計、評估，還沒排進實作計畫的也放這裡 |
| `security/` | 已知的安全問題：現象、驗證方式、處理方向與處理狀態 |

新增文件時依上表放進對應的資料夾；文件之間用相對路徑連結。

## 架構

| 文件 | 內容 |
|---|---|
| [microservice-architecture.md](architecture/microservice-architecture.md) | 服務清單、port 分配、各服務 API、Gateway 路由、下單流程、建立順序；**先讀這份** |

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
| [middleware-roadmap.md](implementation-plans/middleware/middleware-roadmap.md) | 進行中：中介軟體導入計畫（可觀測性 → 韌性 → Kafka + Outbox → Elasticsearch → 通知 → 真的金流）；階段 A–C 完成，D–F 規劃中 |
| [observability-implementation-plan.md](implementation-plans/middleware/observability-implementation-plan.md) | 完成（中介軟體階段 A）：指標、追蹤、log 用 OTLP 送到 Grafana LGTM；儀表板、告警規則；本機與 kind 都驗證 |
| [resilience-implementation-plan.md](implementation-plans/middleware/resilience-implementation-plan.md) | 完成（中介軟體階段 B）：重試與熔斷、Gateway 熔斷、排程分散式鎖（ShedLock）、2 個副本、優雅關閉；kind 上實測 |
| [event-implementation-plan.md](implementation-plans/middleware/event-implementation-plan.md) | 完成（中介軟體階段 C）：Kafka + Outbox、消費端去重與 DLT、付款與庫存改事件、商品與使用者事件；kind 停機恢復與 trace、告警實測 |
| [search-implementation-plan.md](implementation-plans/middleware/search-implementation-plan.md) | 規劃中（中介軟體階段 D）：Elasticsearch 搜尋的詳細計畫、索引與查詢設計、步驟 D1–D7，以及給實作者的規範 |

## 設計與評估

| 文件 | 狀態 |
|---|---|
| [product-service-middleware-evaluation.md](design/product-service-middleware-evaluation.md) | product-service 的中介軟體評估；Redis 已導入，其餘評估中 |
| [product-image-upload-design.md](design/product-image-upload-design.md) | 商品圖片上傳（MinIO / S3，本機改用 SeaweedFS）；已實作 |

## 安全

| 文件 | 內容 |
|---|---|
| [known-issues.md](security/known-issues.md) | 已知的安全問題（11 項，高風險 3 項）、驗證過沒有問題的項目、還沒檢查的範圍；**上線前必讀** |
