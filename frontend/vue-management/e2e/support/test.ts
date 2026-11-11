import { expect, test as base } from '@playwright/test'

// 在 Playwright 的 test 上加一個檢查：頁面不能有 CSP 違規或未捕捉的錯誤。
// 對著 nginx 映像檔跑（E2E_BASE_URL）時才有 CSP；對 Vite dev server 跑時沒有 CSP，這個檢查只會抓到未捕捉的錯誤。
const PROBLEMS =
  /Content Security Policy|violates the following|Refused to (load|apply|execute|connect)/i

export const test = base.extend<{ problems: string[] }>({
  problems: [
    async ({ page }, use) => {
      const problems: string[] = []
      page.on('console', (message) => {
        if (message.type() === 'error' && PROBLEMS.test(message.text())) {
          problems.push(`console: ${message.text()}`)
        }
      })
      page.on('pageerror', (error) => problems.push(`pageerror: ${error.message}`))
      await use(problems)
      expect(problems, '頁面不應該有 CSP 違規或未捕捉的錯誤').toEqual([])
    },
    { auto: true },
  ],
})

export { expect }
