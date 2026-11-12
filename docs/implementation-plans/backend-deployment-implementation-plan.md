# 後端部署（Docker、K8s）實作計畫

> 建立日期：2026-11-11
> 分支：`feature/backend-deployment`
> 位置：`backend/Dockerfile`、`infra/k8s/`
> 狀態：完成（在本機 kind 上驗證，含 NetworkPolicy 的實際攔截）

把整個系統（6 個後端服務、5 個 PostgreSQL、3 個 Redis、物件儲存、商店與後台兩個前端）放進 K8s。服務探索用 K8s 原生（Service 名稱 + ConfigMap／Secret），不用註冊中心，這是之前定好的做法。前端的部署見 [vue-customer Step 11](vue-customer/step-11-deployment.md)、[vue-management Step 11](vue-management/step-11-deployment.md)。

## 內容

| 位置 | 內容 |
| ---- | ---- |
| `backend/Dockerfile` | 六個服務共用一份，用 `--build-arg SERVICE=user-service` 指定。建置階段不含 `SERVICE`，六次建置共用同一份編譯快取；執行階段是 `eclipse-temurin:21-jre-alpine`，非 root（uid 10001） |
| `backend/.dockerignore` | 排除 `target`、`secrets/`、`*.pem`、`*.env`：**金鑰與密碼不能進映像檔**（實際檢查過映像檔內容） |
| `backend/.gitattributes` | `mvnw` 固定 LF。Windows 工作目錄的 `mvnw` 是 CRLF，在 Linux 容器裡會因為 `#!/bin/sh\r` 找不到直譯器（Dockerfile 也會再轉一次） |
| `infra/k8s/data/` | 5 個 PostgreSQL（StatefulSet，DB per service）、3 個 Redis、物件儲存（SeaweedFS）與建立 bucket 的 Job。本機／示範用，正式環境建議用託管服務 |
| `infra/k8s/services/` | 6 個後端服務的 Deployment 與 Service（ClusterIP） |
| `infra/k8s/network-policies.yaml` | 預設拒絕進入，再逐一開放需要的連線（SEC-02） |
| `infra/k8s/network-policies-egress.yaml` | 預設拒絕出去，只開放 DNS 與每個元件實際需要的對象；資料庫與 Redis 完全不能往外連 |
| `infra/k8s/scripts/create-secrets.sh` | 產生所有 Secret（隨機值，只存在叢集裡） |
| `infra/k8s/scripts/kind-up.sh`、`kind-config.yaml` | 建立本機 kind 叢集並安裝 Calico |
| `infra/k8s/kustomization.yaml` | 把以上加上兩個前端的 `k8s/` 全部放進 namespace `my-microservice` |
| 後端程式碼 | 只改了一處：`storage.upload-endpoint`（見下），commit `55d90d2` |

## 部署步驟（本機 kind）

```bash
# 1. 建叢集並安裝 Calico（約 2 分鐘）
infra/k8s/scripts/kind-up.sh

# 2. 建映像檔（後端第一次編譯整個專案，之後共用快取）
for s in api-gateway user-service product-service inventory-service order-service payment-service; do
  docker build -f backend/Dockerfile --build-arg SERVICE=$s -t my-microservice/$s:0.1.0 backend
done
docker build -t vue-customer:0.1.0 frontend/vue-customer
docker build -t vue-management:0.1.0 frontend/vue-management

# 3. 載入叢集（kind 節點不共用本機的映像庫）
kind load docker-image --name my-microservice \
  my-microservice/{api-gateway,user-service,product-service,inventory-service,order-service,payment-service}:0.1.0 \
  vue-customer:0.1.0 vue-management:0.1.0

# 4. 產生 Secret（重複執行安全：已存在的不會重新產生）
KUBE_CONTEXT=kind-my-microservice infra/k8s/scripts/create-secrets.sh

# 5. 部署
kubectl --context kind-my-microservice apply -k infra/k8s

# 6. 從本機連進去（商店 8091、後台 8093、物件儲存 8333）
kubectl --context kind-my-microservice -n my-microservice port-forward svc/vue-customer 8091:80
kubectl --context kind-my-microservice -n my-microservice port-forward svc/vue-management 8093:80
kubectl --context kind-my-microservice -n my-microservice port-forward pod/object-storage-0 8333:8333
```

- **8333 要先讓出來**：叢集設定的圖片公開網址是 `http://localhost:8333`，本機 compose 的物件儲存也用 8333，要先 `docker stop my-microservice-object-storage`，用完再 `docker start`
- 第一次部署時資料庫還沒起來，各服務會重啟約 3 次，等資料庫就緒後恢復，正常
- 管理員帳密不印出來，用 `create-secrets.sh` 結尾印出的 `kubectl get secret bootstrap-admin ...` 指令讀取
- 清理：`kind delete cluster --name my-microservice`

## 設計決定

### 不改程式碼就能部署：用環境變數覆蓋

Spring 的 relaxed binding 讓 `SPRING_DATASOURCE_URL`、`SPRING_DATA_REDIS_HOST` 這類環境變數直接覆蓋 `application.yml`（資料庫位址與 Redis 位址原本寫死 localhost）。各服務的下游位址（`PRODUCT_SERVICE_URI` 等）本來就是環境變數，預設 localhost，K8s 上改成 Service 名稱。

### 唯一的程式碼改動：上傳簽章網址要用對外位址

商品圖片是瀏覽器用 presigned URL 直接 PUT 到物件儲存。簽章網址原本用 `storage.endpoint` 產生，而這個位址同時也是 product-service 自己連物件儲存用的。K8s 上前者必須是瀏覽器連得到的位址，後者是叢集內部的 Service 名稱，兩個不能是同一個（SigV4 的簽章包含 Host）。

新增可選的 `storage.upload-endpoint`（環境變數 `STORAGE_UPLOAD_ENDPOINT`），只給 S3Presigner 用，沒設定就沿用 `endpoint`，既有行為不變。測試：`PresignUploadEndpointDefault/ConfiguredIntegrationTests`；product-service 158 個測試全過。

### 安全（對應 [known-issues](../security/known-issues.md)）

- **網路隔離（SEC-02）**：`default-deny-ingress` + 逐一開放。結果：沒有授權的 Pod 只連得到前端；前端只連得到 Gateway；Gateway 連得到各服務與自己的 Redis；order-service 連 product／inventory 與自己的資料庫；payment-service 連 order-service 與自己的資料庫；每個資料庫、Redis、物件儲存只有對應的服務連得到
- **Secret（SEC-03）**：資料庫與 Redis 密碼、JWT 金鑰、物件儲存金鑰、管理員密碼全部由 `create-secrets.sh` 隨機產生，**不使用 repo 裡的開發用預設值**（本機的 JWT 金鑰、`s3.json` 金鑰都不會帶進叢集）；服務以 `secretKeyRef` 讀取，JWT 金鑰以唯讀檔案掛載。repo 裡沒有任何 Secret 的值
- **Redis 有設密碼**，沒帶密碼連線回 `NOAUTH`
- **Pod**：非 root、`allowPrivilegeEscalation: false`、拿掉所有 capability、`seccompProfile: RuntimeDefault`；後端服務還加上 `readOnlyRootFilesystem`（只有 `/tmp` 是 emptyDir）
- **Gateway 的 `GATEWAY_TRUSTED_PROXIES=1`**：Gateway 的登入限流依來源 IP，前面是前端 nginx。維持預設 0 時 Gateway 看到的永遠是 nginx 的 Pod IP，**所有使用者共用同一個限流額度**。nginx 已把 `X-Forwarded-For` 覆蓋成真實 IP，所以取最後一個值；實測連續 16 次失敗登入、每次偽造不同的 `X-Forwarded-For`，第 11 次起回 429，偽造沒有繞過。如果前面再加 Ingress，要改成 2
- **探針**：Gateway 的 Spring Security 只放行精確的 `/actuator/health`，`/actuator/health/liveness` 會被 401，所以 Gateway 的探針用 `/actuator/health`；其他服務用 `/actuator/health/liveness`、`/readiness`
- **只有 1 個副本**：order、inventory、payment、user 都有 `@Scheduled` 工作（逾時取消、預扣釋放、通知重試、清除 token），沒有分散式鎖（沒有 ShedLock），多個副本會同時執行。前端各 2 個副本

### 其他

- 資源：後端 requests 384Mi／limits 768Mi（`MaxRAMPercentage=75`、`ExitOnOutOfMemoryError`）；資料庫、Redis 各有 limits
- 資料庫：`postgres:18.6`、StatefulSet、1Gi 磁碟區、以 uid 999 執行；每個服務各自的 DB 帳號（`app_<服務>`）
- 物件儲存的 bucket 與 `tmp/` 一天後自動刪除的規則，由 `object-storage-init` Job 建立（和 compose 的 init 容器相同）

## 驗證

在 kind 上（Calico）：20 個 Pod 全部就緒，Flyway 在 5 個資料庫都遷移成功。

**1. 整個購物流程**（從叢集外透過商店與後台的 nginx）：分類樹與 24 件上架商品（草稿不出現）、商品主圖從叢集內的物件儲存匿名讀取（200 `image/jpeg`）、註冊、登入（Gateway 抓 user-service 的 JWKS 驗證 JWT）、建單（order → product 查商品、order → inventory 預扣）、建立付款（payment → order）、付款成功回呼 → 訂單 `PAID`（輪詢 1 次）、庫存 24 → 22、取消待付款訂單 → 庫存釋放、管理員看得到這張訂單、顧客呼叫管理 API → 403。目錄資料（24 件商品與圖片）也是用管理員的 API 在叢集裡重新灌入，**圖片是用對外位址簽章、直傳到叢集內的物件儲存、再由 product-service 登記**，驗證了 `upload-endpoint`。

**2. NetworkPolicy 的實際攔截**（從不同身分的 Pod 用 `nc` 連線，每項 3 秒逾時）：

| 來源（Pod 的 `app` 標籤） | 連得到 | 連不到 |
| ------------------------- | ------ | ------ |
| `attacker`（沒有授權） | 商店、後台（80） | 全部 6 個後端服務、user-db、order-db、user-redis、gateway-redis、object-storage |
| `vue-customer` | api-gateway:8080 | user-service、order-service、user-db |
| `api-gateway` | user／product／inventory／order／payment-service、gateway-redis | user-redis、user-db |
| `order-service` | product-service、inventory-service、order-db | user-service、payment-service、payment-db |
| `payment-service` | order-service、payment-db | product-service、inventory-service |

## 遇到的問題

1. **kind 預設的 kindnet 在這台機器上完全不執行 NetworkPolicy**。第一次驗證時，沒有授權的 Pod 連得到所有服務、資料庫、Redis，policy 卻都是「建立成功」。kindnet 用 nftables 實作，Windows／WSL2 的核心不支援（日誌：`netlink receive: operation not supported`）。**只用 `kubectl apply` 看不出來，一定要用對照組實測**。解法：`kind-config.yaml` 關掉內建 CNI，`kind-up.sh` 安裝 Calico（iptables 實作）
2. **Calico 在這台機器上起不來**：容器建立失敗 `mkdir /sys/kernel/security: read-only file system`（WSL2 核心沒有 securityfs）。這個掛載只在 eBPF 模式用來偵測核心 lockdown，我們用 iptables 模式用不到，所以 `kind-up.sh` 套用 manifest 時用 `sed` 移除（volume 與 volumeMount 各 3 行）。Calico 版本固定 `v3.33.0`（`CALICO_VERSION` 可覆蓋）
3. **kubelet 預設拒絕在 cgroup v1 上執行**（這台機器的 Docker 是 cgroup v1）：`kind-config.yaml` 設 `failCgroupV1: false`。長期要把 Docker／WSL 切到 cgroup v2
4. **SeaweedFS 在 K8s 上傳失敗（`No writable volumes`）**：它以 `-ip=object-storage` 宣告位址，內部元件（volume server 的 8080／18080）和初始化 Job 都用這個名字連，但 ClusterIP Service 只轉發有列出的埠。解法：Service 改成 **headless**，名字直接解析到 Pod IP，所有埠都通（NetworkPolicy 仍然限制誰連得進來）。初始化 Job 另外需要 master、master gRPC、filer、filer gRPC 的埠，`allow-object-storage-admin` 只開放給 `object-storage-init`
5. **管理員帳號**：`BootstrapAdminInitializer` 原本綁在 `dev` profile；現在改由 `app.bootstrap-admin.enabled` 控制，叢集裡用 `BOOTSTRAP_ADMIN_ENABLED=true`，不再使用 `dev` profile（SEC-12）
6. **Windows 的 `mvnw` 是 CRLF**：見上面的 `.gitattributes`
7. **kind 節點不共用本機的映像庫**：`postgres`、`redis`、`seaweedfs` 要從 Docker Hub 拉（第一次部署要等幾分鐘），自己建的映像檔要 `kind load`

## 還沒做的

- **Ingress、TLS、HSTS**：目前用 `port-forward` 連進去；前端的 Service 是 ClusterIP。加 Ingress 時，`allow-frontends` 可以改成只允許 Ingress 所在的 namespace，Gateway 的 `GATEWAY_TRUSTED_PROXIES` 要改成 2，Ingress 要能連到物件儲存讓瀏覽器讀圖與直傳
- **圖片網址是 `localhost:8333`**：只在本機有效。正式環境要換成對外的網域（`STORAGE_UPLOAD_ENDPOINT`、`STORAGE_PUBLIC_BASE_URL`，以及兩個前端 ConfigMap 的 `STORAGE_PUBLIC_ORIGIN`，CSP 會用到）
- **資料庫與 Redis 的高可用、備份**：單一副本、本機磁碟區
- **映像檔倉庫、CI、映像檔掃描、簽章**
- **`SEC-01`（`mock-callback`）**：已改成設定開關、預設關閉，但 K8s 上為了示範與 E2E 仍設為開啟；不接真的金流（路線圖階段 F 不實作），維持此狀態，僅適用於隔離的示範環境
- 服務啟動順序：沒有 initContainer 等資料庫，靠重啟等待
- 測試沒有跑在映像檔建置裡（Testcontainers 需要 Docker），應該在 CI 先跑
