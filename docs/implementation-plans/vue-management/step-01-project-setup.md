# vue-management Step 1：建立專案

> 屬於 [vue-management 實作計畫](README.md)

- `npm create vue@latest`：TypeScript、Router、Pinia、Vitest、ESLint、Prettier
- 加入 Element Plus（按需引入）
- `vite.config.ts` 設定 proxy：`/api` → `http://localhost:8080`

**測試**：`npm run build`、`npm run test:unit` 通過；冒煙測試能掛載 App。

**檢查結果（2026-11-09）**：

| 檢查                                | 結果                                                                                                                       |
| ----------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| `npm run build`（含 `vue-tsc`） | 通過                                                                                                                       |
| eslint、oxlint                      | 沒有問題                                                                                                                   |
| `vitest`                          | 1 個通過（create-vue 的範例測試）                                                                                          |
| dev server + proxy                  | 首頁 200；`/api/v1/products` 有轉送到 `localhost:8080`（Gateway 沒啟動時回 502 `ECONNREFUSED`，代表 proxy 設定正確） |

- 一開始 IDE 在 `vite.config.ts`、`tsconfig.node.json` 報找不到 `vite`、`@types/node`：只是還沒執行 `npm install`
- `npm audit` 有 16 個 moderate，全部在開發工具（eslint 相關、`editorconfig` 依賴的 `minimatch`），不會打包進前端；**不要用 `npm audit fix --force`**，會跨主要版本升降級，可能弄壞 eslint 設定，等上游出新版再 `npm update`

**待辦**：

- [X] 加入 Element Plus：安裝 `element-plus`；建議按需引入（`unplugin-vue-components`、`unplugin-auto-import` + `ElementPlusResolver`），只打包用到的元件
- [X] 整理 `vite.config.ts`
  - [X] 用 Prettier 格式化：`npx prettier --write vite.config.ts`（`npm run format` 只處理 `src/`）
  - [X] 刪除 `secure: false`：只對 https 目標有作用
  - [X] 刪除註解掉的 `rewrite`：Gateway 的路徑本來就是 `/api/v1/...`，前綴不能去掉
  - [X] 範例的英文註解改成專案說明（開發時轉給 api-gateway；正式環境由 nginx 轉發）
- [X] 清掉 create-vue 的範例內容
  - [X] 刪除 `src/stores/counter.ts`
  - [X] `App.vue` 只放 `<RouterView />`，router 加一個暫時的首頁路由
  - [X] `App.spec.ts` 改成冒煙測試：掛載 App + router，確認首頁顯示；順便確認 Element Plus 元件能渲染
- [X] 刪除 `frontend/vue-management/.gitkeep`
- [X] `.env.development`：`VITE_API_BASE=/api/v1`（也可以延到 Step 2 寫 `http.ts` 時再加）
- [X] 補完後重新檢查，整個前端專案（含 `package-lock.json`）commit 並 push

**收尾時遇到的問題**：

- **Vitest 載入 Element Plus 的 CSS 失敗**（`Unknown file extension ".css"`）：按需引入會 import `element-plus/theme-chalk/*.css`，而 Vitest 預設讓 Node 直接載入 `node_modules`。在 `vitest.config.ts` 設 `server.deps.inline: ['element-plus']` 改由 Vite 處理
- 自動產生的 `src/auto-imports.d.ts`、`src/components.d.ts` 放在 `src/` 才會被 `tsconfig.app.json` 涵蓋；加進 `.prettierignore` 與 eslint 的 `globalIgnores`，不做格式檢查
- `App.vue` 的 `<RouterView />` 前面混進一個零寬空白（U+200B），畫面上會多一個文字節點；編輯器看不出來，用 `grep $'\xe2\x80\x8b'` 才找到
- `env.d.ts` 宣告 `ImportMetaEnv.VITE_API_BASE`，Step 2 的 `http.ts` 讀取時有型別
- 冒煙測試用 `createMemoryHistory` 建立自己的 router（`router/index.ts` 另外匯出 `routes`），並確認 `<el-button>` 被解析成 Element Plus 元件

**最終檢查**：vitest 1 個通過、`npm run build`（含 `vue-tsc`）通過、eslint、oxlint、`prettier --check .` 都沒有問題；dev server 首頁 200，`/api` 有轉送到 8080。
