<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useCartStore } from '@/features/cart/store'
import { useCategoryStore } from '@/features/catalog/categoryStore'
import { useAuthStore } from '@/features/auth/store'
import { APP_TITLE } from '@/router'

const auth = useAuthStore()
const categories = useCategoryStore()
const cart = useCartStore()
const route = useRoute()
const router = useRouter()

onMounted(() => void categories.load())

// 搜尋框：送出後到 /search?q=；在搜尋頁時跟著網址上的關鍵字
const keyword = ref('')
watch(
  () => route.query.q,
  (q) => {
    keyword.value = route.name === 'search' && typeof q === 'string' ? q : ''
  },
  { immediate: true },
)

function onSearch(): void {
  const q = keyword.value.trim()
  void router.push({ name: 'search', query: q ? { q } : {} })
}

async function onLogout(): Promise<void> {
  cart.clear()
  await auth.logout()
  // 留在需要登入的頁面會看到別人的資料或錯誤：回首頁
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
        <form class="store-search" role="search" @submit.prevent="onSearch">
          <input
            v-model="keyword"
            type="search"
            name="q"
            maxlength="255"
            placeholder="搜尋商品"
            aria-label="搜尋商品"
          />
        </form>
        <nav class="store-nav" aria-label="主要導覽">
          <RouterLink class="store-nav__item" :to="{ name: 'cart' }">
            購物車<span
              v-if="cart.count > 0"
              class="store-nav__badge"
              :aria-label="`${cart.count} 件商品`"
              >{{ cart.count }}</span
            >
          </RouterLink>
          <template v-if="auth.isAuthenticated">
            <RouterLink class="store-nav__user" :to="{ name: 'account' }">{{
              auth.user?.displayName
            }}</RouterLink>
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
      <nav v-if="categories.tree.length > 0" class="store-categories" aria-label="商品分類">
        <div class="container store-categories__inner">
          <RouterLink
            v-for="category in categories.tree"
            :key="category.id"
            :to="{ name: 'category', params: { slug: category.slug } }"
          >
            {{ category.name }}
          </RouterLink>
        </div>
      </nav>
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

.store-nav__badge {
  display: inline-block;
  min-width: 1.4em;
  margin-left: var(--space-1);
  padding: 0 var(--space-1);
  border-radius: 999px;
  background: var(--color-primary);
  color: var(--color-on-primary);
  font-size: var(--font-size-sm);
  line-height: 1.4;
  text-align: center;
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

.store-categories {
  border-top: 1px solid var(--color-border);
}

/* 分類多時橫向捲動，不換行 */
.store-categories__inner {
  display: flex;
  gap: var(--space-4);
  overflow-x: auto;
  white-space: nowrap;
}

.store-categories a {
  padding: var(--space-2) 0;
  font-size: var(--font-size-sm);
}

.store-categories a:hover,
.store-categories a.router-link-active {
  color: var(--color-primary);
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
