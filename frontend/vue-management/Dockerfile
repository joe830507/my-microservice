# 階段 1：建置。node 版本要符合 package.json 的 engines（^22.18 || >=24.12）
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# build 包含型別檢查（vue-tsc）；測試與 lint 在 CI 先跑，不放進映像檔的建置
RUN npm run build

# 階段 2：只留靜態檔與 nginx。unprivileged 版本用非 root 執行、監聽 8080，不需要 root 權限
FROM nginxinc/nginx-unprivileged:1.29-alpine

# nginx 映像檔啟動時會把 /etc/nginx/templates/*.template 用環境變數展開到 /etc/nginx/conf.d/
# 只展開下面兩個變數，nginx 自己的 $uri、$host 等變數不會被動到
ENV NGINX_ENVSUBST_FILTER="^(API_GATEWAY_URL|STORAGE_PUBLIC_ORIGIN)$"
# 後端的位址：K8s 上是 Service 名稱（見 k8s/configmap.yaml），不是寫死在映像檔裡的 localhost
ENV API_GATEWAY_URL=http://api-gateway:8080
# 商品圖片的來源（後端的 STORAGE_PUBLIC_BASE_URL）：顯示與直傳都在這裡，CSP 的 img-src 與 connect-src 要允許它
ENV STORAGE_PUBLIC_ORIGIN=http://localhost:8333

COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/security-headers.inc.template /etc/nginx/templates/security-headers.inc.template
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 8080
