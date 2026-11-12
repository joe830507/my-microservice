import { defineConfig, devices } from '@playwright/test'
export default defineConfig({
  testDir: './browser-tests',
  workers: 1,
  use: { baseURL: 'http://localhost:5185', ...devices['Desktop Chrome'], channel: 'chrome' },
  webServer: {
    command: 'npm run dev -- --port 5185 --strictPort',
    url: 'http://localhost:5185',
    reuseExistingServer: true,
  },
})
