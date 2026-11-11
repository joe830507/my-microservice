import { createApp } from 'vue'
import { createPinia } from 'pinia'

import './styles/tokens.css'
import './styles/base.css'
import App from './App.vue'
import apiClient from './api/http'
import { installAuthInterceptors } from './features/auth/interceptors'
import { useAuthStore } from './features/auth/store'
import router from './router'

const app = createApp(App)
const pinia = createPinia()

// pinia 要在 router 之前註冊：router 一註冊就會開始第一次導覽，路由守衛會用到 auth store
app.use(pinia)

const auth = useAuthStore(pinia)

installAuthInterceptors(apiClient, auth, () => {
  // refresh 失敗、登入狀態已清除：只有在需要登入的頁面才導到登入頁；逛商品時不打斷，頂部列會變回「登入」
  const current = router.currentRoute.value
  if (current.meta.requiresAuth) {
    void router.replace({ name: 'login', query: { redirect: current.fullPath } })
  }
})

// 重新整理後在背景恢復登入狀態，頂部列才會顯示已登入；路由守衛會共用同一次恢復，不會重複 refresh
void auth.restoreSession()

app.use(router)

app.mount('#app')
