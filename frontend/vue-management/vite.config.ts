import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import Components from 'unplugin-vue-components/vite'
import { defineConfig } from 'vite'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    vueDevTools(),
    // Element Plus 按需引入：只打包用到的元件與樣式
    // AutoImport 處理 ElMessage、ElMessageBox 這類函式呼叫的 API；Components 處理 template 裡的 <el-xxx>
    // 型別檔放在 src/ 才會被 tsconfig.app.json 涵蓋；兩個檔案都 commit，clone 下來不用先啟動就能型別檢查
    AutoImport({
      resolvers: [ElementPlusResolver()],
      dts: 'src/auto-imports.d.ts',
    }),
    Components({
      resolvers: [ElementPlusResolver()],
      dts: 'src/components.d.ts',
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
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
