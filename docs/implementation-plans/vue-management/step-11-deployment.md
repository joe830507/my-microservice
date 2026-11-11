# vue-management Step 11：部署

> 屬於 [vue-management 實作計畫](README.md)

照 [vue-customer Step 11](../vue-customer/step-11-deployment.md) 的做法（Dockerfile、nginx、K8s、安全標頭的決定與理由都寫在那裡，這裡只記錄差異與驗證）。檔案在 `frontend/vue-management/`：`Dockerfile`、`.dockerignore`、`.env.production`、`nginx/`、`k8s/`。

## 和 vue-customer 的差異

- **`.env.production`**：vue-management 同樣沒有這個檔案，production build 的 `VITE_API_BASE` 會是 `undefined`，所有請求會打到 `/users` 而不是 `/api/v1/users`。已補上
- **CSP 的 `connect-src` 要允許物件儲存的來源**：後台上傳圖片是瀏覽器用 presigned URL **直接 PUT 到物件儲存**，不經過 nginx，所以除了 `img-src`，`connect-src` 也要加 `STORAGE_PUBLIC_ORIGIN`（vue-customer 不上傳，不需要）。實測簽章上傳網址的主機是 `localhost:8333`，和 `STORAGE_PUBLIC_ORIGIN` 一致；**後端的儲存網址改了（例如換成 CDN／儲存網域），ConfigMap 的這個值要跟著改**，否則上傳會被 CSP 擋掉
- 打包內容檢查：`index.html` 沒有 inline script／style、沒有 `eval`、執行期沒有對外部網址的請求（只有文件連結與 XML 命名空間字串）；應用程式碼沒有 `blob:`／`data:`、沒有 `fetch`／`XMLHttpRequest`／iframe
- 後台是 Element Plus（按需引入、樣式被抽成 CSS 檔），沒有執行期注入 `<style>`；`style-src 'self'` 理論上可行，但**沒有在瀏覽器驗證過**。如果實際發現某個元件被 CSP 擋掉，先在瀏覽器確認是哪個，再決定要不要放寬 `style-src`
- 資源名稱、映像檔名稱（`vue-management:0.1.0`）、ConfigMap／Deployment／Service 名稱都是 `vue-management`，可以和 `vue-customer` 部署在同一個 namespace
- 後台 API 的 JSON 很小，圖片不經過 nginx，所以 `client_max_body_size 1m` 夠用（註解已改成說明這點）

## 驗證

**1. 本機 Docker**（`docker run -e API_GATEWAY_URL=http://host.docker.internal:8080`，對真的 Gateway）：
- 以非 root（uid 101）執行
- `/`、`/login`、`/products`、`/products/new`、`/orders/abc`、`/no/such` 都回 200 `text/html`（SPA fallback）
- 每個回應都有五個安全標頭，CSP 的 `img-src`、`connect-src` 都含 `http://localhost:8333`
- 靜態資源只有一個 `Cache-Control: public, max-age=31536000, immutable`；CSS 回 `text/css`
- 管理員經 nginx 登入、查 `/users`、`/orders`、草稿商品都成功；匿名查 `/users` 回 401；`/api/actuator/health` 回 404
- vue-management 的 261 個測試通過

**2. kind**（計畫要求：登入 → 建立商品 → 上傳主圖 → 上架）。叢集建立方式與 cgroup v1 的處理、驗證用的 `ExternalName` Service 見 [vue-customer Step 11](../vue-customer/step-11-deployment.md#在-kind-上驗證計畫要求的)。這次先建好 `api-gateway` 的 Service 再部署，Pod 一次就 Ready，沒有出現「找不到上游主機」的重啟。經 `kubectl port-forward svc/vue-management` 從叢集外透過 K8s 裡的 nginx，全部成功：
1. 管理員登入，`roles: ['ADMIN']`；匿名新增商品 → 401
2. 建立商品（`DRAFT`）
3. 還沒有主圖就上架 → 被擋（400 `PRODUCT_PRIMARY_IMAGE_REQUIRED`）
4. 申請上傳網址（經 nginx）→ **直接 PUT 到物件儲存**（不經 nginx）→ 登記圖片，`primary=true`
5. 上架 → `ACTIVE`，設定庫存
6. 匿名在商店搜尋，看得到這件商品、原價與主圖

驗證用的商品是目錄裡合理的新品（`HAT-002`「保暖針織毛帽」，木棉 KAPOK，420 元、原價 520），不是測試垃圾，留在資料庫。驗證完叢集已刪除。

## 還沒做的

- **CSP 已在 [Step 12](step-12-e2e.md) 於真實 Chrome 驗證**：對著這個 nginx 映像檔跑 E2E，Element Plus 的元件、圖片直傳（`connect-src`）與圖片顯示（`img-src`）都沒有被擋；原本擔心的 `style-src 'self'` 沒有問題
- 後端 6 個服務與資料庫都還沒有 Dockerfile／K8s 設定
- Ingress、TLS、HSTS、映像檔倉庫與 CI；和商店一起規劃網域（例如 `shop.example.com`、`admin.example.com`）
- 後台不應該對外公開給所有人：之後在 Ingress 層限制來源（例如內網、VPN 或 IP allowlist），前端的登入檢查只是畫面，真正的權限在後端
