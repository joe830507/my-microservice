import { defineConfig, devices } from '@playwright/test'

// E2E 對著「真的後端」跑：整套後端（Gateway :8080 與各服務）要先啟動，見 docs/implementation-plans/vue-customer/step-12-e2e.md
//
// 兩種跑法：
// - 預設：自己啟動 Vite dev server（:5184，/api 由 Vite proxy 轉給 Gateway），沒有 CSP。
//   用專屬的連接埠並加 --strictPort：不和平常開發用的 :5174 衝突，也不會誤連到別的程式佔用的連接埠
// - E2E_BASE_URL=http://localhost:8090：對著已經在跑的網址（例如 docker run 的 nginx 映像檔或 K8s 的 port-forward），
//   這時會有 CSP 與安全標頭，測試裡的「沒有 CSP 違規」檢查才有意義
const externalBaseUrl = process.env.E2E_BASE_URL
const baseURL = externalBaseUrl ?? 'http://localhost:5184'

export default defineConfig({
  testDir: './e2e',
  // 全域 setup 建立 E2E 專用的商品與分類（重複使用，不會越建越多），結束後停用，商店看不到
  globalSetup: './e2e/global-setup.ts',
  // 測試共用同一個後端與資料庫，而且有些會改全域狀態（庫存、商品狀態）：一次只跑一個
  fullyParallel: false,
  workers: 1,
  timeout: 90_000,
  expect: { timeout: 10_000 },
  reporter: [['list']],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    locale: 'zh-TW',
  },
  projects: [
    {
      name: 'chromium',
      // 預設用本機安裝的 Chrome，不用另外下載瀏覽器；CI 上設 E2E_CHANNEL= 改用 Playwright 自己的 Chromium
      // （先執行 npx playwright install chromium）
      use: { ...devices['Desktop Chrome'], channel: process.env.E2E_CHANNEL ?? 'chrome' },
    },
  ],
  webServer: externalBaseUrl
    ? undefined
    : {
        command: 'npm run dev -- --port 5184 --strictPort',
        url: baseURL,
        reuseExistingServer: true,
        timeout: 60_000,
      },
})
