import { createRouter, createWebHistory, type RouteRecordRaw, type Router } from 'vue-router'

import { useAuthStore } from '@/features/auth/store'
import HomeView from '@/views/HomeView.vue'

declare module 'vue-router' {
  interface RouteMeta {
    // 不需要登入就能進入的頁面
    public?: boolean
    // 麵包屑、瀏覽器分頁標題
    title?: string
    // 出現在側邊選單（只看 AdminLayout 底下的路由）
    menu?: boolean
    // 側邊選單的分組標題
    section?: MenuSection
  }
}

// 畫面上顯示的系統名稱（側邊欄、登入頁、瀏覽器分頁標題）；專案名稱 vue-management 只用在程式碼與 repo
export const APP_TITLE = '商城管理後台'

// 側邊選單的分組，依這個順序顯示
export const MENU_SECTIONS = ['總覽', '商品', '營運'] as const
export type MenuSection = (typeof MENU_SECTIONS)[number]

// AdminLayout 底下的頁面；之後的功能（分類、商品、訂單…）加在這裡，設 menu: true 就會出現在側邊選單
const adminChildren: RouteRecordRaw[] = [
  {
    path: '',
    name: 'home',
    component: HomeView,
    meta: { title: '首頁', menu: true, section: '總覽' },
  },
  {
    path: 'categories',
    name: 'categories',
    component: () => import('@/features/categories/CategoriesView.vue'),
    meta: { title: '分類管理', menu: true, section: '商品' },
  },
  // 父路由只提供麵包屑與選單的「商品管理」，沒有自己的元件
  {
    path: 'products',
    meta: { title: '商品管理', menu: true, section: '商品' },
    children: [
      {
        path: '',
        name: 'products',
        component: () => import('@/features/products/ProductListView.vue'),
      },
      {
        path: 'new',
        name: 'product-create',
        component: () => import('@/features/products/ProductEditView.vue'),
        meta: { title: '新增商品' },
      },
      {
        path: ':id',
        name: 'product-edit',
        component: () => import('@/features/products/ProductEditView.vue'),
        meta: { title: '編輯商品' },
      },
    ],
  },
  {
    path: 'orders',
    meta: { title: '訂單管理', menu: true, section: '營運' },
    children: [
      {
        path: '',
        name: 'orders',
        component: () => import('@/features/orders/OrderListView.vue'),
      },
      {
        path: ':id',
        name: 'order-detail',
        component: () => import('@/features/orders/OrderDetailView.vue'),
        meta: { title: '訂單詳情' },
      },
    ],
  },
  {
    path: 'users',
    name: 'users',
    component: () => import('@/features/users/UserListView.vue'),
    meta: { title: '使用者', menu: true, section: '營運' },
  },
]

// 路由表另外匯出：測試用 createMemoryHistory 建立自己的 router，不共用全域的 router 狀態
export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/features/auth/LoginView.vue'),
    meta: { public: true, title: '登入' },
  },
  // AdminLayout 用動態 import：它會 import 這個檔案的 menuItems，靜態 import 會形成循環
  { path: '/', component: () => import('@/layouts/AdminLayout.vue'), children: adminChildren },
  // 403、404 不需要登入：未登入時打錯網址也直接顯示 404，不先導到登入頁
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('@/views/ForbiddenView.vue'),
    meta: { public: true, title: '沒有權限' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { public: true, title: '找不到頁面' },
  },
]

export interface MenuItem {
  path: string
  title: string
  section: MenuSection
}

// 側邊選單：AdminLayout 底下設了 menu: true 的路由
export const menuItems: MenuItem[] = adminChildren
  .filter((route) => route.meta?.menu)
  .map((route) => ({
    path: `/${route.path}`,
    title: route.meta?.title ?? '',
    section: route.meta?.section ?? '總覽',
  }))

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

// 瀏覽器分頁標題：「頁面標題 - 商城管理後台」
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
