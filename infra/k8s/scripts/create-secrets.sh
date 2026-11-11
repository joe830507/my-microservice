#!/usr/bin/env bash
# 產生叢集需要的所有 Secret：資料庫與 Redis 密碼、JWT 金鑰、物件儲存金鑰、第一個管理員的帳密。
# 值都是隨機產生的，只存在叢集裡，不會寫進 repo 或印出來（SEC-03：不使用 repo 裡的開發用預設值）。
#
# 用法：infra/k8s/scripts/create-secrets.sh [namespace]
#   KUBE_CONTEXT=kind-my-microservice infra/k8s/scripts/create-secrets.sh
#
# 重複執行是安全的：已經存在的 Secret 不會重新產生。這點很重要——資料庫密碼在第一次啟動、
# 初始化資料目錄時就決定了，之後重新產生會讓服務連不上資料庫。要換密碼請自己處理（改資料庫密碼 + 改 Secret）。
set -euo pipefail

NS="${1:-my-microservice}"
kc() { kubectl ${KUBE_CONTEXT:+--context "$KUBE_CONTEXT"} -n "$NS" "$@"; }

# kubectl 是 Windows 程式時看不懂 Git Bash 的 /tmp 路徑，所以值一律用 --from-literal 傳，不用 --from-file
rand() { openssl rand -base64 36 | tr -d '/+=\n' | cut -c1-24; }

kubectl ${KUBE_CONTEXT:+--context "$KUBE_CONTEXT"} get namespace "$NS" >/dev/null 2>&1 \
  || kubectl ${KUBE_CONTEXT:+--context "$KUBE_CONTEXT"} create namespace "$NS" >/dev/null

# 建立 Secret（已存在就跳過）；其餘參數是 --from-literal=key=value
ensure() {
  local name="$1"; shift
  if kc get secret "$name" >/dev/null 2>&1; then
    echo "  跳過 $name（已存在）"
  else
    kc create secret generic "$name" "$@" >/dev/null
    echo "  建立 $name"
  fi
}

echo "Secret（namespace: $NS）"

# 每個服務各自的資料庫帳密
for svc in user product inventory order payment; do
  if kc get secret "$svc-db" >/dev/null 2>&1; then
    echo "  跳過 $svc-db（已存在）"
  else
    kc create secret generic "$svc-db" --from-literal=username="app_$svc" --from-literal=password="$(rand)" >/dev/null
    echo "  建立 $svc-db"
  fi
done

# Redis 密碼
for r in user product gateway; do
  if kc get secret "$r-redis" >/dev/null 2>&1; then
    echo "  跳過 $r-redis（已存在）"
  else
    kc create secret generic "$r-redis" --from-literal=password="$(rand)" >/dev/null
    echo "  建立 $r-redis"
  fi
done

# JWT 簽章金鑰（RSA 2048）：每個環境各自產生，不和本機開發用的金鑰共用
if kc get secret jwt-keys >/dev/null 2>&1; then
  echo "  跳過 jwt-keys（已存在）"
else
  priv="$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null)"
  pub="$(printf '%s\n' "$priv" | openssl pkey -pubout 2>/dev/null)"
  kc create secret generic jwt-keys \
    --from-literal=jwt-private.pem="$priv" --from-literal=jwt-public.pem="$pub" >/dev/null
  echo "  建立 jwt-keys"
fi

# 物件儲存：product-service 的存取金鑰，以及 SeaweedFS 的身分設定（s3.json）
# 匿名只能讀 products/ 底下（商品圖片），tmp/ 是私有的
if kc get secret object-storage >/dev/null 2>&1; then
  echo "  跳過 object-storage（已存在）"
else
  access="$(rand | cut -c1-16)"; secret="$(rand)"
  s3json="$(cat <<JSON
{
  "identities": [
    {
      "name": "product-service",
      "credentials": [{ "accessKey": "$access", "secretKey": "$secret" }],
      "actions": ["Admin", "Read", "Write", "List", "Tagging"]
    },
    {
      "name": "anonymous",
      "actions": ["Read:product-images/products/*"]
    }
  ]
}
JSON
)"
  kc create secret generic object-storage \
    --from-literal=access-key="$access" --from-literal=secret-key="$secret" --from-literal=s3.json="$s3json" >/dev/null
  echo "  建立 object-storage"
fi

# 第一個管理員（user-service 啟動時建立）；密碼隨機，要用時從 Secret 讀出來，不會印在這裡
if kc get secret bootstrap-admin >/dev/null 2>&1; then
  echo "  跳過 bootstrap-admin（已存在）"
else
  kc create secret generic bootstrap-admin \
    --from-literal=email="${ADMIN_EMAIL:-admin@example.com}" --from-literal=password="$(rand)" >/dev/null
  echo "  建立 bootstrap-admin"
fi

cat <<MSG

完成。管理員帳密不會印出來，需要時用：
  kubectl -n $NS get secret bootstrap-admin -o jsonpath='{.data.email}' | base64 -d; echo
  kubectl -n $NS get secret bootstrap-admin -o jsonpath='{.data.password}' | base64 -d; echo
MSG
