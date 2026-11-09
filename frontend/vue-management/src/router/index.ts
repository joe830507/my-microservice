import { createRouter, createWebHistory } from 'vue-router'

import HomeView from '@/views/HomeView.vue'

// 路由表另外匯出：測試用 createMemoryHistory 建立自己的 router，不共用全域的 router 狀態
export const routes = [{ path: '/', name: 'home', component: HomeView }]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

export default router
