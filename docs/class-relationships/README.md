# 類別關係圖與職責說明

這類文件用來回答：「這個類別負責什麼、與誰協作、由誰建立，以及應該從哪裡開始讀程式？」依後端模組分檔，圖與職責表放在同一份文件，方便對照。

| 模組 | 閱讀入口 | 內容 |
|---|---|---|
| common | [common 類別關係圖與職責說明](common.md) | 自動設定、身分與權限、錯誤處理、JPA 稽核、下游呼叫保護、事件與排程鎖 |
| observability | [observability 類別關係圖與職責說明](observability.md) | Logback 與 OpenTelemetry 接線、共用設定、三種資料流、框架與服務責任界線 |
| spring-api-gateway | [Gateway 類別關係圖與職責說明](spring-api-gateway.md) | WebFlux／路由過濾鏈、JWT、身分 header、來源 IP、Redis 限流、熔斷與錯誤回應 |
| spring-inventory-service | [庫存服務類別關係圖與職責說明](spring-inventory-service.md) | 庫存與預扣、狀態與數量轉換、交易／冪等、訂單事件、逾時回收與 migration |
| spring-notification-service | [通知服務類別關係圖與職責說明](spring-notification-service.md) | 事件投影、郵件入列／去重、租約寄送、重試與模板 |
| spring-order-service | [訂單服務類別關係圖與職責說明](spring-order-service.md) | 建單快照／冪等、狀態交易、下游補償、付款事件、逾時與 outbox |
| spring-payment-service | [付款服務類別關係圖與職責說明](spring-payment-service.md) | 有效付款唯一性、模擬回呼、條件更新、outbox 與通知機制退役 |

## 圖例

文件使用 Markdown 與 Mermaid；支援 Mermaid 的預覽器可直接顯示圖形。每張圖只畫該主題的重要關係，完整來源清單由職責表補足。

| 標記 | 意義 |
|---|---|
| `Parent <\|-- Child` | Child 繼承 Parent |
| `Interface <\|.. Implementation` | Implementation 實作介面 |
| `A --> B` | A 持有 B 的參照，例如建構子注入的欄位 |
| `A ..> B` | A 使用 B、呼叫靜態方法或建立 B；由箭頭文字進一步說明 |
| `A *-- B` | 組合關係，B 的生命週期從屬於 A，例如預扣與其明細 |

關聯旁的 `1`、`0..*` 表示一個與零到多個。狀態圖另外描述允許的狀態轉換，不把其中的箭頭解讀成 Java 繼承或方法呼叫。

自動設定的註冊總覽與資料流使用流程圖。Spring 註冊、框架回呼與間接協作不等於 Java 繼承；文件會分別說明。外部服務類別與框架型別僅作協作背景，不計入各模組自訂類別的來源檔數。

## 維護方式

類別新增、移動、依賴或責任界線改變時，同步更新對應模組的圖、職責表與來源連結。描述目前程式實作；設計理由與歷史驗收仍連回既有架構、實作計畫及測試，不複製成另一份規格。

跨服務的互動先讀[系統導讀](../architecture/system-guide.md)，事件格式與消費契約見[事件契約](../architecture/event-contracts.md)。
