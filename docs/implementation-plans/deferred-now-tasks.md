# 暫緩清單：立即處理項目

建立日期：2026-11-13。來源是[暫緩清單](deferred-items.md)中「處理時機」標為**現在**的項目。由 Codex 實作，Claude 審查；每一項各自一個 commit，先寫測試、跑過再提交。完成後依暫緩清單的[維護規則](deferred-items.md#維護規則)更新原始來源與清單，並在本文件對應章節填寫「完成紀錄」。

| 編號 | 項目 | 狀態 |
|---|---|---|
| T-01 | 訂單取消後收斂 PENDING 付款 | 已完成（`fix/t01-cancel-pending-payments`） |
| T-02 | 並行註冊衝突統一回 EMAIL_ALREADY_EXISTS | 已完成（`fix/t02-registration-email-conflict`） |
| T-03 | 共用電腦登出後清空購物車 | 已完成（`fix/t03-clear-cart-on-logout`） |

## T-01 訂單取消後收斂 PENDING 付款

### 問題

訂單被取消（顧客取消或付款逾時）之後，payment-service 裡同一張訂單的 `PENDING` 付款會一直停在 `PENDING`，因為付款狀態目前只由 `mock-callback` 決定。結果是：

- 資料庫留下永遠不會結束的付款紀錄，「進行中付款」的統計與查詢不準。
- 顧客如果停在付款頁，晚一點按「付款成功」仍然可以把付款改成 `SUCCEEDED`，再由 order-service 的 `PaymentEventListener` 記成「需要人工退款」。這是可以事先擋下來的情況。

### 決定

| 問題 | 決定 | 理由 |
|---|---|---|
| 由誰觸發收斂 | payment-service 消費 `order.events` 的 `OrderCancelled` | 符合現有「取消、逾時透過事件」的架構；`common` 已有 `ProcessedEventHandler`、`ResilientEventConsumer` 可重用，inventory 的 `OrderEventListener` 可當範本 |
| 收斂後的狀態 | 新增終態 `CANCELLED`，**不用** `FAILED` | `FAILED` 會送出 `PaymentFailed`，notification 會寄「付款失敗」信，但這筆付款其實不是失敗而是訂單取消了 |
| 是否發事件 | 不發 | 目前沒有消費者需要；之後有需要再加 `PaymentCancelled` |
| 取消之後才到的回呼 | `CANCELLED` 視為已完成：`SUCCESS`、`FAILED` 都回 409 `PAYMENT_ALREADY_COMPLETED` | 沿用既有的條件式 UPDATE（`WHERE status = 'PENDING'`），回呼與取消同時發生時只有先到的一方生效 |
| 回呼搶先成功 | 維持現況：付款 `SUCCEEDED`，order 端照舊記錄人工退款 | 這是真正的競爭情境，交給 G-14 退款流程處理，本項不擴大範圍 |

### 實作範圍

1. **Flyway migration**（payment，下一個版本號）：`chk_payment_status` 加入 `CANCELLED`。確認唯一約束 `(order_id) WHERE status IN ('PENDING', 'SUCCEEDED')` 不需要改：`CANCELLED` 不在條件內，所以取消後如果訂單還能付款（實際上不會），也不會被擋。
2. **`PaymentStatus`** 加入 `CANCELLED`，更新類別註解的狀態轉換說明。
3. **Repository**：新增依 `orderId` 的條件式 UPDATE，`SET status = 'CANCELLED' WHERE order_id = ? AND status = 'PENDING'`，回傳影響筆數。
4. **Consumer**：payment 新增 `order.events` 消費者，只處理 `OrderCancelled`，其餘事件類型明確忽略（參考 notification 對 `PaymentSucceeded` 的處理）。去重表與事件處理放在同一個交易，沿用 `common` 的元件。需要的 Kafka consumer 設定、去重表 migration、`ProcessedEventCleanup` 排程，比照 inventory。
5. **mock-callback**：`CANCELLED` 走既有的「已完成」分支，回 409 `PAYMENT_ALREADY_COMPLETED`。
6. **K8s NetworkPolicy**：確認 payment 到 Kafka 的 egress 已允許（目前已經是 producer，應該已允許），不需要時不要改。
7. **前台**：`frontend/vue-customer/src/features/payments/types.ts` 的 `PaymentStatus` 加入 `CANCELLED`；`usePayFlow.ts` 在 `CANCELLED` 時顯示「訂單已取消」並停止輪詢或停用付款按鈕。
8. **文件**：[事件契約](../architecture/event-contracts.md)的 `OrderCancelled` 消費者欄位加上 payment；[payment 類別導讀](../class-relationships/spring-payment-service.md)補上 consumer 與新狀態；payment 實作計畫的狀態表加一列 `CANCELLED`。

### 測試

- 整合測試（Testcontainers）：
  1. `PENDING` 付款收到 `OrderCancelled` → 變成 `CANCELLED`。
  2. 同一個事件重送 → 冪等，不報錯、不重複處理。
  3. 訂單已有 `SUCCEEDED` 或 `FAILED` 付款時收到 `OrderCancelled` → 不改變。
  4. 訂單沒有任何付款時收到 `OrderCancelled` → 正常略過。
  5. `CANCELLED` 之後送 `mock-callback` 的 `SUCCESS`、`FAILED` → 都回 409，且不產生 outbox 事件。
  6. 未知事件類型 → 忽略，不進 DLT。
- 前台 Vitest：`usePayFlow` 收到 `CANCELLED` 時的畫面狀態。

### 完成紀錄

2026-11-13：完成於 `fix/t01-cancel-pending-payments` 的單一提交（commit ID 以該分支 git log 為準）。新增 CANCELLED、V8、OrderCancelled consumer 與交易去重／清理；前台取得 CANCELLED 後顯示訂單已取消並停用付款。payment 原有 Kafka egress 已允許 9092，未修改 NetworkPolicy。新 consumer 設 earliest，涵蓋保留期內歷史取消事件。

測試：新增取消 Testcontainers 整合測試 11 項、既有回呼 15 項與 outbox 1 項，共 27 項通過；前台付款頁 23 項通過（新增測試先重現失敗），type-check 與異動檔 Prettier 通過。後端初次執行因 wrapper／測試編譯問題未完成，改用已安裝 Maven 並修正測試後通過。文件本機連結／章節錨點、UTF-8 無 BOM 與 git diff --check 通過。未在 kind 部署或驗證 Kafka broker 投遞；本次整合測試直接呼叫實際 listener，使用真實 PostgreSQL。

## T-02 並行註冊衝突統一回 EMAIL_ALREADY_EXISTS

### 問題

`AuthServiceImpl.register` 先用 `existsByEmail` 檢查，已存在時回 409 `EMAIL_ALREADY_EXISTS`。但同一個 email 同時送出兩個註冊請求時，兩邊都可能通過檢查，後寫入的會被 `uq_users_email` 擋下，回傳共用的 409 `DATA_CONFLICT`。同一種情況有兩種錯誤碼，前端必須兩種都處理。

### 決定（使用者 2026-11-13）

統一回 `EMAIL_ALREADY_EXISTS`。依序註冊本來就會回這個錯誤碼，所以這次修改不會額外增加 Email 列舉的風險（列舉問題仍由 SEC-04 追蹤）。

### 實作範圍

1. 在 `register` 中捕捉 `transactions.execute(...)` 拋出的 `DataIntegrityViolationException`，**只有**違反 `uq_users_email` 時才轉成 `AppException(UserErrorCode.EMAIL_ALREADY_EXISTS)`；其他約束違反照原樣拋出，不可以全部吞成 email 重複。
   - 判斷約束名稱的方式要可靠，例如從 cause 取出 Hibernate 的 `ConstraintViolationException.getConstraintName()`，或 PostgreSQL 的 `PSQLException` 的 server error message。不要用例外訊息字串比對整句。
   - 可以參考 `BootstrapAdminInitializer` 既有的 `DataIntegrityViolationException` 處理方式，若適合就抽成共用的小方法。
2. 刪掉 `register` 中「回 DATA_CONFLICT」的過時註解，改寫成新的行為。
3. user-service 實作計畫第 27 行與錯誤碼表確認一致（已註記決定，完成後補上「已完成」）。

### 測試

- 整合測試：用兩個執行緒加 `CountDownLatch` 同時註冊同一個 email，斷言一個成功、另一個回 409 `EMAIL_ALREADY_EXISTS`，資料庫只有一筆。重複執行數次確認穩定（可以用 `@RepeatedTest`）。
- 單元或整合測試：其他約束違反（如果有可觸發的）不會被轉成 `EMAIL_ALREADY_EXISTS`。
- 確認 outbox 沒有因為失敗的那一筆多寫 `UserRegistered`。

### 完成紀錄

2026-11-13：完成於 `fix/t02-registration-email-conflict` 的單一提交（commit ID 以該分支 git log 為準）。register 在交易外捕捉 DataIntegrityViolationException，只有 Hibernate 約束名稱 uq_users_email 才轉 EMAIL_ALREADY_EXISTS；未改動管理員初始化行為。新增同步屏障並行測試重複 5 次，均為一筆 201、一筆 409 EMAIL_ALREADY_EXISTS、只有一個使用者與一筆 UserRegistered；另驗證其他資料庫限制不轉換且交易與 outbox 回滾。原先只跑新增 6 項與既有註冊 15 項，共 21 項。審查修正後執行完整 -pl spring-user-service -am test：user 170 項、common 67 項全部通過。並行測試先穩定重現原錯誤；測試資料與 outbox 信封查詢修正後全部通過。文件本機連結／錨點、UTF-8 無 BOM 與 git diff --check 通過；未在 kind 驗證。

## T-03 共用電腦登出後清空購物車

### 問題

購物車存在瀏覽器 localStorage（`vue-customer.cart`），不跟帳號綁定；登出時保留，同一台電腦的下一個人看得到前一個人的購物車。

### 決定（使用者 2026-11-13）

**使用者主動登出時清空購物車**，不做依帳號分開存放、登入後合併等較複雜的邏輯。同一個人登出再登入，購物車會是空的，這是接受的取捨。

| 情境 | 是否清空 | 理由 |
|---|---|---|
| 頂部列「登出」（`auth.logout()`，由 `StoreLayout.vue` 呼叫） | 清空 | 決定的範圍 |
| 帳號頁「登出所有裝置」（`auth.logoutAll()`，由 `AccountView.vue` 呼叫），撤銷成功後 | 清空 | 本機一樣會變成登出狀態；撤銷失敗時不登出，也不清空 |
| 登入（`login()` 一開始會呼叫 `clear()`） | **不清空** | 未登入時加入的購物車要能帶進結帳 |
| refresh 被後端拒絕、登入過期（`doRefresh` 呼叫 `clear()`） | **不清空** | 使用者沒有主動登出；登入過期就清掉購物車體驗不好 |
| 登入流程中取得使用者資料失敗而內部呼叫 `logout()` | **不清空** | 這不是使用者主動登出 |

前三個情境都經過 auth store 的 `clear()`，所以**不能把清空購物車放進 `clear()`**，要只放在使用者主動登出的路徑。

### 實作範圍

1. **cart store**（`features/cart/store.ts`）新增 `clear()`：把 `items` 設成空陣列，並移除 localStorage 的 `CART_KEY`（或寫入空陣列）。其他分頁會透過既有的 `storage` 事件同步成空的，不需要另外處理。
2. **主動登出的路徑**呼叫 `cart.clear()`，建議做法擇一：
   - 在 auth store 的 `logout()`／`logoutAll()` 加一個參數或拆出 `userLogout()`，只有使用者主動觸發時才清空；或
   - 在兩個呼叫端（`StoreLayout.vue`、`AccountView.vue`）登出成功後呼叫 `cart.clear()`。
   選擇時注意不要讓 auth store 與 cart store 互相 import 形成循環；`login()` 內部失敗時呼叫的 `logout()` 不可以清空購物車。
3. 更新 cart store 頂部「登出時保留（雛型）」的註解，以及 [vue-customer 紀錄](vue-customer/README.md)中「登出時保留」的設計說明。

### 測試

- Vitest：
  1. 主動登出後購物車變空、localStorage 的 `CART_KEY` 被清除。
  2. 「登出所有裝置」成功後清空；API 失敗時購物車保留。
  3. 登入、登入過期（refresh 回 4xx）時購物車保留。
- Playwright（現有 E2E 有登出流程的話一併調整）：加入商品 → 登出 → 購物車件數為 0。

### 完成紀錄

2026-11-13：完成於 `fix/t03-clear-cart-on-logout` 的單一提交（commit ID 以該分支 git log 為準）。cart.clear() 清空記憶體並移除 CART_KEY；StoreLayout 在等待主動登出回應前呼叫，AccountView 只在撤銷所有裝置成功後呼叫，避免 auth/cart 循環依賴。登入、refresh 4xx 與登入取得使用者資料失敗時保留購物車；撤銷失敗同樣保留。審查修正後前台完整 Vitest 30 檔 342 項通過（主動登出兩項先重現失敗），type-check 與異動檔 Prettier 通過。Playwright 完整 4 項通過；登出案例已移至 browser-tests/cart-logout.spec.ts，實際點選登入、加入商品、登出，確認兩分頁件數歸零與 localStorage 被移除；使用攔截 API 的 Chrome 測試，未跑真實後端 E2E、未部署 kind。文件本機連結／錨點、UTF-8 無 BOM、git diff --check 通過。
