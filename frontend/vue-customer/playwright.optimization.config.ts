// 歷史檔名保留；執行 browser-tests 的所有 API 攔截瀏覽器回歸測試，包含購物車登出政策。
import { defineConfig, devices } from '@playwright/test'
export default defineConfig({
  testDir: './browser-tests',
  workers: 1,
  use: { baseURL: 'http://localhost:5184', ...devices['Desktop Chrome'], channel: 'chrome' },
  webServer: {
    command: 'npm run dev -- --port 5184 --strictPort',
    url: 'http://localhost:5184',
    reuseExistingServer: true,
  },
})
