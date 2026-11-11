# K8s 部署

部署步驟、設計決定、驗證結果與已知限制：[docs/implementation-plans/backend-deployment-implementation-plan.md](../../docs/implementation-plans/backend-deployment-implementation-plan.md)

快速開始（本機 kind）：

```bash
infra/k8s/scripts/kind-up.sh                                           # 建叢集 + Calico
# 建映像檔、kind load（見上面的文件）
KUBE_CONTEXT=kind-my-microservice infra/k8s/scripts/create-secrets.sh  # 產生 Secret（隨機值，不進 repo）
kubectl --context kind-my-microservice apply -k infra/k8s
```

`data/`、`services/`、`network-policies.yaml` 是產生出來的 YAML，之後直接手動維護。
