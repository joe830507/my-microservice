# vue-customer Step 2：HTTP 層與登入狀態

> 屬於 [vue-customer 實作計畫](README.md)

- 從 vue-management 複製 `api/http.ts`、`api/errors.ts`、`features/auth` 的 `api.ts`、`types.ts`、`interceptors.ts`、`store.ts`，以及 `composables/useRouteQuery.ts`（先複製，不抽共用套件）
- store 拿掉 ADMIN 檢查與 `NOT_ADMIN` 錯誤代碼：ADMIN 也能逛和下單，登入後不檢查角色
- refresh token 的 localStorage key 為 `vue-customer.refreshToken`，和後台分開；同一台電腦可以同時登入兩邊的不同帳號
- 路由守衛 `installAuthGuard`：只有 `meta.requiresAuth` 的頁面才需要登入（和後台相反），未登入導到登入頁並帶 `redirect`；重新整理後先用 refresh token 恢復登入狀態。已登入進登入頁回首頁
- `main.ts` 的 session 過期處理：refresh 失敗時，**只有在需要登入的頁面**才導到登入頁；逛商品時不打斷
- `/login` 路由與 `LoginView.vue` 先放佔位內容（Step 3 實作），守衛才有地方可以導

## 完成內容

**測試**（`npm run test:unit`，全部 53 個）：
- 沿用後台的 `errors`、`http`、攔截器（同時兩個 401 只 refresh 一次、refresh 失敗清除等）、store（登入、恢復、登出）測試，去掉 ADMIN 相關的案例
- `router/__tests__/guard.spec.ts`：不需要登入的頁面未登入也能進入；需要登入的頁面導到登入頁並帶 `redirect`；有 refresh token 時恢復登入；refresh token 失效導到登入頁；已登入進登入頁回首頁；404；分頁標題

`npm run build`、oxlint、eslint 通過。
