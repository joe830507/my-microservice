# 立即處理項目審查（T-01～T-03）

審查日期：2026-11-13。審查對象是 [立即處理項目](deferred-now-tasks.md) 的三個實作分支，各自從 `main`（`2b30617`）分出：

| 任務 | 分支 | 提交 |
|---|---|---|
| T-01 訂單取消後收斂 PENDING 付款 | `fix/t01-cancel-pending-payments` | `c2c2661` |
| T-02 並行註冊衝突統一回 EMAIL_ALREADY_EXISTS | `fix/t02-registration-email-conflict` | `b2309c7` |
| T-03 共用電腦登出後清空購物車 | `fix/t03-clear-cart-on-logout` | `e37a6b5` |

由 Codex 依下列項目修正，Claude 再複審。修正請直接提交在原分支上，每個 R 項目一個 commit；修正後在本文件對應項目填寫「處理結果」。

## 審查時實際執行的驗證

| 分支 | 指令 | 結果 |
|---|---|---|
| T-01 | `./mvnw -pl spring-payment-service -am test` | **失敗**：61 項中 1 項失敗（見 R1） |
| T-01 | vue-customer `vitest run`、`vue-tsc --build --force` | 338 項通過；型別檢查通過 |
| T-02 | `./mvnw -pl spring-user-service -am test` | 全部通過；`RegistrationConflictIntegrationTests` 6 項通過 |
| T-03 | vue-customer `vitest run`、`vue-tsc --build --force` | 341 項通過；型別檢查通過 |
| T-03 | `npx playwright test -c playwright.optimization.config.ts` | 4 項通過，含新增的跨分頁登出測試 |
| 三個分支 | 依 T-02 → T-03 → T-01 順序模擬合併 | T-02 乾淨；T-03、T-01 在兩份文件衝突（見 R2） |

未驗證：三個分支都沒有在 kind 上部署驗證；T-01 沒有經過真實 Kafka broker 投遞（整合測試直接呼叫 listener）。

## 總表

| 編號 | 分支 | 嚴重度 | 摘要 |
|---|---|---|---|
| R1 | T-01 | 高 | 既有測試 `flyway_createsPaymentTable` 被改壞；完成紀錄只跑了部分測試 |
| R2 | 全部 | 中 | 三個分支都改了暫緩清單的同一處，第二、三個合併時會衝突 |
| R3 | T-03 | 低 | 登出時要等伺服器回應後才清空購物車，期間關閉分頁購物車就不會被清掉 |
| R4 | T-01 | 低 | `processed_event` 索引沒有沿用 inventory 最佳化後的 BRIN |
| R5 | T-01 | 低 | `PaymentStatus.java` 缺結尾換行 |
| R6 | T-02 | 低 | 新增的 import 插在 security import 中間 |
| R7 | T-03 | 低 | 登出清空購物車的瀏覽器測試放在最佳化專用的測試檔 |

## R1（高）T-01 改壞既有測試，完成紀錄未跑完整模組

- **位置**：`backend/spring-payment-service/src/test/java/com/mymicroservice/payment/PaymentServiceApplicationIntegrationTests.java:54`
- **現象**：V8 新增 `processed_event` 資料表，但這個測試斷言 `payment_service` schema 只能有 `payment`、`shedlock`、`outbox_event` 三張表：

  ```text
  Expecting actual:
    ["shedlock", "payment", "outbox_event", "processed_event"]
  to contain exactly in any order:
    ["payment", "shedlock", "outbox_event"]
  but the following elements were unexpected:
    ["processed_event"]
  ```

- **原因**：完成紀錄寫「新增 11 項、既有回呼 15 項與 outbox 1 項，共 27 項通過」，只執行了挑選過的測試類別，沒有跑整個模組，所以沒發現這個失敗。
- **修正**：
  1. 斷言加入 `processed_event`；方法名稱如有需要可以改成符合現況的名稱。
  2. 執行 `./mvnw -pl spring-payment-service -am test`，確認整個模組全部通過。
  3. 更正 T-01 的完成紀錄：寫出整個模組的測試總數與結果，並說明先前只跑部分測試、漏掉這一項。
- **之後的規則**：完成紀錄的「通過」要以 `-pl <module> -am test` 的整個模組結果為準；只跑部分測試時要寫清楚範圍。

**處理結果**：已將 Flyway 斷言加入 processed_event，方法改名為 flyway_createsPaymentAndEventTables。執行完整 -pl spring-payment-service -am test：payment 61 項、common 67 項，全部通過。T-01 完成紀錄已承認原先只跑 27 項部分測試而漏掉此斷言；後續通過紀錄以整個模組為準。未部署 kind。

## R2（中）三個分支在暫緩清單上衝突

- **位置**：`docs/implementation-plans/deferred-items.md`（「現在」的項目數、「現在」表格、變更紀錄）、`docs/implementation-plans/deferred-now-tasks.md`（狀態總表）
- **現象**：三個分支都從同一個 `main` 分出，各自把「現在」的項目數從 3 改成 2、刪掉自己那一列、在變更紀錄開頭插一行，並改狀態總表。依序合併時，第二個與第三個分支一定會衝突。
- **修正**：依 **T-02 → T-03 → T-01** 的順序處理（T-01 還要先修 R1）。每合併一個，就把下一個分支 rebase 到最新的 `main`，再解衝突：
  - 項目數依序變成 2 → 1 → 0。
  - 「現在」表格最後是空的，保留表頭，並在表格前加一句「目前沒有項目」。
  - 變更紀錄保留三行，依時間排列，**不要**在項目之間留空行（T-02 分支目前在兩個項目之間多了一個空行）。
  - 狀態總表三項都改成「已完成」，各自附上分支名稱。
- **之後的規則**：多個任務都要改同一份追蹤文件時，用堆疊分支（後一個從前一個分出），或把追蹤文件的更新集中在最後一個分支。

**處理結果**：

## R3（低）T-03 登出時購物車清空得太晚

- **位置**：`frontend/vue-customer/src/layouts/StoreLayout.vue` 的 `onLogout()`
- **現象**：目前先 `await auth.logout()` 再 `cart.clear()`。`auth.logout()` 會先清本機登入狀態，再等後端 `/auth/logout` 回應。等待期間畫面已經顯示「未登入」，但購物車件數還在；如果使用者這時候關閉分頁，或網路很慢，購物車就不會被清掉。T-03 本來就是為了共用電腦，這種「看起來已經登出、購物車卻還在」的狀況正好違反目的。
- **修正**：改成先呼叫 `cart.clear()`，再 `await auth.logout()`。`auth.logout()` 本來就不會拋出例外（伺服器錯誤都在內部吞掉），所以先清空購物車不會出現「沒登出成功卻清空」的情況。
  - `AccountView.vue` 的「登出所有裝置」**維持現狀**：撤銷失敗時要保留登入與購物車，所以必須等撤銷成功後才清空。
- **測試**：在 `StoreLayout.spec.ts` 加一個案例，讓 `/auth/logout` 延遲回應（MSW `delay` 或手動控制的 Promise），斷言在回應之前購物車就已經清空。

**處理結果**：StoreLayout 改成先 cart.clear()，再 await auth.logout()；AccountView 保留撤銷成功後才清空。新增手動控制 logout 回應的測試，先重現等待期間購物車殘留，修正後回應前即清空。完整 Vitest 342 項與 vue-tsc --build --force 通過。

## R4（低）T-01 的 processed_event 索引沒有沿用 BRIN

- **位置**：`backend/spring-payment-service/src/main/resources/db/migration/V8__cancel_pending_payments.sql`
- **現象**：V8 用一般 B-tree 建立 `idx_processed_event_processed_at`。inventory 在最佳化審查時已經改成 `USING BRIN`（`spring-inventory-service` 的 `V6__optimize_event_indexes_and_cleanup.sql`），因為這個欄位只會遞增寫入、只用於依時間批次清理。
- **修正**：改用 `USING BRIN (processed_at)`，跟 inventory 一致。schema 前綴不用改：payment 既有的事件表（V3 `outbox_event`）也沒有寫前綴，V8 跟它一致。
- **注意**：V8 還沒合併、還沒部署，可以直接修改 V8，不需要另外新增 V9。如果本機開發資料庫已經跑過舊版 V8，要先清掉那個資料庫，否則 Flyway checksum 驗證會失敗。

**處理結果**：V8 的 processed_at 索引已改為 USING BRIN，沿用 inventory。Flyway 測試查 pg_class／pg_am 確認實際建立 brin 索引；完整 -pl spring-payment-service -am test：payment 61 項、common 67 項全部通過。使用全新 Testcontainers 資料庫，未清除或修改開發資料庫，未新增 V9。

## R5（低）PaymentStatus.java 缺結尾換行

- **位置**：`backend/spring-payment-service/src/main/java/com/mymicroservice/payment/enums/PaymentStatus.java`
- **現象**：diff 顯示 `\ No newline at end of file`；原本的檔案有結尾換行。
- **修正**：補上結尾換行。

**處理結果**：已補 PaymentStatus.java 結尾換行；位元組檢查與 git diff --check 通過，無行為變更。

## R6（低）T-02 的 import 順序

- **位置**：`backend/spring-user-service/src/main/java/com/mymicroservice/user/service/impl/AuthServiceImpl.java` 的 import 區塊
- **現象**：`org.springframework.dao.DataIntegrityViolationException` 與 `org.hibernate.exception.ConstraintViolationException` 插在 `org.springframework.security.crypto.password.PasswordEncoder` 與其他 `org.springframework.security.*` 之間，跟檔案原本依套件排序的寫法不一致。
- **修正**：照檔案現有的排序規則放到對應位置（`org.hibernate` 在 `org.springframework` 之前，`dao` 在 `security` 之前）。

**處理結果**：已按套件順序將 org.hibernate 放在 org.springframework 前、dao 放在 security 前。完整 -pl spring-user-service -am test：user 170 項、common 67 項全部通過，含並行註冊 5 輪；git diff --check 通過。

## R7（低）T-03 的瀏覽器測試放錯檔案

- **位置**：`frontend/vue-customer/browser-tests/optimization.spec.ts`
- **現象**：新增的 `user logout clears the cart in this tab and another open tab` 放在最佳化審查專用的測試檔裡。這個檔案的其他測試都對應最佳化審查的項目（跨分頁同步、圖片提早載入等），跟登出政策無關，之後不容易找到。
- **修正**：搬到獨立的檔案，例如 `browser-tests/cart-logout.spec.ts`。共用的 `id`、`product` 等測試資料如果需要，抽到小的 fixture 模組，或在新檔案裡自己定義。確認 `npx playwright test -c playwright.optimization.config.ts` 仍然全部通過；如果這份設定只收錄最佳化相關的測試，要在檔案頂部註解說明。

**處理結果**：

## 審查確認沒有問題的部分

- **T-01**：
  - 條件式 UPDATE（`WHERE status = 'PENDING'`）與回呼共用同一個條件，回呼與取消同時發生時只有一方生效。
  - `ProcessedEventHandler` 在同一個交易裡寫入去重紀錄並更新付款；重送的事件測試確認版本只加 1。
  - 未知事件類型明確忽略、不進 DLT；版本不符時進 DLT，跟其他服務的 consumer 一致。
  - 不送出 `PaymentFailed`，所以不會寄出錯誤的「付款失敗」信。
  - Kafka consumer lag 告警以 `service_name` 分組，會自動涵蓋新的 consumer，不需要修改告警設定。
  - 新的 consumer group 從 `earliest` 開始消費，Kafka 保留期內已取消的訂單會一併收斂，這是預期的效果。
  - 前台付款頁顯示「訂單已取消」，並且不會出現「已付款項會退回」的文字。
- **T-02**：
  - 只轉換 Hibernate 回報約束名稱為 `uq_users_email` 的錯誤，其他約束錯誤原樣拋出，並有測試涵蓋（`display_name` 長度）。
  - 捕捉的位置在 `transactions.execute` 外面，提交時才發生的約束錯誤也能轉換。
  - 並行測試用 `PasswordEncoder` spy 製造同步點，確保兩個請求都通過 `existsByEmail` 才寫入，重複執行 5 次都穩定；也驗證失敗的那一筆沒有多寫 `UserRegistered`。
- **T-03**：
  - 清空購物車只放在兩個使用者主動登出的路徑；`auth` store 的 `clear()` 沒有被修改，登入、登入過期、登入失敗時內部登出都保留購物車，三種情境都有測試。
  - 「登出所有裝置」撤銷失敗時購物車保留，有測試涵蓋。
  - 其他分頁透過既有的 `storage` 事件同步清空，瀏覽器測試已驗證。
  - 儲存被封鎖時仍會清空記憶體中的購物車。
