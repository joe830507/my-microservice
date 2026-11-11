#!/usr/bin/env bash
# 建立本機 kind 叢集並安裝 Calico（讓 NetworkPolicy 真的生效）。
# 用法：infra/k8s/scripts/kind-up.sh
set -euo pipefail

NAME="${KIND_CLUSTER_NAME:-my-microservice}"
CALICO_VERSION="${CALICO_VERSION:-v3.33.0}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG="$HERE/../kind-config.yaml"
# kind、kubectl 是 Windows 程式時，Git Bash 的路徑要轉成 Windows 格式
command -v cygpath >/dev/null 2>&1 && CONFIG="$(cygpath -w "$CONFIG")"

kind create cluster --name "$NAME" --config "$CONFIG"
CTX="kind-$NAME"

# 關掉內建 CNI 之後叢集沒有網路，節點在安裝 CNI 之前都是 NotReady
#
# sed 移除 calico-node 對 /sys/kernel/security 的 hostPath 掛載（volume 與 volumeMount 各 3 行）：它只在 eBPF 模式
# 偵測核心 lockdown 用，我們用 iptables 模式用不到；而 Windows / WSL2 的核心沒有 securityfs，容器會因為
# 「mkdir /sys/kernel/security: read-only file system」建立失敗，calico-node 起不來
curl -fsSL "https://raw.githubusercontent.com/projectcalico/calico/${CALICO_VERSION}/manifests/calico.yaml"   | sed '/name: sys-kernel-security/,+2d'   | kubectl --context "$CTX" apply -f -
kubectl --context "$CTX" -n kube-system rollout status daemonset/calico-node --timeout=300s
kubectl --context "$CTX" wait --for=condition=Ready node --all --timeout=300s
echo "完成：context $CTX（Calico $CALICO_VERSION）"
