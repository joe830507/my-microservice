# 前端元件關係圖與職責說明

這類文件回答「頁面由哪些元件組成、狀態由誰持有、操作如何呼叫 API、失敗時由誰處理」。以應用分檔，主要元件與關鍵流程畫圖，完整應用來源另以職責表連結。

| 應用 | 導讀 |
|---|---|
| vue-customer | [顧客端](vue-customer.md)：瀏覽、購物車、結帳、付款、訂單與帳號 |
| vue-management | [管理端](vue-management.md)：分類、商品、圖片、庫存、訂單與使用者 |

圖使用 Mermaid flowchart：實線箭頭表示元件呈現、呼叫或資料傳遞，依箭頭文字解讀；虛線表示事件回報或讀取關係，不表示 Java 繼承。元件樹與操作流程分開畫，Vue Router／Pinia／Axios／Element Plus 是框架協作者。props 往子元件、emit／v-model 往父層；store 與 composable 的依賴不是元件巢狀關係。

兩個應用有各自的 auth store、API client 與工具，名稱相同不表示共用程式碼。路由守衛及按鈕狀態是畫面控制，實際權限、價格、庫存、版本與交易仍由後端裁決。

新增頁面、調整 store 生命週期、API 契約或關鍵流程時，同步更新圖與來源對照。部署、歷史實作及測試證據繼續放在[前台實作紀錄](../implementation-plans/vue-customer/README.md)、[後台實作紀錄](../implementation-plans/vue-management/README.md)，後端依賴見[後端類別導讀](../class-relationships/README.md)。
