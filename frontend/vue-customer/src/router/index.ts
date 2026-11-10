import { createRouter, createWebHistory, type RouteRecordRaw, type Router } from 'vue-router'

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
      {
        path: ':pathMatch(.*)*',
        name: 'not-found',
        component: () => import('@/views/NotFoundView.vue'),
        meta: { title: '找不到頁面' },
      },
    ],
  },
]

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
installTitleUpdater(router)

export default router
