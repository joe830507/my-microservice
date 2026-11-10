# vue-management Step 0：後端前置問題

> 屬於 [vue-management 實作計畫](README.md)

- 實測 SeaweedFS 的 CORS：從 `http://localhost:5173` 對 presigned URL 發 PUT，確認 preflight 通過；不通過就在 compose 的初始化加入 CORS 設定
- 決定訂單出貨、完成 API 要不要補；要補就照後端流程（整合測試、單獨 commit）

**CORS 實測結果（2026-11-09）**：不用改設定。

- SeaweedFS 3.80 預設就允許所有來源：OPTIONS 預檢回 200，帶 `Access-Control-Allow-Origin: *`、`Allow-Methods: *`、`Allow-Headers: *`
- 用簽了 `content-type`、`content-length` 的 presigned URL、帶 `Origin: http://localhost:5173` 實際 PUT：回 200，回應也帶 `Access-Control-Allow-Origin: *`；換成別的 Content-Type 仍然回 403，簽章檢查沒有因為 CORS 放寬
- 前端上傳**不能帶 credentials**（axios 的 `withCredentials` 保持 false）：回應是 `*` 加上 `Allow-Credentials: true`，瀏覽器在帶 credentials 時會拒絕 `*`
- 正式環境的物件儲存（S3 或 SeaweedFS）如果改成只允許後台網域，要再設定 bucket CORS
- 順便遇到的本機問題：Windows 的 TCP 動態 port 範圍從 1024 開始時，Hyper-V / WSL 會保留 8045–8344 一帶的 port，造成 8080–8085、8333 綁定失敗。修正方式：系統管理員執行 `netsh int ipv4 set dynamic tcp start=49152 num=16384`，再重啟 `winnat`
- compose 的 `object-storage-init` 可能在 filer 準備好之前就執行，出現 `fail to dial` 錯誤但仍然以 0 結束；重新執行 `docker compose up object-storage-init` 即可

**訂單出貨、完成 API（2026-11-09）**：

| API                                   | 轉換                                           | 重複呼叫                            | 其他狀態                      |
| ------------------------------------- | ---------------------------------------------- | ----------------------------------- | ----------------------------- |
| `POST /api/v1/orders/{id}/ship`     | `PAID → SHIPPED`，記錄 `shippedAt`        | 已是 `SHIPPED` 回 200，不再改動   | 409 `ORDER_NOT_SHIPPABLE`   |
| `POST /api/v1/orders/{id}/complete` | `SHIPPED → COMPLETED`，記錄 `completedAt` | 已是 `COMPLETED` 回 200，不再改動 | 409 `ORDER_NOT_COMPLETABLE` |

- 限 ADMIN（`@RequireRoles`）：order-service 第一次用到，啟動類別加上 `@Import(RequireRolesGuard.class)`
- 用條件式 UPDATE，同時多個請求只有一個真的改動；不需要呼叫 inventory（付款時已確認扣除）
- V2、V3 migration 新增 `shipped_at`、`completed_at`；`OrderResDto` 多了這兩個欄位
- 測試：`OrderControllerShipIntegrationTests`（9）、`OrderControllerCompleteIntegrationTests`（10），order-service 全部 114 個通過
