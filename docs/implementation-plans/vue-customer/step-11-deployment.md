# vue-customer Step 11：部署

> 屬於 [vue-customer 實作計畫](README.md)

專案裡原本沒有任何 Dockerfile 或 K8s 設定（vue-management 的部署也還沒做），vue-customer 是第一個。後台之後可以照這一套改。

## 內容

| 檔案 | 內容 |
| ---- | ---- |
| `.env.production` | `VITE_API_BASE=/api/v1`。**沒有這個檔案時 production build 的 `VITE_API_BASE` 是 `undefined`**，所有請求會打到 `/products` 而不是 `/api/v1/products`；開發時有 `.env.development` 掩蓋了這件事，部署才會發現 |
| `Dockerfile` | 多階段：`node:24-alpine` 建置（`npm ci`、`npm run build`，含 `vue-tsc` 型別檢查）→ `nginxinc/nginx-unprivileged:1.29-alpine`（非 root、監聽 8080）。測試與 lint 在 CI 先跑，不放進映像檔的建置 |
| `nginx/default.conf.template` | SPA fallback、`/api/v1/` 轉發、快取、探針；啟動時用環境變數 `API_GATEWAY_URL`、`STORAGE_PUBLIC_ORIGIN` 展開（只展開這兩個，nginx 自己的 `$uri` 等變數不受影響） |
| `nginx/security-headers.inc.template` | 安全標頭（見下） |
| `k8s/` | kustomize：`configmap.yaml`、`deployment.yaml`（2 個副本）、`service.yaml`（ClusterIP）、`kustomization.yaml` |
| `.dockerignore` | 不把 `node_modules`、`dist`、`.git` 送進建置 |

### nginx 的幾個決定

- **SPA fallback**：找不到實體檔案的路徑（`/p/123`、`/orders/x/pay`）回 `index.html`，由 vue-router 處理，包含 404 頁
- **只轉發 `/api/v1/`**：其他 `/api/...`（例如 `/api/actuator/health`）直接回 404，不會被轉給後端
- **`X-Forwarded-For` 用覆蓋**（`$remote_addr`），不附加用戶端自己帶的值：否則依 IP 的登入限流可以被偽造 header 繞過。如果前面還有 Ingress，要改成信任 Ingress 傳來的值
- **清掉 `X-User-Id`、`X-User-Roles`**：身分 header 只能由 Gateway 在驗過 JWT 後產生，nginx 這層多清一次（Gateway 本來就會清）
- **快取**：`/assets/`（檔名帶雜湊）一年、`immutable`；`index.html` 與 SPA fallback 一律 `no-cache`，發布新版後使用者才不會拿到指向舊檔名的頁面。`/assets/` 不要同時用 `expires` 與 `add_header Cache-Control`，會輸出兩個重複的標頭（實測發現並修正）
- **`add_header` 不繼承**：location 裡只要有自己的 `add_header`，就不會繼承 server 層的。所以安全標頭放在 `security-headers.inc`，每個 location 各自 `include`，server 層不重複 include（否則沒有 `add_header` 的 location 會輸出兩份）
- `/healthz`：K8s 的存活與就緒探針
- 請求本體上限 1m（前端不上傳檔案）、`proxy_read_timeout 30s`（對應 axios 的 30 秒逾時）、`server_tokens off`

### 安全標頭（對應 [SEC-06](../../security/known-issues.md#sec-06-前端缺少瀏覽器端防護)）

- `Content-Security-Policy`：`default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: <STORAGE_PUBLIC_ORIGIN>; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`。能用這麼嚴格是因為打包後的 `index.html` 沒有 inline script 或 style，Vue 用的是不含執行期編譯器的版本（不需要 `unsafe-eval`），API 經 nginx 轉發所以同源。商品圖片在物件儲存（另一個來源），所以 `img-src` 要加上 `STORAGE_PUBLIC_ORIGIN`——**後端的 `STORAGE_PUBLIC_BASE_URL` 改了（例如換成 CDN 網域），這個值要跟著改**，否則圖片會被 CSP 擋掉
- `X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy: strict-origin-when-cross-origin`、`Permissions-Policy`（關掉相機、麥克風、定位、付款）
- **HSTS 沒有加**：只能設在 HTTPS 上，TLS 在 Ingress 終結，要在那裡加

### K8s 的決定

- Service 是 **ClusterIP**：對外請用 Ingress，不要用 NodePort 或 LoadBalancer 直接曝露
- 後端位址放在 ConfigMap（`API_GATEWAY_URL: http://api-gateway:8080`），映像檔裡的預設值也是 K8s 的 Service 名稱，不是 localhost；符合「服務探索用 K8s 原生，不用註冊中心」的決定
- Pod 以非 root 執行、`allowPrivilegeEscalation: false`、拿掉所有 capability、`seccompProfile: RuntimeDefault`、設 requests／limits。**沒有開 `readOnlyRootFilesystem`**：啟動時要把設定樣板展開寫進 `/etc/nginx/conf.d`
- nginx 在啟動時就解析 `api-gateway` 的主機名稱：**後端的 Service 還不存在時 nginx 會啟動失敗（`host not found in upstream`）**，Pod 會不斷重啟，直到 Service 建好才正常（實測確認）。探針沒通過之前不會有流量進來，所以不影響使用者，只是部署順序上先建好 `api-gateway` 的 Service 比較乾淨
- 映像檔是本機 `vue-customer:0.1.0`、`imagePullPolicy: Never`（kind 用 `kind load` 載入）；換成映像檔倉庫後要改

## 驗證

### 在本機用 Docker 驗證 nginx

用 `docker run -e API_GATEWAY_URL=http://host.docker.internal:8080` 對本機真的 Gateway 驗證：
- 以非 root（uid 101）執行；設定樣板只展開兩個變數
- `/`、`/p/abc`、`/orders/x/pay`、`/no/such/page`、`/login` 都回 200 `text/html`（SPA fallback）
- 靜態資源只有一個 `Cache-Control: public, max-age=31536000, immutable`；不存在的 asset 回 404
- `/api/v1/categories/tree`、`/api/v1/products` 轉發到真的 Gateway 回 200；`/api/actuator/health`、`/api/v2/anything` 回 404；`/actuator/health` 是 SPA 的 `index.html`，不是後端的
- 偽造 `X-User-Roles: ADMIN` 讀草稿商品：404
- 每個回應都有五個安全標頭

### 在 kind 上驗證（計畫要求的）

1. 建立叢集時發現：**這台機器的 Docker 用 cgroup v1，kind 節點映像檔（kubelet 1.37）預設拒絕在 cgroup v1 上執行**（`failCgroupV1: true`），`kubeadm init` 會失敗。用 kind 設定檔暫時允許（不需要改系統的 WSL 設定）：

   ```yaml
   kind: Cluster
   apiVersion: kind.x-k8s.io/v1alpha4
   nodes:
     - role: control-plane
       kubeadmConfigPatches:
         - |
           kind: KubeletConfiguration
           failCgroupV1: false
   ```

   `kind create cluster --name my-microservice --config <上面的檔案>`。長期要處理，cgroup v1 之後會被移除（把 Docker／WSL 切到 cgroup v2）。
2. `kind load docker-image vue-customer:0.1.0 --name my-microservice`，`kubectl apply -k k8s`
3. 因為後端還沒有放進 K8s，驗證時另外建一個**只用於驗證、不放進 repo** 的 Service，讓叢集裡的 `api-gateway:8080` 指向本機的 Gateway：

   ```yaml
   apiVersion: v1
   kind: Service
   metadata:
     name: api-gateway
   spec:
     type: ExternalName
     externalName: host.docker.internal
   ```

4. `kubectl port-forward svc/vue-customer 8091:80`，從叢集外**透過 K8s 裡的 nginx** 走完整流程，全部成功：瀏覽分類與商品 → 商品詳情與庫存 → 註冊、登入 → 購物車 batch → 建單（`PENDING_PAYMENT`，2560）→ 建立付款 → 付款成功回呼 → 輪詢 1 次訂單變 `PAID` → 我的訂單 → **後台（管理員）看到同一張訂單**

## 還沒做的

- **沒有在瀏覽器裡驗證過 CSP**：Chrome 擴充功能沒有連上，沒辦法開瀏覽器確認頁面在嚴格 CSP 下能正常載入、圖片能顯示。依據是打包後沒有 inline script／style、沒有 `eval`（已檢查 `dist`），理論上沒有問題，但需要實際看一次（開 http://localhost:8091，看瀏覽器主控台有沒有 CSP 違規）
- 上面的流程是用 HTTP 呼叫走的，不是在瀏覽器點擊，所以前端畫面本身（版面、圖片、倒數）在 K8s 版本上沒有被驗證
- 後端的 Dockerfile／K8s 設定、`NetworkPolicy` 見 [後端部署](../backend-deployment-implementation-plan.md)（兩個前端與後端已經一起在 kind 上部署並驗證）
- Ingress、TLS、HSTS；映像檔倉庫與 CI；`securityContext.readOnlyRootFilesystem`
- 和後台一起規劃網域（例如 `shop.example.com`、`admin.example.com`）

## 清理

驗證用的叢集與 port-forward 還開著：`kind delete cluster --name my-microservice`。
