# 程式碼最佳化驗證紀錄（2026-11-13）

後端分段執行完整回歸；修正測試暴露的登入 cookie、排程數量及 payment 無去重表問題後，受影響模組與共用依賴均重新通過。以下為各模組最後一份 Surefire XML 統計，全部無失敗、錯誤或略過。

| 模組 | 測試 | 失敗 | 錯誤 | 略過 |
|---|---:|---:|---:|---:|
| common | 56 | 0 | 0 | 0 |
| spring-product-service | 194 | 0 | 0 | 0 |
| spring-user-service | 164 | 0 | 0 | 0 |
| spring-inventory-service | 95 | 0 | 0 | 0 |
| spring-order-service | 137 | 0 | 0 | 0 |
| spring-payment-service | 50 | 0 | 0 | 0 |
| spring-notification-service | 12 | 0 | 0 | 0 |
| spring-api-gateway | 111 | 0 | 0 | 0 |

合計 819 個後端測試。觀測性模組沒有獨立 Surefire 測試，由 reactor 建置與服務啟動驗證。

後端命令（backend 目錄）：

```powershell
.\mvnw.cmd -B -q test
.\mvnw.cmd -B -q -pl spring-order-service,spring-payment-service,spring-notification-service,spring-api-gateway -am test
.\mvnw.cmd -B -q -pl spring-payment-service,spring-notification-service,spring-api-gateway -am test
```

前端（各應用目錄）：`npm run test:unit -- --run`：vue-customer 336／30 個測試檔，vue-management 267／26 個測試檔，全通過。兩邊 `npm run type-check`、`npm run build` 均通過。

`npm run test:browser`：Chrome 前台三項（相關性請求參數、雙分頁購物車、圖片先於商品回應發出）及後台一項（實際 canvas 縮至 1600×800 WebP）均通過。這四項使用瀏覽器搭配攔截 API，驗證 UI／HTTP 邊界，不代表整套部署驗收；既有真實後端 e2e/browse.spec.ts 已補搜尋參數斷言，但本次未啟動全套部署執行該套 e2e。測試結束後已停止本次 Vite 5184／5185 子程序。

新增關鍵回歸：bcrypt encode／matches 無實際交易；登入失敗零 refresh token，成功 cookie token 雜湊與 DB 一致；去重清理保留近期資料；outbox 部分成功保留 ACK 且同 aggregate 不越序；分類快取包含停用節點且修改後失效；SMTP 在已提交 SENDING 租約的交易外執行，過期租約回收、有效租約保留；PostgreSQL 暫停五秒後 Kafka 恢復處理且 DLT metric／offset 均為零；DLT 重放取消時零發送、確認後二進位 key／payload／headers 完全保留。

索引：PostgreSQL 18.6，五萬商品／五萬事件，單次 EXPLAIN ANALYZE（非正式環境壓測）。全站商品 6.798→0.032ms；單分類 0.569→0.044ms；outbox 3.001→0.462ms。[SQL](codebase-optimization-explain.sql)／[完整計畫](codebase-optimization-explain.txt)。

Docker：`docker build -f backend/Dockerfile --build-arg SERVICE=notification-service -t my-microservice/optimization-review-notification:local backend` 成功。使用隔離 PostgreSQL 啟動映像，JarLauncher 正常載入、五個 migration 成功、約五秒顯示 Started。[啟動日誌](codebase-optimization-image-startup.log)。兩個暫時容器已移除，未推送或部署。CDS／AOT training 仍暫緩，原因見原審查第 18 點。


## Claude 第一輪 R1～R7 修正驗證（2026-11-13）

本輪修改後端、運維工具及文件，未重跑上一輪完整 819 個後端／603 個前端測試，也未執行整套部署 e2e。R1、R2 各有 common 測試通過紀錄；最新乾淨建置針對七個相關模組執行以下 93 個重點測試。

| 模組 | 測試 | 失敗 | 錯誤 | 略過 |
|---|---:|---:|---:|---:|
| common | 21 | 0 | 0 | 0 |
| spring-product-service | 15 | 0 | 0 | 0 |
| spring-user-service | 22 | 0 | 0 | 0 |
| spring-inventory-service | 5 | 0 | 0 | 0 |
| spring-order-service | 8 | 0 | 0 | 0 |
| spring-payment-service | 15 | 0 | 0 | 0 |
| spring-notification-service | 7 | 0 | 0 | 0 |

命令（backend，PowerShell）：

```powershell
.\mvnw.cmd -B -q -pl common test
.\mvnw.cmd -B -q -pl common clean test
.\mvnw.cmd -B -q -pl common,spring-product-service,spring-user-service,spring-inventory-service,spring-order-service,spring-payment-service,spring-notification-service -am clean test '-Dtest=EventsAutoConfigurationTest,OutboxRelayIntegrationTests,CommonAutoConfigurationTest,CategoryTreeCacheIntegrationTests,ProductListSubcategoryIntegrationTests,AuthControllerLoginIntegrationTests,RefreshTokenCleanupJobIntegrationTests,ReservationExpiryJobIntegrationTests,SchedulerLockIntegrationTests,OrderJobsIntegrationTests,PaymentControllerMockCallbackIntegrationTests,NotificationEmailIntegrationTests' '-Dsurefire.failIfNoSpecifiedTests=false'
```

補強檢查：metadata 已失敗 future 僅 send 一次；同步 send 耗盡窗口後停止剩餘事件且保留 ACK；去重與 outbox 每批獨立提交（剩餘 4→1→0），保留近期／未發布資料；排程池 core size 為 4，既有 ShedLock 與 SMTP 多副本／租約測試通過；七種 JDBC／Spring／5xx 錯誤的直接及 listener 包裝形式採指數退避，未知錯誤維持有限重試；重放更新 timestamp 並保留原時間 header／key／payload；分類 LINKS_KEY TTL 範圍。

R3 沿用五萬商品 fixture、PostgreSQL 18.6，追加十個常數分類 IN：降冪約 0.199ms（status 索引掃描後篩選），升冪約 0.146ms（增量排序）；全站／單分類升冪約 0.027／0.110ms，亦需增量排序。保留原索引，這些單次量測不代表正式環境 SLA。[SQL](codebase-optimization-explain.sql)／[完整計畫](codebase-optimization-explain.txt)。暫時資料庫容器已移除。

最後另執行 `./mvnw.cmd -B -q -pl common test`，common 完整 67 個測試全數通過（零失敗、錯誤、略過），包含上表 common 的 21 個重點案例，兩組數字不可直接相加。
