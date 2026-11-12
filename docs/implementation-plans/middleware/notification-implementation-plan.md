# 通知（notification-service）實作計畫（階段 E）

> 建立日期：2026-11-12
> 分支：實作時開 `feature/notification-phase-e`（從階段 D 合併後的 `main` 切出；階段 D 尚未合併時，從 `feature/search-phase-d` 切出並做 stacked PR）
> 對應：[middleware-roadmap.md](middleware-roadmap.md)「階段 E」、[event-contracts.md](../../architecture/event-contracts.md)
> 前置：階段 C 完成（`user.events`、`order.events`、`payment.events` 已經在發送）；階段 D 完成（本階段沿用其文件與驗收規範）
> 狀態：E1–E2 完成；E3–E7 尚未開始

這份文件是給實作者（Codex 或任何人）照著做的**唯一依據**。分成兩半：前半說明「做什麼、為什麼這樣設計」，後半（[實作規範](#實作規範)）說明「怎麼做、哪些事不能做」。**開始實作前兩半都要讀完。** 遇到文件沒寫到、或發現文件有錯的地方，不要自己默默決定，先照[計畫變更](#計畫變更紀錄)的規則處理。

---

## 目標與範圍

**目標**：下單、付款成功、付款失敗、出貨、取消時，自動寄信給顧客。寄信不能拖慢也不能影響下單、付款等主流程；SMTP 或通知服務停機時，信件延後寄出而不是遺失；同一個事件不會寄出兩封信。

**做**：

- 新服務 `spring-notification-service`（自己的資料庫、沒有對外 HTTP API），本機與 kind 用 Mailpit 收信
- 消費 `user.events` 建立本地的收件人資料，**不在寄信時同步呼叫 user-service**
- order-service 補發 `OrderCreated`、`OrderShipped` 事件；inventory-service 先能容忍這兩種新事件
- 以「信件發件匣」模式寄信：消費事件只寫資料庫，另一個派送器負責寄出、失敗重試與退避
- 指標、儀表板、告警；文件與驗收證據

**不做**（刻意排除，不要順手做）：

| 項目 | 理由 |
| ---- | ---- |
| 忘記密碼、改密碼、Email 變更通知 | user-service 目前沒有這些 API；有了之後再接 |
| HTML 信件、範本引擎（Thymeleaf 等） | 先用純文字，避免 HTML 注入（顧客名稱是使用者輸入）和新依賴；有需求再另開 |
| 簡訊、推播、Webhook 等其他管道 | 超出範圍 |
| 通知偏好設定（退訂、頻率）、多語系 | 需要先有前端與資料模型；之後另開 |
| 後台查看寄信紀錄的 API／畫面 | 先靠 Mailpit 與資料庫；沒有對外 API 也就沒有新的授權面 |
| 訂單完成（`COMPLETED`）通知 | roadmap 只列下單、付款成功、出貨、取消；`OrderCompleted` 事件不新增 |

**暫緩**（不是不做：先讓系統有雛型，之後**一定要補**；本階段不實作，也不要為它們預留半成品程式或欄位）：

| 項目 | 暫緩的理由與之後要做的事 |
| ---- | ---- |
| 註冊改成寄信確認（roadmap 原 E5，解決 SEC-04 的 Email 列舉） | 需要驗證碼、「未驗證」帳號狀態、過期與重寄、兩個前端的流程調整，是獨立的功能。階段 E 完成、寄信管道穩定後再做，會用到本階段的發件匣 |
| 補寄「服務上線前」已存在使用者與訂單的資料 | 要補需要 user-service、order-service 新增內部匯出 API。雛型階段先接受[已知限制](#已知限制預期會寫進實作紀錄) |
| 真實 SMTP 服務與 SMTP 的認證／TLS | 本階段本機與 kind 用 Mailpit，靠 NetworkPolicy 隔離，記為 SEC-15。真實信箱收信驗證見 [E7](#e7-真實信箱驗證暫緩需使用者配合) |

---

## 現況（階段 D 完成後）

- **沒有 notification-service**。`user.events` 的 `UserRegistered`（payload：`userId`、`email`、`displayName`）已經在發送，註明「預留給通知服務」
- `order.events` 只有 `OrderPaid`、`OrderCancelled`，payload 只有 `orderId`（`OrderCancelled` 選填 `reason`）。**沒有「下單」與「出貨」事件，payload 也沒有 `userId`**
- `payment.events` 有 `PaymentSucceeded`、`PaymentFailed`，payload 為 `paymentId`、`orderId`、`status`、`amount`，**沒有 `userId`**
- order-service 的 `shipOrder`、`completeOrder` **不是** `@Transactional`，用 repository 的條件式 UPDATE（`markShippedIfPaid`）；因此目前沒有地方可以在同一個交易寫 outbox
- **inventory-service 的 `OrderEventListener` 遇到不認識的 `order.events` 型別會丟 `IllegalArgumentException`**，事件進 DLT 並觸發 DLT 告警（critical）。**直接在 `order.events` 加新型別會讓 inventory 每筆新事件都進 DLT**
- 共用元件在 `common`：outbox 寫入（`OutboxWriter`／`outbox.append`）、`OutboxRelay`、`ProcessedEventHandler`（`processed_event` 去重）、`ResilientEventConsumer`、`EventDeadLetterRecoverer`。新服務要沿用，不要自己重做
- 各服務都有自己的 PostgreSQL（`*-db` StatefulSet）、Flyway migration、Resilience4j、Micrometer／OTel、ShedLock 表（見 user-service 的 `V6`）

---

## 已定的決定

| 項目 | 決定 | 理由 |
| ---- | ---- | ---- |
| 寄信模式 | **發件匣（outbox for email）**：消費事件的交易只寫 `email_message`；獨立派送器負責 SMTP 寄出並更新狀態 | SMTP 是外部副作用，無法和資料庫放在同一個交易；先寫資料庫才能做到「停機不遺失」「重試」「不重複建立」 |
| 送達語意 | **至少一次**。派送器寄出後、更新狀態前當機，會重寄一次 | 要做到恰好一次需要 SMTP 端的冪等，辦不到；用固定的 `Message-ID`（由 `email_message.id` 產生）讓郵件客戶端可辨識重複，並在已知限制寫明 |
| 收件人從哪來 | 消費 `user.events` 建立本地 `user_contact`（`userId`、`email`、`displayName`） | 不在寄信時同步呼叫 user-service，user-service 停機也能寄；roadmap 已定 |
| 訂單屬於誰 | 消費 `OrderCreated` 建立本地 `order_ref`（`orderId`、`userId`、`totalAmount`）；其他訂單事件、`PaymentFailed` 只帶 `orderId`，**寄信時**再由 `order_ref` → `user_contact` 解析收件人 | 避免修改 `OrderPaid`、`OrderCancelled`、`payment.events` 的既有 payload；也避免「跨 topic 順序不保證」造成的消費端重試（解析失敗留給派送器退避重試） |
| 付款成功信由誰觸發 | **`OrderPaid`**，不是 `PaymentSucceeded` | 訂單狀態以 order-service 為準。付款成功但訂單已取消時，`PaymentSucceeded` 會被 order 送進 DLT、不會有 `OrderPaid`，這時**不應**寄「付款成功」。因此 notification-service **不消費** `PaymentSucceeded` |
| 付款失敗信 | 消費 `payment.events` 的 `PaymentFailed` | 這是 order 事件看不到的結果 |
| 與 roadmap E3 的差異 | roadmap 寫「消費 `order.events`、`payment.events`」；實際 `payment.events` 只消費 `PaymentFailed` | 同上；roadmap 的 E 章節已於計畫擬定時同步 |
| 信件格式 | 純文字（`text/plain`），主旨固定文字加訂單編號，**不放使用者輸入**（顧客名稱只出現在內文開頭） | 避免 HTML 與標頭注入；主旨可預期 |
| 範本 | 放在 `src/main/resources/templates/mail/*.txt`，用簡單的 `{{name}}` 取代，取代時不執行任何運算 | 不引入範本引擎 |
| 新事件放哪個 topic | 放在既有 `order.events`（`OrderCreated`、`OrderShipped`），**先改 inventory 讓它忽略這兩種型別** | 同一訂單的事件要在同一個 partition 保持順序；新開 topic 會失去 `OrderCreated` → `OrderPaid` 的順序保證 |
| inventory 怎麼容忍 | 明確列出要忽略的型別（`OrderCreated`、`OrderShipped`），**其他未知型別仍丟例外進 DLT** | 保留「未知事件要被發現」的嚴格性；不改成「全部忽略」 |
| 派送器的併發 | 與 `OutboxRelay` 相同：`FOR UPDATE SKIP LOCKED` 領取一小批，多副本不重複寄 | 沿用已驗證的做法；不需要 ShedLock |
| 派送器 SMTP 逾時 | 連線 3 秒、讀寫 5 秒（可由設定覆寫） | 與搜尋服務的逾時風格一致；避免 SMTP 卡住時佔住連線與資料列鎖 |
| 重試排程 | 寄信失敗退避：10 秒、30 秒、1 分、2 分、5 分、10 分、30 分、1 小時（共 8 次），之後標記 `FAILED` | 暫時性故障自動恢復，永久性故障不無限重試；`FAILED` 有告警 |
| 收件人不存在 | 解析不到 `order_ref` 或 `user_contact` 時，按同樣的退避重試**最多 5 次**，仍找不到標記 `SKIPPED`（附原因）並計數，不進 DLT | 使用者／訂單資料可能只是晚到；永遠找不到是已知限制，不該擋住其他信件 |
| 去重 | 消費端用 `processed_event`（與 inventory 相同）；`email_message` 另有 `(event_id, template)` 唯一約束作為第二道防線 | 事件至少投遞一次 |
| 資料庫 | 獨立的 `notification-db`（PostgreSQL，比照其他服務）、Flyway、`processed_event` 表（由 common 提供的機制所需） | 一服務一庫 |
| 對外 API | **沒有**。只開 actuator（叢集內） | 沒有 API 就沒有新的授權面；Gateway 不新增路由 |
| 本機收信 | **Mailpit**（SMTP 1025、UI 8025），compose 與 kind 都放；UI 只綁 `127.0.0.1`／用 port-forward，不對外 | 免註冊、有 HTTP API 可在測試斷言 |
| 寄件地址 | 設定 `notification.mail.from`，預設 `no-reply@my-microservice.local` | 本機不需要真實網域 |
| Spring Mail | 使用 Spring Boot BOM 管理的 `spring-boot-starter-mail`，限制在 notification-service | 計畫唯一新增的主要依賴 |

---

## 架構

```
user-service ──UserRegistered──▶ user.events ─┐
order-service ─OrderCreated/Paid/Shipped/Cancelled─▶ order.events ─┤
payment-service ─PaymentFailed──▶ payment.events ─┤
                                                  ▼
                              notification-service（消費者，同一個交易內）
                                ├ UserRegistered  → upsert user_contact
                                ├ OrderCreated    → upsert order_ref
                                └ 其餘事件        → 寫入 email_message(PENDING) ＋ processed_event
                                                  │
                          派送器（排程，SKIP LOCKED 領一批）
                                ├ 解析 order_ref → user_contact（找不到：退避，最多 5 次後 SKIPPED）
                                ├ 套範本，SMTP 寄出 ──▶ Mailpit（本機／kind）
                                └ 成功 SENT；失敗退避重試，8 次後 FAILED
```

inventory-service 同時消費 `order.events`：**必須在 order-service 開始發 `OrderCreated`／`OrderShipped` 之前，先部署能忽略這兩種型別的版本**（見[部署順序](#部署順序)）。

### 資料表

所有表在 `notification-db`，從 `V1` 開始。欄位名稱以 snake_case，時間用 `TIMESTAMPTZ`。

| 表 | 欄位（重點） | 說明 |
| ---- | ---- | ---- |
| `user_contact` | `user_id`（PK）、`email`、`display_name`、`updated_at` | 只存寄信需要的欄位 |
| `order_ref` | `order_id`（PK）、`user_id`、`total_amount NUMERIC(12,2)`、`created_at` | 由 `OrderCreated` 建立 |
| `email_message` | `id`（UUIDv7，PK）、`event_id`、`template`、`order_id`（可空）、`status`（`PENDING`／`SENT`／`SKIPPED`／`FAILED`）、`attempts`、`next_attempt_at`、`last_error`、`skip_reason`、`created_at`、`sent_at` | `(event_id, template)` 唯一；`last_error` 只存例外類別與簡短訊息，**不存收件地址或信件內容** |
| `processed_event` | 比照其他服務 | 消費端去重 |

索引：`email_message (status, next_attempt_at)` 供派送器領取；`order_ref (user_id)` 不需要。

### 範本與內容

| 範本 | 觸發 | 主旨（固定文字） | 內文要點 |
| ---- | ---- | ---- | ---- |
| `order-created` | `OrderCreated` | 訂單已成立 | 顧客名稱、訂單編號、應付金額、「請於時限內付款」 |
| `order-paid` | `OrderPaid` | 付款成功 | 訂單編號、金額 |
| `order-shipped` | `OrderShipped` | 訂單已出貨 | 訂單編號 |
| `order-cancelled` | `OrderCancelled` | 訂單已取消 | 訂單編號、原因（`timeout` 顯示為「逾時未付款」，無原因為「已取消」） |
| `payment-failed` | `PaymentFailed` | 付款失敗 | 訂單編號、金額、請重新付款 |

- 訂單編號直接用訂單 UUID（目前沒有人類可讀的訂單號）
- 顧客名稱只放內文開頭，且要處理換行與控制字元（避免在純文字內文偽造結構）；名稱缺少時用「顧客」
- 實際文案由實作者擬定，繁體中文，寫進範本檔；文案調整不算計畫變更

### 事件契約變更

在 `order.events` 新增兩種型別（**相容變更**，信封版本不升）：

| 型別 | Aggregate | Payload | 產生時機 |
| ---- | ---- | ---- | ---- |
| `OrderCreated` | orderId | `orderId`、`userId`、`totalAmount`（JSON 數字，與 product 的 `price` 一致） | 建單成功、訂單存檔的**同一個交易**（冪等重送同一個建單請求**不可**重複發事件） |
| `OrderShipped` | orderId | `orderId` | `markShippedIfPaid` 回傳 1 的**同一個交易**；已出貨的冪等請求**不可**重複發事件 |

`OrderPaid`、`OrderCancelled`、`payment.events` **不變**。`event-contracts.md` 同步更新：新型別、消費行為（notification：建立 `order_ref`／寄信；inventory：忽略）、`user.events` 的消費者補上 notification、以及「通知是至少一次、用發件匣」的說明。

`shipOrder` 要改成 `@Transactional`（連同讀取、條件式 UPDATE 與 outbox 寫入），因為事件必須和狀態變更同交易。這是對現有行為的最小修改，**不要順手重構其他方法**，也不要改變回應與狀態碼（已出貨回 200、不可出貨回 409）。

---

## 部署順序

兩個服務共用 `order.events`，順序錯了會讓 DLT 告警響起：

1. 先部署 **inventory-service**（會忽略 `OrderCreated`、`OrderShipped`）
2. 再部署 **order-service**（開始發新事件）
3. 最後部署 **notification-service**（從 earliest 開始消費，會處理保留期內的歷史事件）

實作紀錄要寫明這個順序，D7 風格的 kind 驗收也照這個順序做。

---

## 步驟

每一步**單獨 commit**，測試通過才 commit（見[實作規範](#實作規範)）。

| Step | 內容 | 驗收 |
| ---- | ---- | ---- |
| E1 | **infra 與服務骨架**：Mailpit（compose 與 kind）、`notification-db`、`spring-notification-service`模組、NetworkPolicy、SEC-15 | **完成**：kind 中 notification-service 2/2 Ready、Mailpit 1/1 Ready；Flyway V1/V2 通過；notification 可連 DB、Kafka:9092、Mailpit:1025；product-service 連這兩個埠皆由 NetworkPolicy 阻擋 |
| E2 | **收件人資料**：消費 `user.events` 的 `UserRegistered`，upsert `user_contact`；`processed_event` 去重；格式錯誤進 DLT | **完成**：真實 Kafka/PostgreSQL 整合測試 3 項通過；重複 eventId 保留單筆；同 userId 新事件更新收件人；缺 email 的事件進 `user.events.DLT` |
| E3 | **訂單事件契約**：inventory 先忽略 `OrderCreated`／`OrderShipped`；order-service 發 `OrderCreated`、`OrderShipped`（`shipOrder` 改交易）；更新 `event-contracts.md` | 見 [E3](#e3-訂單事件契約) |
| E4 | **發件匣與寄信**：消費 `OrderCreated`（建 `order_ref`）、`OrderPaid`、`OrderShipped`、`OrderCancelled`、`PaymentFailed`（寫 `email_message`）；範本；派送器與 SMTP | 見 [E4](#e4-發件匣與寄信) |
| E5 | **失敗處理與可觀測**：退避重試、`SKIPPED`／`FAILED`、指標、儀表板、告警；文件同步（`microservice-architecture.md`、`event-contracts.md`、`known-issues.md`、`docs/README.md`、roadmap） | 見 [E5](#e5-失敗處理與可觀測) |
| E6 | **kind 部署驗收與 E2E**：依[部署順序](#部署順序)部署；走完整購物流程在 Mailpit 收到各種信；停 SMTP、停 notification、停 order 的恢復；完整 `mvnw.cmd -o clean test` | 見 [E6](#e6-部署驗收) |
| E7 | **真實信箱驗證（暫緩，需使用者配合）**：雛型穩定後，把信寄到使用者提供的真實信箱，確認中文主旨與內文、寄件地址、`Message-ID` 在真實郵件客戶端的呈現 | 見 [E7](#e7-真實信箱驗證暫緩需使用者配合)；**不是階段 E 結案的條件** |

### E3 訂單事件契約

- inventory：`OrderEventListener` 對 `OrderCreated`、`OrderShipped` 不做任何事（但仍照原流程經 `processedEvents.handle` 記錄消費，保持行為一致），其他未知型別維持丟例外。測試：兩種新型別被忽略且**不進 DLT**；未知型別仍進 DLT
- order-service：`createOrder` 在最後存檔的同一個交易內寫 `OrderCreated`。**冪等重送**（相同 `Idempotency-Key`）回傳既有訂單時不可再寫事件。測試：建單產生剛好一筆 `OrderCreated`；重送同一請求仍是一筆；payload 的 `userId`、`totalAmount` 正確；建單失敗（庫存不足等）不產生事件
- `shipOrder`：`markShippedIfPaid` 回傳 1 時同交易寫 `OrderShipped`；已出貨再按一次（冪等 200）不寫；不可出貨（409）不寫。測試涵蓋三種情況
- 既有測試不可放寬：order-service、inventory-service 現有的整合測試全部維持通過

### E4 發件匣與寄信

- **消費者**：新的 `OrderEventListener`／`PaymentEventListener`／（E2 的）`UserEventListener`，放在 `messaging/`，沿用 `ResilientEventConsumer` 與 `ProcessedEventHandler`。同一交易內完成：業務表寫入（`order_ref` 或 `email_message`）＋ `processed_event`。不認識的型別進 DLT（與 inventory 相同的嚴格性）。`payment.events` 的 `PaymentSucceeded` 是**已知但不需要**的型別（付款成功信由 `OrderPaid` 觸發），要明確忽略、不寫信、不進 DLT
- **派送器**：`@Scheduled`（間隔可設定，預設 5 秒），一次領取一批（預設 10 筆）`status = PENDING AND next_attempt_at <= now()`，`FOR UPDATE SKIP LOCKED`。流程：解析收件人 → 套範本 → 寄出 → 更新狀態，全程在同一個交易（鎖持有到結束，比照 `OutboxRelay` 並在紀錄寫明這個簡化取捨與 SMTP 逾時上限）
- 寄出時設定固定的 `Message-ID`（由 `email_message.id` 產生），重寄時相同
- 日誌**不得**出現收件地址、顧客名稱、信件內容；可以記 `emailMessageId`、`template`、`attempts`、結果
- 測試（真實 PostgreSQL＋Kafka＋Mailpit Testcontainers，用 Mailpit 的 HTTP API 斷言信件）：
  - 每種事件寄出一封信，收件人、主旨、內文要點正確
  - 同一事件送兩次只寄一封（`processed_event`）；同一 `(event_id, template)` 無法重複插入
  - `OrderCreated` 晚於 `UserRegistered` 或相反順序到達都能寄出
  - `OrderPaid` 比 `OrderCreated` 先被派送器看到（模擬）→ 退避後寄出
  - `PaymentSucceeded` 不產生信件
  - 顧客名稱含換行與特殊字元時，內文不被破壞、主旨不含使用者輸入
  - 兩個派送器實例同時執行不重複寄信

### E5 失敗處理與可觀測

- SMTP 無法連線或回暫時性錯誤：`attempts + 1`，依退避表設定 `next_attempt_at`；第 8 次仍失敗標記 `FAILED`。永久性錯誤（收件地址被拒）直接 `FAILED`，不重試
- 解析不到 `order_ref` 或 `user_contact`：同退避表，但最多 5 次，之後 `SKIPPED`，`skip_reason` 為 `unknown_order` 或 `unknown_user`
- 派送器本身不可因單一信件失敗而中斷整批；一封信的例外不影響同批其他信
- 指標與告警見[可觀測性](#可觀測性)
- 測試：SMTP 關閉（停容器或錯誤埠）時信件保持 `PENDING`、`attempts` 增加、`next_attempt_at` 往後；SMTP 恢復後自動寄出且只寄一次；永久失敗進 `FAILED`；找不到收件人 5 次後 `SKIPPED`

### E6 部署驗收

在 kind 實際驗證，證據放 `evidence/phase-e/`（UTF-8 無 BOM）：

| 項目 | 驗收 |
| ---- | ---- |
| NetworkPolicy | 沒有授權的 Pod 連不到 Mailpit:1025 與 notification-db；notification 可連 Kafka、Mailpit、自己的 DB |
| 部署順序 | 依 inventory → order → notification；過程中 DLT 告警**沒有**響 |
| 完整流程 | 註冊 → 下單 → 付款成功 → 出貨 → 取消（另一筆）；Mailpit 收到對應的各種信 |
| 付款失敗 | 付款回呼指定 `FAILED`，收到「付款失敗」信 |
| 停 SMTP | Mailpit 縮到 0 → 下單、付款 → 信件 `PENDING`；恢復後信件寄出且只有一封 |
| 停 notification | 縮到 0 → 下單；恢復後補寄，且不重複 |
| 停 order | 不影響 notification 已收到的信件；恢復後新事件照常 |
| 告警 | `FAILED` 與信件積壓的告警實際 firing 一次（並嘗試記錄 resolved） |
| 完整回歸 | `mvnw.cmd -o clean test` 全模組成功，附指令、耗時、模組數 |
| 人工確認 | 前端不受影響（vue-customer 下單流程、vue-management 出貨）；沒有截圖就如實寫「人工操作、無截圖」 |
| Trace | Tempo 中看到「下單 → outbox → Kafka → notification 消費」的 trace（派送器寄信的 span 有則更好，沒有也要如實寫） |

### E7 真實信箱驗證（暫緩，需使用者配合）

階段 E 的 E1–E6 全部使用 Mailpit，不會寄到真實信箱。雛型穩定後，由使用者配合做一次真實收信驗證：

- **何時做**：E6 完成、使用者通知可以開始時。實作者**不要自行開始**，也不要預先寫任何與真實信箱有關的程式或設定
- **信箱位址由使用者在當時提供**。實作者到了這一步再向使用者詢問，**不要猜測、不要使用其他地址**
- **位址與 SMTP 憑證都不得進入 repo**：不寫進程式、設定檔、範本、測試、commit、實作紀錄、證據檔、日誌或對話以外的任何文件。以環境變數或 K8s Secret 在執行當下提供，用完移除；實作紀錄只寫「已以使用者提供的信箱驗證」，不寫位址
- **需要真實 SMTP**：Mailpit 不會把信投遞出去，要收到信必須接真實的 SMTP 服務。使用哪一個（例如個人信箱的 SMTP、或雲端寄信服務）與是否需要 TLS／認證，由使用者在當時決定；若需要認證或 TLS，這會順便觸發 SEC-15 的處理，屬於[計畫變更](#計畫變更紀錄)，要先記錄並等使用者確認
- **驗證項目**：五種信各寄一封，確認中文主旨與內文不亂碼、寄件地址顯示正確、沒有被標成垃圾信（若有，記錄原因）、重寄時郵件客戶端不會顯示成兩封不同的信（`Message-ID`）
- **只寄測試資料**：使用測試帳號與測試訂單，不要寄含真實個資的內容
- 結果記進實作紀錄，截圖由使用者自行決定是否提供；沒有截圖就如實寫「使用者人工確認」

---

## 失敗模式

| 情況 | 行為 |
| ---- | ---- |
| SMTP／Mailpit 停機 | 信件留在 `email_message`（`PENDING`），退避重試；恢復後自動寄出；8 次後 `FAILED` 並告警 |
| SMTP 回應很慢 | 3／5 秒逾時 → 當作失敗退避；避免長時間佔住資料列鎖 |
| notification-service 停機 | 事件留在 Kafka（保留 7 天）；恢復後從 offset 繼續；超過保留期的事件遺失，列為已知限制 |
| 事件重複、亂序 | `processed_event` 去重；`(event_id, template)` 唯一；同 aggregate 在同 partition 有序 |
| 派送器寄出後當機（尚未更新狀態） | 重啟後該信仍是 `PENDING`，**會重寄一封**；`Message-ID` 相同。屬於至少一次的已知取捨 |
| 收件人／訂單資料尚未到達 | 派送器退避，最多 5 次，之後 `SKIPPED` 並計數 |
| 事件格式錯誤 | 進 DLT（共用的 DLT 告警響起），不影響其他事件 |
| 資料庫停機 | 消費者與派送器失敗；Kafka 重試機制接手，恢復後繼續 |
| order-service 在 notification 上線前已產生的訂單 | 沒有 `order_ref`，其後續事件的信件 `SKIPPED`（`unknown_order`）；預期行為，不算錯誤 |
| 使用者在 notification 上線前已註冊且超過 Kafka 保留期 | 沒有 `user_contact`，信件 `SKIPPED`（`unknown_user`）；已知限制 |

---

## 可觀測性

指標名稱沿用專案的點號風格：

| 指標 | tag | 說明 |
| ---- | ---- | ---- |
| `notification.emails` | `template`、`result`（`sent`／`failed`／`skipped`／`retry`） | 派送結果 |
| `notification.email.send.duration` | — | SMTP 寄出耗時（Timer） |
| `notification.email.pending` | — | `PENDING` 的筆數（Gauge） |
| `notification.email.oldest_pending_seconds` | — | 最舊 `PENDING` 的等待秒數（Gauge）；無待寄時為 0 |
| `notification.events` | `type`、`result`（`stored`／`ignored`／`duplicate`） | 消費端處理結果 |

- 儀表板：**改 `infra/observability/generate_dashboard.py` 再重新產生**，不要手改產生出來的 JSON；新增「通知」一列（各模板的寄出量、重試／失敗／略過、待寄筆數與最舊等待時間、SMTP 耗時）
- 告警（比照 `mm-search-fallback` 的寫法，改產生器後重新產生）：
  - `mm-notification-failed`：`increase(notification_emails_total{result="failed"}[10m]) > 0`（warning）
  - `mm-notification-backlog`：`notification_email_oldest_pending_seconds > 600` 持續 5 分鐘（warning）
  - 消費延遲與 DLT 沿用既有的 consumer lag 與 DLT 告警（確認 notification 的 consumer group 有被涵蓋）
- trace：消費者透過 `ResilientEventConsumer` 取得；派送器自行建立 span 並標記 `template`，**不要把收件地址或內容放進 span attribute**

---

## infra 規範

- **Mailpit**：compose 服務 `mailpit`（SMTP 埠 `1025`、UI `8025`，兩者都只綁 `127.0.0.1`）；K8s 加 Deployment＋Service（SMTP 供叢集內、UI 不建立對外 Service，用 port-forward）。映像**固定完整版號**，不用 `latest`，選定版本與理由寫進實作紀錄；kind 需先 `kind load docker-image`
- **notification-db**：加進 `infra/k8s/data/databases.yaml`，比照其他服務的 StatefulSet、PVC、Secret、備份／資源設定；compose 也要加對應的資料庫，名稱與埠沿用現有規則，避開已使用的埠
- **服務**：`infra/k8s/services/notification-service.yaml`，比照 inventory-service（副本數、探針、資源、`SERVER_SHUTDOWN` 與優雅關閉相關環境變數、`KAFKA_BOOTSTRAP_SERVERS`、`SPRING_DATASOURCE_URL`、`DB_USERNAME`／`DB_PASSWORD` 來自 Secret、OTLP 端點）；加進 `kustomization.yaml` 與 `pod-disruption-budgets.yaml`
- **NetworkPolicy**：ingress——notification-db 只允許 notification-service；Mailpit SMTP 只允許 notification-service；notification-service 只允許可觀測性元件抓指標。egress——notification-service 只開 Kafka、自己的 DB、Mailpit:1025、OTLP；檔案與寫法比照階段 C／D（`network-policies.yaml`、`network-policies-egress.yaml`），並用對照組實測
- **Gateway**：不新增路由。確認 `/actuator/*` 仍被封鎖
- **設定**：`application.yml` 使用 `@ConfigurationProperties` record（`NotificationProperties`：寄件地址、派送間隔、批次大小、退避表、逾時），SMTP 位址用 `MAIL_HOST`／`MAIL_PORT`（預設 `localhost`／`1025`）
- **SEC-15**（接續 SEC-14）：`known-issues.md` 新增「SMTP 未啟用認證與 TLS；通知資料庫保存使用者 Email」，格式比照 SEC-13、SEC-14。內容至少涵蓋：Mailpit 為開發用、UI 無認證；對外環境需要認證 SMTP＋TLS；`user_contact` 與 `email_message` 保存個資，沒有保留期限與清理機制
- **已知會踩的坑**（實作時確認，結果記進實作紀錄）：
  - Spring Boot 4 的 Mail 自動設定與逾時屬性名稱：以實際版本的文件為準，不要憑印象寫
  - Testcontainers 的 Mailpit 需要等 SMTP 與 HTTP 兩個埠都就緒；測試環境離線時要先把映像拉好
  - 純文字信件的編碼固定 UTF-8，主旨有中文時要確認 Mailpit 顯示正確

---

## 測試規範

- **用真的 Kafka、PostgreSQL、Mailpit**（Testcontainers）。信件內容與收件人用 Mailpit 的 HTTP API 斷言，不要 mock `JavaMailSender`
- 可以 mock 的只有「SMTP 失敗」的情境，用停掉容器或錯誤的位址模擬（參考階段 C 的 `OutboxRelayIntegrationTests`），而不是 mock 寄信元件
- 每個步驟的「驗收」欄與上方各步驟的測試重點都要有對應的自動化測試，測試名稱要能看出在驗什麼
- 既有測試不能被改弱：order-service、inventory-service、user-service 現有測試全部維持通過；改動它們的斷言要在實作紀錄說明理由
- 開發時只跑改到的模組（`mvnw.cmd -o -pl spring-notification-service -am test`）；**commit 前**（尤其是 E3、E4、E6）跑完整 reactor（`mvnw.cmd -o clean test`），結果的時間、耗時與模組數寫進實作紀錄
- 參考階段 D 留下的教訓：測試曾一度全部「通過」只是因為走了退回路徑結果剛好相同——寄信測試必須**斷言信件真的出現在 Mailpit**，不能只斷言資料庫狀態是 `SENT`

---

## 實作規範

這一節是整份文件最重要的部分之一。階段 C、D 的審查發現過一批「功能對、但做法和專案慣例不合」的問題，這次要避免。

### 流程

1. **一次只做一個 Step**。做完、測試通過、更新文件進度後 **commit**，才開始下一個 Step。**不要**一口氣做完整個階段再一次提交
2. Commit 訊息格式：`Phase E<N>: <英文簡述>`，例如 `Phase E3: emit order created and shipped events`。訊息本文簡述重點，結尾加上專案要求的 `Co-Authored-By` 行（與歷史 commit 一致）。**不要**使用 `fix(events):` 這類 conventional commit 格式
3. 每個 commit 只包含該步驟的檔案。**不要**順手修改無關的檔案（例如 `.vscode/launch.json` 是使用者本機的設定，永遠不要提交）
4. 不 push、不開 PR、不改 `main`，這些由使用者決定
5. 開始前先讀：[middleware-roadmap.md](middleware-roadmap.md)、[event-implementation-plan.md](event-implementation-plan.md)、[event-contracts.md](../../architecture/event-contracts.md)、[search-implementation-plan.md](search-implementation-plan.md) 的實作規範與 [phase-d-review.md](phase-d-review.md)（上一階段審查的教訓），以及 inventory-service 的 `OrderEventListener`、common 的 outbox／consumer 元件與測試風格
6. 完成後在實作紀錄最後列出「**最不確定、希望審查者重點看的地方**」

### 實作紀錄與文件

- 本文件是**計畫**，不要在裡面記實作結果。結果寫在新檔 `docs/implementation-plans/middleware/notification-implementation-record.md`，格式比照 [search-implementation-record.md](search-implementation-record.md)：標頭（日期、分支、對應、狀態）→ 內容表 → 規則說明 → 驗證結果 → 遇到的問題 → 已知限制 → 最不確定的地方
- **所有文件用繁體中文**（程式識別字、指令、設定鍵名除外）
- 驗證結果要有**可重現的證據**：指令、時間、數字。寫「測試通過」要附指令與結果；沒有親自驗證的項目標「待驗證」，不要寫成已完成。部署驗收的證據放 `evidence/phase-e/`
- **證據檔一律存成 UTF-8（無 BOM）**。Windows PowerShell 的 `>`、`Out-File` 預設會產生 UTF-16，請指定無 BOM 的 UTF-8
- 文件互相引用的地方要同步：每個 Step 完成時更新本計畫的進度、`docs/README.md` 的索引（**一個檔案只列一次**）、roadmap 的狀態。不要留下過時的敘述
- 新的安全限制記到 `docs/security/known-issues.md`（編號接續 SEC-14）
- **證據檔內不得出現真實或看起來真實的 Email、密碼**；用測試帳號（如 `*@example.com`），Mailpit 截取的內容要確認沒有敏感資料

### 程式風格（跟著現有程式，不要自創）

- **註解用繁體中文，寫「為什麼」，不要翻譯程式碼在做什麼**。類別或方法上方用 `//` 說明設計決定與取捨，風格參考 `OutboxRelay`、`ProductServiceImpl`
- **用 `import`，不要在程式裡寫 `java.util.Map.of(...)` 這種全名**；import 依專案現有順序整理，不要留下沒用到的 import
- **沒有用到的參數與欄位要刪掉**，不要為了「以後可能用」保留
- 套件與命名比照現有：`messaging/`（listener）、`service/` 與 `service/impl/`、`entities/`、`repository/`；設定用 `@ConfigurationProperties` 的 record，不要散落的 `@Value`
- 共用元件放 `common`，但 **`common` 不能出現任何單一服務的業務名詞**（例如訂單、通知的模板名稱）；本階段原則上**不需要**修改 `common`，如果需要先走[計畫變更](#計畫變更紀錄)
- 日誌用 `@Slf4j`，**不要記錄事件的完整 payload、Email、顧客名稱或信件內容**（`UserRegistered` 的 payload 含 Email，見 `event-contracts.md` 的警語）
- **不要加功能旗標來同時保留新舊兩條路徑**。本階段沒有舊路徑，不需要「是否寄信」的開關
- **不要在 listener、請求執行緒或派送器裡 `Thread.sleep`** 做重試；退避靠 `next_attempt_at` 欄位
- 不引入計畫沒寫的新依賴；唯一新增的主要依賴是 `spring-boot-starter-mail`（限 notification-service），測試用 Mailpit 的 Testcontainers 以 `GenericContainer` 使用，不另外引入郵件測試函式庫。需要其他依賴時，先走計畫變更並在實作紀錄寫明版本與理由

### 資料庫與 migration

- **已經存在的 migration 檔案絕對不改**（Flyway checksum）。notification 是新服務，編號從 `V1`；其他服務本階段**原則上不需要**新 migration，若發現需要先走計畫變更
- migration 中的 SQL 要能在 PostgreSQL 18 執行，並寫明用了哪些 18 才有的功能
- 時間用 `TIMESTAMPTZ`，金額用 `NUMERIC(12,2)`，主鍵用 UUID（應用端產生 UUIDv7，比照現有服務）

### 測試

- 見[測試規範](#測試規範)。另外：**不要用 `Thread.sleep` 等時間**，用輪詢加逾時，失敗時要能看出等的是什麼
- 測試要能在「離線 Maven + 本機已有映像」的環境執行
- 測試資料不要依賴執行順序；每個測試自己準備、自己清理（資料表、Kafka topic、Mailpit 的信箱）
- 避免重蹈階段 D 的覆轍：不要寫「預期不穩定」的時間精度斷言；有時間比較時，測試端與被測端使用相同精度

### 誠實回報

- 做不到或沒做的，寫進「已知限制」或「待驗證」，不要含糊帶過
- 計畫有錯或不合理，**先停下來記錄再改**（見下節），不要悄悄偏離
- 不要為了讓測試通過而放寬斷言、跳過測試或加 `@Disabled`；真的要這樣做，在實作紀錄寫明理由與後續
- 無法自動化驗證的項目（例如需要瀏覽器），明確標示由誰、如何驗證，不要用「不驗收」帶過而讓階段無法結案

---

## 已知限制（預期會寫進實作紀錄）

- **至少一次送達**：派送器寄出後、更新狀態前當機，會重寄一封；`Message-ID` 相同。
- **歷史資料暫不補寄**：notification 上線前已存在的使用者（超過 Kafka 保留期者）沒有 `user_contact`；上線前已建立的訂單沒有 `order_ref`。相關信件會被 `SKIPPED`。補資料需要 user-service／order-service 新增內部匯出 API，屬於[暫緩項目](#目標與範圍)，雛型後要補。
- **個資沒有保留期限**：`user_contact`、`email_message` 保存 Email 與寄信紀錄，目前沒有清理機制（SEC-15）。
- **SMTP 無認證與 TLS**：只適用於隔離的開發環境（SEC-15）。
- **只有純文字、單一語言**。
- **派送期間持有資料列鎖**：與 `OutboxRelay` 相同的簡化取捨，SMTP 逾時上限即鎖的最長持有時間。
- **Email 列舉（SEC-04）仍未解決**：註冊確認信是暫緩項目，雛型後要做。

---

## 驗收清單（階段 E 結案條件）

- [ ] E1 到 E6 各自單獨 commit，每個 commit 測試通過
- [ ] 完整 `mvnw.cmd -o clean test` 全模組成功（附指令、耗時、結果）
- [ ] 部署順序正確：inventory → order → notification，部署過程 DLT 告警沒有響
- [ ] E6 部署驗收表的每一項都有證據檔，沒有證據的項目明確標「待驗證」並說明原因
- [ ] E7（真實信箱驗證）不是結案條件，但紀錄要寫明「暫緩，待使用者配合」，且 repo 內沒有任何真實信箱位址
- [ ] 停 SMTP、停 notification 後信件不遺失、不重複（附證據）
- [ ] 日誌、trace、證據檔中沒有 Email、顧客名稱或信件內容
- [ ] order-service、inventory-service、user-service 既有測試未被放寬
- [ ] 文件：實作紀錄、`event-contracts.md`、`microservice-architecture.md`、`known-issues.md`（SEC-15）、`docs/README.md`、roadmap 都已同步，且全部是繁體中文
- [ ] 實作紀錄最後有「最不確定、希望審查者重點看的地方」

---

## 計畫變更紀錄

計畫與現實不符時，**不要直接改程式去配合，也不要直接改上面的計畫**。在這一節新增一筆：日期、哪個 Step、發現了什麼、打算怎麼改、為什麼。然後：

- 影響範圍小、不改變[已定的決定](#已定的決定)與事件契約的（例如調整退避時間、批次大小、信件文案）：記錄後自行處理，審查時說明
- 會改變決定、事件契約、`common`、需要新依賴或其他服務的新 migration 的：記錄後**停下來**，等使用者確認

- 2026-11-12｜E1｜`EventsAutoConfiguration` 會排程執行 `OutboxRelay`/`OutboxMetrics`，通知服務也需要 `outbox_event`。V1 已套用到 kind，不修改既有 migration；新增 V2 建立相容表與索引。未變更 `common` 或事件契約。
