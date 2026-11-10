import { createRouter, createWebHistory, type RouteRecordRaw, type Router } from 'vue-router'

import { useAuthStore } from '@/features/auth/store'
import HomeView from '@/views/HomeView.vue'

declare module 'vue-router' {
  interface RouteMeta {
    // 不需要登入就能進入的頁面
    public?: boolean
  }
}

// 路由表另外匯出：測試用 createMemoryHistory 建立自己的 router，不共用全域的 router 狀態
export const routes: RouteRecordRaw[] = [
  { path: '/', name: 'home', component: HomeView },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/features/auth/LoginView.vue'),
    meta: { public: true },
  },
]

// 未登入 → 登入頁（帶 redirect）；重新整理頁面後先用 refresh token 恢復登入狀態
// 這裡只影響畫面，真正的權限檢查由 Gateway 與後端的 @RequireRoles 負責
export function installAuthGuard(router: Router): void {
  router.beforeEach(async (to) => {
    const auth = useAuthStore()
    if (to.meta.public) {
      // 已登入還進登入頁：直接回首頁
      if (to.name === 'login' && auth.isAuthenticated) {
        return { name: 'home' }
      }
      return true
    }
    if (await auth.restoreSession()) {
      return true
    }
    return { name: 'login', query: { redirect: to.fullPath } }
  })
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})
installAuthGuard(router)

export default router
