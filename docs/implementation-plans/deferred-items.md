# 暫緩項目集中清單

核對日期：2026-11-13。掃描 repo 全部 Markdown 的「暫緩／延後／之後再處理／上線前／未檢查」並對照後續完成紀錄與現行來源；以下是追蹤入口，不在本次實作功能、不估承諾工期，也不把原文搬走。影響範圍是靜態估計，正式排程時再確認。

**暫緩**是待前提滿足後補或重評；**條件式候選**須先證實需求，並非承諾一定做。[路線圖「暫不排入」](middleware/middleware-roadmap.md#暫不排入記錄理由)是目前決定不做，不混為待辦：帳號跨裝置購物車、秒殺 Redis Lua、Caffeine、多副本 DB 擴展、service mesh／mTLS、Debezium 以較新路線圖為準。真實金流階段 F 也明確不實作；自動退款僅因最佳化 #20 仍明列暫緩而保留條件式追蹤，不因此重新承諾 F。

| 項目 | 來源文件／章節 | 暫緩原因 | 前提或觸發條件 | 預估影響範圍 |
|---|---|---|---|---|
| E7 真實信箱驗證 | [通知 E7](middleware/notification-implementation-plan.md#e7-真實信箱驗證暫緩需使用者配合) | 需真實 SMTP 與收件者配合，不是 E 階段結案條件 | 執行時再向使用者確認收件信箱；不把實際信箱寫入 repo | notification、SMTP、真實客戶端、中文呈現與 Message-ID |
| 註冊確認信／避免 Email 列舉（SEC-04） | [通知範圍](middleware/notification-implementation-plan.md#目標與範圍)；[安全限制](../security/known-issues.md#sec-04-註冊沒有頻率限制) | 驗證碼、未驗證狀態、重寄／到期及前端流程是獨立功能 | 寄信管道穩定後排獨立需求；保留現有註冊限流 | user schema／API、notification、兩個前端 |
| 通知上線前的歷史收件人／訂單投影 | [歷史資料限制](middleware/notification-implementation-plan.md#已知限制預期會寫進實作紀錄) | Kafka 保留期外資料無法僅靠 earliest 補齊 | 正式啟用前定匯出／匯入與是否補寄；先驗冪等與個資權限 | user／order 內部匯出、notification user_contact／order_ref |
| 真實 SMTP 認證／TLS 與 Mailpit 存取邊界 | [SEC-15](../security/known-issues.md#sec-15-通知服務的測試-smtp-與-email-個資保護)；[通知範圍](middleware/notification-implementation-plan.md#目標與範圍) | 目前隔離示範用 Mailpit，不提供真實 SMTP 安全設定 | 對外環境前選 SMTP 供應者、Secret、TLS、寄件網域與出站政策 | notification 設定、部署、E7 |
| 通知 Email 個資保留／清理／稽核 | [SEC-15](../security/known-issues.md#sec-15-通知服務的測試-smtp-與-email-個資保護) | 目前沒有保留期限或自動清理規則 | 上線前定保存需求、清除流程與存取稽核 | notification DB、備份、log／測試證據 |
| Kafka TLS／用戶端認證與 topic ACL | [SEC-13](../security/known-issues.md#sec-13-kafka-尚未啟用用戶端認證) | 本機／kind 暫以 NetworkPolicy 隔離，沒有 client 身分與 ACL | 任何對外或多租戶環境前設憑證與最小權限 | broker、所有 producer／consumer、Secret、工具與重放流程 |
| Elasticsearch TLS／認證／最小權限 | [SEC-14](../security/known-issues.md#sec-14-elasticsearch-尚未啟用認證與-tls) | 隔離環境目前明文 HTTP，Pod 標籤不是認證 | 對外或多租戶前補 TLS、帳號與 Secret | ES、product 查詢／索引／重建、健康檢查 |
| TLS 終結處 HSTS | [SEC-06](../security/known-issues.md#sec-06-前端缺少瀏覽器端防護) | 目前文件驗證範圍為本機 HTTP／nginx，HSTS 放 TLS 終結處 | 有正式 HTTPS Ingress 時設定並用瀏覽器驗證 | Ingress、兩個前端部署與安全標頭 |
| 後端獨立縮圖／多尺寸 | [最佳化 #7](codebase-optimization-review.md#7中商品圖片沒有縮圖列表直接載入原圖)；[圖片後續](backend/product-image-upload-implementation-plan.md#之後再處理) | 已採前端 1600px／WebP，避免本輪擴 API／儲存／刪除契約 | 列表流量／圖片大小有實測瓶頸時再設計，含既有圖片策略 | product、物件儲存、ProductListItem、顧客卡片與刪除補償 |
| 首頁統計 API 與趨勢圖 | [最佳化 #13](codebase-optimization-review.md#13低後台首頁一次發-9-個請求)；[管理首頁紀錄](vue-management/README.md#vue-management-step-4layout) | 短期三十秒計數快取已消除重複請求 | 資料量／監控證實 count 瓶頸，或有每日訂單／營收需求 | product／order／user 統計查詢、ADMIN API、管理首頁 |
| CDS／AOT cache | [最佳化 #18](codebase-optimization-review.md#18低後端映像檔沒有分層啟動沒用-cdsaot-cache) | 已有映像分層；訓練啟動依賴環境，需要可重現基準 | 先建立依賴與 training 流程，量測 CPU／啟動收益 | 所有 Java Dockerfile、CI、啟動／訓練環境 |
| 自動退款／自動 DLT listener（條件式，未承諾） | [最佳化 #20](codebase-optimization-review.md#20中dlt-只有告警沒有處理手冊與重放工具)；[人工流程](../architecture/event-contracts.md#dlt-處理與重放) | 現況人工追蹤；真實金流階段 F 明確不實作 | 只有未來重新決定真實金流或自動補償需求時才重評；未決定前不自動重放 | payment／order 狀態機、金流憑證、DLT 稽核與人工核對 |
| 圖片 CDN | [圖片後續](backend/product-image-upload-implementation-plan.md#之後再處理) | 物件儲存公開 URL 已足夠，未部署 CDN | 跨區延遲／頻寬需求出現時規劃快取與失效 | public-base-url、CDN、儲存權限與 CSP |
| 正式圖片物件對帳清理 | [圖片設計](backend/product-image-upload-implementation-plan.md#商品圖片上傳設計實作與驗收)；[原後續章節](backend/product-image-upload-implementation-plan.md#image-design-之後再處理) | Copy／DB 非原子，漏刪物件目前只記 log；tmp TTL 不清正式孤兒 | 正式儲存前定資料列／objectKey 對帳、預覽與安全刪除流程 | product_image、products/ 物件、排程與補償 |
| 快取擊穿 per-key 協調 | [快取現況](../design/product-service-middleware-evaluation.md#現況) | 目前規模可接受，不使用會破壞動態 TTL 的 sync=true | 熱門 key 同時 miss 確實壓垮 DB 時量測再做 | ProductDetailCache、Redis 鎖與 TTL／失敗恢復 |
| Bloom filter／ETag（候選，未承諾） | [評估現況](../design/product-service-middleware-evaluation.md#現況) | 原評估標延後且非必要，負快取與現有 HTTP 已足夠 | 先證實穿透或重複傳輸問題，才選具體方案 | Redis／商品讀 API、瀏覽器快取與失效 |
| 個人資料修改／改密碼／忘記密碼 | [前台後續](vue-customer/README.md#之後再處理) | 後端尚無 API；忘記密碼依賴郵件與安全流程 | 定驗證、憑證撤銷與個資規則，郵件管道穩定後排程 | user API、兩個 token 流程、顧客帳號頁、notification |
| 共用電腦登出後購物車政策 | [前台後續](vue-customer/README.md#之後再處理) | 目前保留非帳號綁定 localStorage cart，尚未定清除政策 | 確認共用電腦／多帳號需求後決定保留或清空 | 顧客 auth／cart、切帳號與回歸測試 |
| 商品 SEO／SSR 或預產生（條件式） | [前台後續](vue-customer/README.md#之後再處理) | 目前 SPA 示範，不以搜尋引擎收錄為目標 | 確定收錄需求後評估 Nuxt／預產生與部署成本 | 顧客路由、建置、API、SEO metadata |
| 前後台共用 workspace 套件（條件式） | [前台後續](vue-customer/README.md#之後再處理) | 兩個應用獨立，重複尚未促成套件化決策 | 重複維護成本可量測時設共用契約，避免誤共享 auth 狀態 | 兩個前端 HTTP／型別／工具與建置 |
| Gateway Boot／Cloud 升級 | [Gateway 後續](backend/api-gateway-implementation-plan.md#之後再處理) | 目前 POM 因相容性維持 Boot 4.0.9／Cloud 2025.1.4 | 實作時重新確認正式相容版本，完整測驗再升級 | Gateway POM、OTel 相依、路由／JWT／限流測試 |
| Gateway 測試設定共用與 Docker 分離 | [Gateway 後續](backend/api-gateway-implementation-plan.md#之後再處理) | 原計畫記載重複 WireMock／JWKS／Redis 設定及全部依賴 TestRedis | 維護測試成本或無 Docker 執行需求明確時重整，不犧牲 Redis 整合覆蓋 | Gateway 測試 base／annotation、CI |
| SKU 含 % 的 Gateway 契約 | [Gateway 後續](backend/api-gateway-implementation-plan.md#之後再處理) | firewall 可能拒絕編碼 %25，目前沒有完整特殊 SKU 契約 | 業務允許 % 前確認跨層編碼與 firewall 安全性 | Gateway、inventory 路徑、前後台 SKU 編碼 |
| ProductCreated 自動建立庫存 | [庫存後續](backend/inventory-service-implementation-plan.md#之後再處理) | 目前後台依 SKU 建立，inventory 未消費 product.events | 要自動建檔時先定初始量與 SKU 變更生命週期 | product event、inventory consumer／schema、管理端 |
| 商品批次讀取移至 internal API | [訂單後續](backend/order-service-implementation-plan.md#之後再處理) | 目前公開 batch 受可見性規則保護，order 仍使用此路徑 | 服務契約需分離時一起調路由、網路政策與呼叫端 | product controller、order ProductClient、Gateway／NetworkPolicy |
| PENDING 付款逾時收斂 | [付款後續](backend/payment-service-implementation-plan.md#之後再處理) | 訂單取消後可能保留 PENDING；目前付款結果只由回呼決定 | 先定回呼／取消競爭及終態契約再新增訂閱或排程 | payment 狀態／事件、order 取消、顧客付款頁 |
| 並行註冊衝突錯誤碼一致性（待決） | [使用者計畫](backend/user-service-implementation-plan.md) | 目前唯一鍵競爭回 DATA_CONFLICT，先查存在回 EMAIL_ALREADY_EXISTS | 先決定接受現狀或統一契約，不僅替換字串 | user exception mapping、API／並行測試、前端訊息 |
| 剩餘安全驗證工作 | [未檢查清單](../security/known-issues.md#還沒有檢查的) | 現有證據不涵蓋全部攻擊面；不是已證實漏洞 | 對外環境前逐項安排：SQLi／XSS 模糊、圖片內容偽造、Maven 漏洞、JWT 輪替演練、後台安全、密碼與 token 審查 | 後端／兩個前端／依賴／部署；若後續已驗證須回填來源 |

## 掃描後排除與現況判讀

- 已完成的舊待辦不再列入：建單冪等（SEC-10）、Gateway JWKS／來源限流、K8s 服務名稱與 NetworkPolicy、Gateway ProblemDetail、訂單出貨／完成、HTTP 韌性與事件通知、圖片瀏覽器 upload endpoint、refresh cookie／前端安全標頭、登入雙層計數、SMTP 交易外寄送與 SENDING 租約。原文件保留歷史內容；目前實作見[類別導讀](../class-relationships/README.md)及[最佳化驗證](evidence/codebase-optimization-validation.md)。
- 舊 inventory 計畫提 PaymentSucceeded 直達庫存，現況是 OrderPaid；不把舊事件草案列成尚未完成的任務。
- 原評估的 Caffeine 延後與前台帳號購物車等，已由較新「暫不排入」決策覆蓋。Bloom／ETag 未見相同排除決策，僅保留候選。
- SEC-02 的 egress、SEC-03 的 Compose 綁定、SEC-05 的 email+IP、SEC-06 的 cookie 及 CSP 已有完成註記，不把歷史「還沒處理」誤當現況；token 黑名單與 mTLS 屬可選方向，現行短 JWT／NetworkPolicy 取捨不自動轉待辦。
- 安全文件「還沒有檢查的」是待驗證而非已知漏洞；過往測試數字、kind 實測及未檢查宣告均維持原文。本次沒有重新安全測試或清理探測資料。

## 維護規則

完成、取消或改變前提時先更新原始來源，再更新本表；原處加回本清單的連結。執行 E7 時才向使用者確認收件信箱，本表及證據不寫任何實際信箱。新範圍涉及真實金流、對外部署或刪除資料時，另依具體需求評估與授權。
