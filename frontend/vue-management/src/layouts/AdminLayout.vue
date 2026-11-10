<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter, type RouteLocationRaw } from 'vue-router'
import { useAuthStore } from '@/features/auth/store'
import { APP_TITLE, MENU_SECTIONS, menuItems } from '@/router'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

interface Crumb {
  title: string
  to?: RouteLocationRaw
}

// 麵包屑：首頁 + 目前路由一路上有 title 的層級；最後一層（目前頁面）不放連結
const crumbs = computed<Crumb[]>(() => {
  const titled = route.matched.filter((record) => record.meta.title && record.name !== 'home')
  const items: Crumb[] = [{ title: '首頁', to: { name: 'home' } }]
  for (const record of titled) {
    items.push({
      title: record.meta.title ?? '',
      // 沒有名稱的父路由（例如「商品管理」）用路徑連結；路徑有參數時無法直接連結
      to: record.name ? { name: record.name } : record.path.includes(':') ? undefined : record.path,
    })
  }
  // 專案的 lib 是 ES2020，沒有 Array.prototype.at
  const last = items[items.length - 1]
  if (last) {
    last.to = undefined
  }
  return items
})

// 選單選中的項目：目前路徑本身，或它所在的上層選單（例如 /products/1 對應 /products）
const activeMenu = computed(() => {
  const match = menuItems
    .filter((item) =>
      item.path === '/'
        ? route.path === '/'
        : route.path === item.path || route.path.startsWith(`${item.path}/`),
    )
    .sort((a, b) => b.path.length - a.path.length)[0]
  return match?.path ?? route.path
})

// 側邊選單依分組顯示（總覽、商品、營運），沒有項目的分組不顯示
const menuGroups = computed(() =>
  MENU_SECTIONS.map((section) => ({
    section,
    items: menuItems.filter((item) => item.section === section),
  })).filter((group) => group.items.length > 0),
)

// 頂部列的頭像：顯示名稱的第一個字
const avatarText = computed(() => auth.user?.displayName.trim().charAt(0).toUpperCase() ?? '')

async function onLogout(): Promise<void> {
  await auth.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <el-container class="admin-layout">
    <el-aside class="admin-aside" width="232px">
      <div class="admin-brand">{{ APP_TITLE }}</div>
      <el-menu :default-active="activeMenu" router class="admin-menu">
        <el-menu-item-group v-for="group in menuGroups" :key="group.section">
          <template #title>
            <span class="menu-section">{{ group.section }}</span>
          </template>
          <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
            {{ item.title }}
          </el-menu-item>
        </el-menu-item-group>
      </el-menu>
    </el-aside>

    <el-container class="admin-body">
      <el-header class="admin-header">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item v-for="crumb in crumbs" :key="crumb.title" :to="crumb.to">
            {{ crumb.title }}
          </el-breadcrumb-item>
        </el-breadcrumb>

        <div class="admin-user">
          <el-button class="logout-button" round @click="onLogout">登出</el-button>
          <template v-if="auth.user">
            <span class="admin-user-name">{{ auth.user.displayName }}</span>
            <span class="admin-avatar" aria-hidden="true">{{ avatarText }}</span>
          </template>
        </div>
      </el-header>

      <el-main class="admin-main">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.admin-layout {
  min-height: 100vh;
  background: var(--el-bg-color-page);
}

/* 側邊欄：卡片底色，和內容區用顏色區隔，不畫邊線 */
.admin-aside {
  background: var(--el-bg-color);
}

.admin-brand {
  padding: 28px 24px 20px;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 0.3px;
  color: var(--el-text-color-primary);
}

.admin-menu {
  --el-menu-bg-color: transparent;
  --el-menu-hover-bg-color: var(--el-fill-color);
  --el-menu-text-color: var(--el-text-color-regular);
  --el-menu-active-color: var(--el-text-color-primary);
  --el-menu-item-height: 44px;
  border-right: none;
}

/* 分組標題：洋紅色、大寫字距，和參考圖一致 */
.admin-menu :deep(.el-menu-item-group__title) {
  padding: 20px 24px 8px !important;
  border-top: 1px solid var(--el-border-color-lighter);
}

.admin-menu :deep(.el-menu-item-group:first-child .el-menu-item-group__title) {
  border-top: none;
}

.menu-section {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 2px;
  color: var(--app-accent-magenta);
}

.admin-menu .el-menu-item {
  padding-left: 24px !important;
  font-size: 14px;
}

/* 選中的項目：紫色漸層底，左邊一條強調色 */
.admin-menu .el-menu-item.is-active {
  background: linear-gradient(90deg, rgb(155 92 246 / 45%) 0%, rgb(155 92 246 / 8%) 100%);
  box-shadow: inset 4px 0 0 var(--app-accent-violet);
  font-weight: 600;
}

.admin-body {
  min-width: 0;
}

.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  height: 72px;
  padding: 0 32px;
}

.admin-user {
  display: flex;
  flex-direction: row-reverse;
  align-items: center;
  gap: 12px;
  white-space: nowrap;
}

.admin-user-name {
  color: var(--el-text-color-regular);
}

.admin-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  font-weight: 700;
  color: #fff;
  background: var(--app-gradient-violet);
}

.admin-main {
  padding: 8px 32px 32px;
}

@media (max-width: 768px) {
  .admin-aside {
    width: 180px !important;
  }

  .admin-header,
  .admin-main {
    padding-left: 16px;
    padding-right: 16px;
  }

  .admin-user-name {
    display: none;
  }
}
</style>
