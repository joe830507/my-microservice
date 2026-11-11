<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/features/auth/store'
import { APP_TITLE } from '@/router'

// 骨架階段：搜尋、購物車件數是假的內容，分別在 Step 4、6 接上真的功能
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

async function onLogout(): Promise<void> {
  await auth.logout()
  // 留在需要登入的頁面會看到別人的資料或錯誤：回首頁（購物車不清空）
  if (route.meta.requiresAuth) {
    await router.push({ name: 'home' })
  }
}
</script>

<template>
  <div class="store-layout">
    <header class="store-header">
      <div class="container store-header__inner">
        <RouterLink :to="{ name: 'home' }" class="store-brand">{{ APP_TITLE }}</RouterLink>
        <form class="store-search" role="search" @submit.prevent>
          <input type="search" name="q" placeholder="搜尋商品" aria-label="搜尋商品" />
        </form>
        <nav class="store-nav" aria-label="主要導覽">
          <a class="store-nav__item" href="#" @click.prevent>購物車</a>
          <template v-if="auth.isAuthenticated">
            <span class="store-nav__user">{{ auth.user?.displayName }}</span>
            <button type="button" class="store-nav__item store-nav__logout" @click="onLogout">
              登出
            </button>
          </template>
          <template v-else>
            <RouterLink
              class="store-nav__item"
              :to="{ name: 'login', query: { redirect: route.fullPath } }"
            >
              登入
            </RouterLink>
            <RouterLink class="store-nav__item" :to="{ name: 'register' }">註冊</RouterLink>
          </template>
        </nav>
      </div>
    </header>

    <main class="store-main container">
      <RouterView />
    </main>

    <footer class="store-footer">
      <div class="container">© {{ APP_TITLE }}（雛型專案，不會真的出貨）</div>
    </footer>
  </div>
</template>

<style scoped>
.store-layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.store-header {
  position: sticky;
  top: 0;
  z-index: 10;
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
}

/* 手機：Logo 與導覽在第一列，搜尋框換到第二列；寬螢幕三者排成一列 */
.store-header__inner {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2) var(--space-4);
  min-height: var(--header-height);
  padding-top: var(--space-2);
  padding-bottom: var(--space-2);
}

.store-brand {
  font-size: var(--font-size-lg);
  font-weight: 700;
  color: var(--color-primary);
}

.store-search {
  flex: 1 1 100%;
  order: 3;
}

.store-search input {
  width: 100%;
  height: 40px;
  padding: 0 var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg);
  font: inherit;
}

.store-nav {
  display: flex;
  gap: var(--space-4);
  margin-left: auto;
}

.store-nav__item:hover {
  color: var(--color-primary);
}

.store-nav__user {
  max-width: 8em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-text-muted);
}

.store-nav__logout {
  padding: 0;
  border: 0;
  background: none;
  font: inherit;
  cursor: pointer;
}

.store-main {
  flex: 1;
  padding-top: var(--space-6);
  padding-bottom: var(--space-8);
}

.store-footer {
  padding: var(--space-6) 0;
  border-top: 1px solid var(--color-border);
  background: var(--color-surface);
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

@media (min-width: 768px) {
  .store-search {
    flex: 1 1 auto;
    order: 0;
    max-width: 480px;
  }
}
</style>
