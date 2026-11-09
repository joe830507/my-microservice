# inventory-service 實作步驟

> 建立日期：2026-11-09
> 分支：`feature/inventory-service`
> 對應：[microservice-architecture.md](microservice-architecture.md)「inventory-service」、「服務互動：下單流程」
> 狀態：Step 1–2 完成（2026-11-09），下一步 Step 3

## 進度

| Step | 內容 | 狀態 |
|---|---|---|
| 1 | 建立專案、DB 與資料表 | 完成 |
| 2 | `GET /api/v1/inventory/{sku}` 查庫存 | 完成 |
| 3 | `PUT /api/v1/inventory/{sku}` 後台設定庫存（ADMIN） | 未開始 |
| 4 | `POST /internal/v1/inventory/reservations` 預扣 | 未開始 |
| 5 | `POST /internal/v1/inventory/reservations/{orderId}/confirm` 確認扣除 | 未開始 |
| 6 | `POST /internal/v1/inventory/reservations/{orderId}/release` 釋放預扣 | 未開始 |
| 7 | api-gateway 加上 `/api/v1/inventory/**` 路由 | 未開始 |

每一步都附整合測試，測試通過後單獨 commit。

---

## Step 1：建立專案、DB 與資料表

| 項目 | 值 |
|---|---|
| Group / Artifact | `com.mymicroservice` / `inventory-service` |
| Package | `com.mymicroservice.inventory` |
| 位置 | `backend/spring-inventory-service`，加入 `backend/pom.xml` 的 modules |
| Boot | 4.1.1（和 product、user 一致），依賴 common |
| port | 8083 |
| DB | `inventory-db`（postgres:18.6，本機 port 5434），資料庫 `inventory_db`，schema `inventory_service` |
| 依賴 | webmvc、data-jpa、flyway、validation、actuator、springdoc、Lombok |

不需要 Redis：雛型階段用 DB 的條件式 UPDATE 防止超賣，之後再換 Redis Lua。

### 資料表

架構文件原本寫 `reservation(order_id, sku, qty, status)`，這裡拆成主檔與明細：預扣是「全部成功或全部失敗」，狀態屬於整張訂單，不是個別 SKU。

```
stock
  sku         VARCHAR(64) PK      和 product 的 sku 長度一致
  available   INT  >= 0           可賣的數量（不含已預扣）
  reserved    INT  >= 0           已預扣、等待付款的數量
  + 稽核欄位、version

reservation
  order_id    UUID PK             冪等鍵：同一張訂單只會有一筆
  status      RESERVED | CONFIRMED | RELEASED
  + 稽核欄位、version

reservation_item
  order_id    UUID  FK → reservation
  sku         VARCHAR(64) FK → stock
  qty         INT > 0
  PK (order_id, sku)
```

- `available`、`reserved` 用 CHECK `>= 0` 當最後防線，程式出錯也不會扣成負數
- 庫存的數量變動一律用**條件式 UPDATE**（`WHERE available >= ?`），不先讀再寫，避免併發時超賣

**測試**：context 啟動、Flyway 套用成功、`/actuator/health` 為 UP。

**已完成**：`InventoryServiceApplication`、實體 `Stock`、`Reservation`、`ReservationItem`、`V1__create_inventory_tables.sql`、compose 的 `inventory-db`；測試 `InventoryServiceApplicationIntegrationTests`（6）：資料表建立、health，以及負數庫存、不存在的 SKU、數量 0、未知狀態都會被 DB 擋下。

---

## Step 2：查庫存

`GET /api/v1/inventory/{sku}`

| 項目 | 做法 |
|---|---|
| 權限 | 不需登入（商品頁要顯示庫存）；Gateway 在 Step 7 放行 `GET` |
| 回應 | `{sku, available, reserved, updatedAt}` |
| 錯誤 | SKU 不存在 → 404 `STOCK_NOT_FOUND` |

**測試**：存在、不存在、SKU 超過 64 字元回 400。

**已完成**：`StockController`、`StockService`、`InventoryErrorCode`；測試 `StockControllerGetIntegrationTests`（5），涵蓋 SKU 區分大小寫，回應不含稽核欄位與 version。

---

## Step 3：後台設定庫存

`PUT /api/v1/inventory/{sku}`，body `{available}`

| 項目 | 做法 |
|---|---|
| 權限 | `@RequireRoles("ADMIN")` |
| 語意 | 把 `available` **設成**指定值（盤點後直接設定）；`reserved` 不受影響 |
| 不存在 | 建立新的庫存紀錄，回 201；已存在回 200 |
| 併發 | 用 `@Version`：設定期間剛好有人下單預扣 → 409 `VERSION_CONFLICT`，請管理者重新讀取後再設定，避免蓋掉剛被扣掉的數量 |
| 驗證 | `available` 必填、`>= 0` |

SKU 雛型階段不檢查 product-service 裡是否存在；之後改成接收 `ProductCreated` 事件自動建立。

**測試**：建立、更新、非 ADMIN 403、沒有身分 401、負數 400、稽核欄位記錄操作者。

---

## Step 4：預扣

`POST /internal/v1/inventory/reservations`，body `{orderId, items[{sku, qty}]}`

| 項目 | 做法 |
|---|---|
| 全有全無 | 同一個交易內逐一條件式 UPDATE：`available -= qty, reserved += qty WHERE available >= qty`；任一個失敗就整筆 rollback |
| 錯誤 | 庫存不足 → 409 `INSUFFICIENT_STOCK`；SKU 不存在 → 404 `STOCK_NOT_FOUND`；訊息列出所有出問題的 SKU，不是只有第一個 |
| 冪等 | 同一個 `orderId` 再送一次：內容相同 → 回 200 和原本的結果，不重複扣；內容不同 → 409 `RESERVATION_MISMATCH` |
| 已釋放 | `orderId` 已經釋放過 → 409 `RESERVATION_ALREADY_RELEASED`（避免延遲送達的預扣在取消之後才扣） |
| 回應 | 新建立回 201：`{orderId, status, items}` |
| 死結 | 依 SKU 排序後再更新，兩張訂單同時扣同一組 SKU 時鎖的順序一致 |
| 驗證 | `orderId` 必填；`items` 1–100 筆；`qty >= 1`；同一個 SKU 重複出現時合併數量 |

- 內部 API 沒有 `X-User-Id`，稽核欄位記為系統使用者
- 兩個相同 `orderId` 的請求同時送達：後到的會被 `reservation` 的主鍵擋下，回 409 `DATA_CONFLICT`；呼叫方重試時就會走冪等的路徑

**測試**：成功扣減、庫存不足時完全不扣、SKU 不存在、重送相同內容、重送不同內容、重複 SKU 合併、併發搶最後一件只有一個成功。

---

## Step 5：確認扣除

`POST /internal/v1/inventory/reservations/{orderId}/confirm`（付款成功後由 order-service 呼叫）

| 目前狀態 | 結果 |
|---|---|
| `RESERVED` | 改成 `CONFIRMED`，每個 SKU `reserved -= qty`（`available` 在預扣時已經扣過）；回 200 |
| `CONFIRMED` | 不做任何事，回 200（冪等） |
| `RELEASED` | 409 `RESERVATION_ALREADY_RELEASED` |
| 不存在 | 404 `RESERVATION_NOT_FOUND` |

狀態轉換用條件式 UPDATE（`WHERE status = 'RESERVED'`），兩個請求同時確認時只有一個會調整庫存。

**測試**：上表每種狀態、確認後庫存數字正確、重複確認不會再扣。

---

## Step 6：釋放預扣

`POST /internal/v1/inventory/reservations/{orderId}/release`（取消或付款逾時時呼叫）

| 目前狀態 | 結果 |
|---|---|
| `RESERVED` | 改成 `RELEASED`，每個 SKU `available += qty, reserved -= qty`；回 200 |
| `RELEASED` | 不做任何事，回 200（冪等） |
| `CONFIRMED` | 409 `RESERVATION_ALREADY_CONFIRMED`（已付款的訂單要走退貨流程，雛型階段不做） |
| 不存在 | 建立一筆沒有明細的 `RELEASED` 紀錄，回 200 |

**不存在時為什麼要建立紀錄**：order-service 呼叫預扣逾時，不知道到底有沒有扣成功，於是取消並呼叫釋放。如果預扣的請求之後才送達，看到 `RELEASED` 就會被擋下（Step 4），庫存不會被扣住沒人釋放。

**測試**：上表每種狀態、釋放後庫存數字正確、先釋放再預扣會被擋。

---

## Step 7：Gateway 路由

在 api-gateway 加上：

| 路徑 | 目標 | 登入 |
|---|---|---|
| `/api/v1/inventory/**` | inventory-service（`INVENTORY_SERVICE_URI`，預設 `http://localhost:8083`） | `GET` 不需要；`PUT` 需要 JWT（ADMIN 由 inventory-service 檢查） |

`/internal/v1/inventory/**` 本來就被 Gateway 擋下，不需要另外處理。

**測試**：在 api-gateway 的路由、JWT 測試補上 inventory 的案例。

---

## 之後再處理

- 預扣逾時的保險：inventory 自己定期釋放超過一定時間仍是 `RESERVED` 的預扣（以防 order-service 沒有呼叫釋放）
- 接收 product 的 `ProductCreated` 事件，自動建立庫存紀錄
- 換成 Redis Lua 扣庫存（熱門商品高併發）
- Kafka 導入後，確認與釋放改成接收 `PaymentSucceeded`、`OrderCancelled` 事件
