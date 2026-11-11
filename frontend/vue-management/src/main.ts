import { createApp } from 'vue'
// Element Plus 的深色變數（元件樣式是按需引入，變數要另外載入），再用自己的配色覆寫
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/theme.css'
import { createPinia } from 'pinia'

import App from './App.vue'
import apiClient from './api/http'
import { installAuthInterceptors } from './features/auth/interceptors'
import { useAuthStore } from './features/auth/store'
import router from './router'

const app = createApp(App)
const pinia = createPinia()

// pinia 要在 router 之前註冊：router 一註冊就會開始第一次導覽，路由守衛會用到 auth store
app.use(pinia)

installAuthInterceptors(apiClient, useAuthStore(pinia), () => {
  // refresh 失敗、登入狀態已清除：導回登入頁，登入後回到原本的頁面
  const current = router.currentRoute.value
  if (current.name !== 'login') {
    void router.replace({ name: 'login', query: { redirect: current.fullPath } })
  }
})

app.use(router)

app.mount('#app')
