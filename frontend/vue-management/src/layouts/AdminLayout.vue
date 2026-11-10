<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter, type RouteLocationRaw } from 'vue-router'
import { useAuthStore } from '@/features/auth/store'
import { APP_TITLE, menuItems } from '@/router'

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
      to: record.name ? { name: record.name } : undefined,
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

async function onLogout(): Promise<void> {
  await auth.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <el-container class="admin-layout">
    <el-aside class="admin-aside" width="200px">
      <div class="admin-brand">{{ APP_TITLE }}</div>
      <el-menu :default-active="activeMenu" router class="admin-menu">
        <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
          {{ item.title }}
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="admin-header">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item v-for="crumb in crumbs" :key="crumb.title" :to="crumb.to">
            {{ crumb.title }}
          </el-breadcrumb-item>
        </el-breadcrumb>

        <div class="admin-user">
          <span v-if="auth.user" class="admin-user-name">{{ auth.user.displayName }}</span>
          <el-button type="primary" link @click="onLogout">登出</el-button>
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
}

.admin-aside {
  border-right: 1px solid var(--el-border-color);
}

.admin-brand {
  height: 60px;
  line-height: 60px;
  padding: 0 20px;
  font-weight: 600;
  border-bottom: 1px solid var(--el-border-color);
}

.admin-menu {
  border-right: none;
}

.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid var(--el-border-color);
}

.admin-user {
  display: flex;
  align-items: center;
  gap: 12px;
  white-space: nowrap;
}

.admin-user-name {
  color: var(--el-text-color-regular);
}

.admin-main {
  background: var(--el-bg-color-page);
}
</style>
