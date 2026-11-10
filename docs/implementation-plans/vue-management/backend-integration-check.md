# vue-management 接上後端的實測

> 屬於 [vue-management 實作計畫](README.md)
> 日期：2026-11-10（Step 10 完成後、Step 11 之前）

Step 3–10 寫完時都只用 MSW 驗證過。這次把整套後端在本機跑起來，照前端各頁面的呼叫順序打一遍真正的 API。

## 環境

| 項目 | 做法 |
| ---- | ---- |
| 基礎設施 | `infra/docker-compose.yml`：5 個 Postgres、3 個 Redis、SeaweedFS |
| 後端 | `./mvnw package -DskipTests` 後，6 個服務各自 `java -jar` 在本機執行（8080–8085），`/actuator/health` 都是 200 |
| user-service | `--spring.profiles.active=dev`，由 `BootstrapAdminInitializer` 建立 ADMIN |
| 前端 | `npm run dev`（`http://localhost:5173`），API 經由 Vite proxy 轉給 Gateway |

- 測試用的 ADMIN 另外建一個 `claude-admin@example.com`（用環境變數 `BOOTSTRAP_ADMIN_EMAIL`、`BOOTSTRAP_ADMIN_PASSWORD` 覆蓋 `secrets/dev.env`），不使用個人的管理員帳號
- `object-storage-init` 又遇到 Step 0 記錄的 `fail to dial`（filer 還沒準備好就執行，仍以 0 結束），`docker compose up object-storage-init` 重跑一次就好

## 方式

Chrome 擴充功能沒有連上，這次**沒有操作畫面**，改用腳本照前端的呼叫順序打 API：

- 我們的 API 全部經過 Vite proxy（`http://localhost:5173/api/v1/...`），和瀏覽器走同一條路
- 每個請求帶 `Origin: http://localhost:5173`
- 圖片上傳和瀏覽器一樣直接 PUT 到 presigned URL（SeaweedFS `:8333`），先送一次 CORS 預檢（`OPTIONS`，`Access-Control-Request-Headers: content-type`）；PUT 時帶上 `requiredHeaders` 裡的 `Content-Type`，`Content-Length` 不手動設定，交給 HTTP 用戶端依 body 自動帶（和瀏覽器一致）
- 測試資料以 `e2e-<timestamp>` 為前綴；這次是 `e2e-1794284652`

## 結果：58 項，全部符合預期

| 範圍 | 確認的行為 |
| ---- | ---------- |
| 登入（Step 3） | ADMIN 登入、`/users/me` 有 `ADMIN`；無效 token → 401 `UNAUTHENTICATED`（前端據此 refresh）；refresh 會輪替 token；帳密錯誤 → `INVALID_CREDENTIALS`；登出 204，之後 refresh 被拒 |
| 分類（Step 5） | 新增、子分類；slug 重複 → `CATEGORY_SLUG_DUPLICATED`；帶 `version` 停用；舊 `version` → `VERSION_CONFLICT`；後台列表有停用的分類，公開的分類樹沒有；刪除有子分類的 → `DATA_CONFLICT` |
| 商品（Step 6） | 新增為 `DRAFT`；新增時 SKU 重複 → `DATA_CONFLICT`；沒有分類上架 → `PRODUCT_ACTIVE_CATEGORY_REQUIRED`；沒有主圖上架 → `PRODUCT_PRIMARY_IMAGE_REQUIRED`；關鍵字、排序；封存後修改 → `PRODUCT_ARCHIVED` |
| 圖片（Step 7） | 申請上傳網址；**SeaweedFS 預檢與 PUT 都回 `Access-Control-Allow-Origin: *`**；**不手動帶 `Content-Length`，簽章仍通過**；登記後第一張是主圖；公開網址可以讀到圖片；GIF → `IMAGE_CONTENT_TYPE_NOT_ALLOWED`；排序、設主圖、補齊條件後上架成功；上架中刪主圖 → `PRODUCT_IMAGE_PRIMARY_UNDELETABLE` |
| 庫存（Step 8） | 還沒建立 → 404 `STOCK_NOT_FOUND`；第一次設定 201、之後 200 |
| 訂單（Step 9） | 顧客下單後庫存被預扣；後台取消待付款訂單，庫存釋放；未付款出貨 → `ORDER_NOT_SHIPPABLE`；模擬付款成功後變成 `PAID`；已付款取消 → `ORDER_NOT_CANCELLABLE`；出貨、完成並記錄時間；依使用者與狀態篩選；`userId` 不是 UUID → 400（前端搜尋前就擋下） |
| 使用者（Step 10） | 關鍵字、角色篩選與排序；顧客呼叫 → 403 |

### 一開始判定失敗、實際上正確的一項

- 腳本預期「剛換過的舊 refresh token 立刻再用會被拒」，實際上回了新的 token
- 原因是 user-service 的 `refresh-token-reuse-grace: 10s`：換新後 10 秒內舊的 token 仍可使用，給同時送出 refresh 的情況（例如兩個分頁）用。這是設計好的行為，腳本的預期錯了
- 對前端是好事：Step 3 的「同時只發一次 refresh」只能管同一個分頁，多個分頁同時 refresh 時靠這個寬限期

## 還沒驗證的

- **畫面本身**：版面、表單互動、拖曳排序、`ElMessage`、窄螢幕等，要在瀏覽器實際操作（Chrome 擴充功能連上後，或手動用 `http://localhost:5173` 操作）
- **瀏覽器的 CORS 行為**：腳本確認了回應的 CORS header，但沒有真的由瀏覽器執行同源政策檢查；header 正確時瀏覽器應該會放行

## 留在本機資料庫的資料

- ADMIN 帳號 `claude-admin@example.com`
- 名稱以 `e2e-1794284652` 開頭的分類（含一個停用的子分類）、一個已封存的商品（2 張圖片、庫存 20）、一個顧客帳號 `e2e-1794284652@example.com`、兩張訂單（一張已取消、一張已完成）
