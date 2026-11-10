import { createRouter, createWebHistory, type RouteRecordRaw, type Router } from 'vue-router'

import { useAuthStore } from '@/features/auth/store'
import HomeView from '@/views/HomeView.vue'

declare module 'vue-router' {
  interface RouteMeta {
    // 需要登入才能進入的頁面（結帳、付款、我的訂單、帳號）；沒設就是不用登入也能逛
    requiresAuth?: boolean
    // 瀏覽器分頁標題
    title?: string
  }
}

// 畫面上顯示的商店名稱（頂部列、頁尾、瀏覽器分頁標題）；專案名稱 vue-customer 只用在程式碼與 repo
export const APP_TITLE = 'Joe 商城'

// 路由表另外匯出：測試用 createMemoryHistory 建立自己的 router，不共用全域的 router 狀態
export const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('@/layouts/StoreLayout.vue'),
    children: [
      { path: '', name: 'home', component: HomeView },
      // 頁面內容在 Step 3 完成；先有路由，守衛才有地方可以導
      {
        path: 'login',
        name: 'login',
        component: () => import('@/features/auth/LoginView.vue'),
        meta: { title: '登入' },
      },
      {
        path: ':pathMatch(.*)*',
        name: 'not-found',
        component: () => import('@/views/NotFoundView.vue'),
        meta: { title: '找不到頁面' },
      },
    ],
  },
]

// 只有 meta.requiresAuth 的頁面需要登入（和後台相反：後台是 meta.public 的頁面才不用登入）
// 未登入 → 登入頁（帶 redirect）；重新整理頁面後先用 refresh token 恢復登入狀態
// 這裡只影響畫面，真正的權限檢查由 Gateway 與後端負責
export function installAuthGuard(router: Router): void {
  router.beforeEach(async (to) => {
    const auth = useAuthStore()
    if (to.meta.requiresAuth) {
      if (await auth.restoreSession()) {
        return true
      }
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    // 已登入還進登入頁：直接回首頁
    if (to.name === 'login' && auth.isAuthenticated) {
      return { name: 'home' }
    }
    return true
  })
}

// 瀏覽器分頁標題：「頁面標題 - Joe 商城」
export function installTitleUpdater(router: Router): void {
  router.afterEach((to) => {
    document.title = to.meta.title ? `${to.meta.title} - ${APP_TITLE}` : APP_TITLE
  })
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})
installAuthGuard(router)
installTitleUpdater(router)

export default router
