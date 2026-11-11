import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue(), vueDevTools()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // 後台是 5173，前台用 5174，兩邊可以同時開發
    port: 5174,
    proxy: {
      // 開發時把 /api 轉給 api-gateway，前端和 API 維持同一個來源，後端不用設 CORS
      // 正式環境由 nginx 轉發（見 Step 11）；Gateway 的路徑本來就是 /api/v1/...，不用 rewrite
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
