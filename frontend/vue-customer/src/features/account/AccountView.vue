<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { toApiError } from '@/api/errors'
import { useAuthStore } from '@/features/auth/store'
import { formatDateTime } from '@/utils/format'

const auth = useAuthStore()
const router = useRouter()

// 先顯示登入時取得的資料，進頁面再重新取得一次；重新取得失敗就維持目前的資料，不打斷頁面
onMounted(() => {
  void auth.refreshUser().catch(() => {})
})

// 登出所有裝置：先在頁面內確認，不用瀏覽器的 confirm 對話框
const confirming = ref(false)
const submitting = ref(false)
const errorMessage = ref('')

async function logoutAll(): Promise<void> {
  // 處理中不再送第二次
  if (submitting.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await auth.logoutAll()
    // 購物車保留（不跟帳號綁定）；回首頁，頂部列變回未登入
    await router.replace({ name: 'home' })
  } catch (error) {
    // 撤銷失敗：維持登入，讓使用者知道其他裝置可能還登入著，可以再試
    errorMessage.value = `登出所有裝置失敗，其他裝置可能仍然登入：${toApiError(error).detail}`
    confirming.value = false
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section v-if="auth.user" class="account">
    <h1>我的帳號</h1>

    <dl class="account__info">
      <div>
        <dt>顯示名稱</dt>
        <dd>{{ auth.user.displayName }}</dd>
      </div>
      <div>
        <dt>Email</dt>
        <dd>{{ auth.user.email }}</dd>
      </div>
      <div>
        <dt>註冊時間</dt>
        <dd>{{ formatDateTime(auth.user.createdAt) }}</dd>
      </div>
    </dl>

    <p class="account__links">
      <RouterLink :to="{ name: 'orders' }">我的訂單</RouterLink>
    </p>

    <h2>安全性</h2>
    <p class="account__hint">
      登出所有裝置會讓這個帳號在每一台裝置上都需要重新登入，包含目前這台。
      其他裝置已經取得的登入憑證可能還會在最多約 5 分鐘內有效。
    </p>

    <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>

    <div
      v-if="confirming"
      class="account__confirm"
      role="alertdialog"
      aria-label="確認登出所有裝置"
    >
      <p>確定要登出所有裝置嗎？</p>
      <button type="button" class="btn account__danger" :disabled="submitting" @click="logoutAll">
        {{ submitting ? '處理中…' : '確定登出' }}
      </button>
      <button
        type="button"
        class="account__link"
        :disabled="submitting"
        @click="confirming = false"
      >
        返回
      </button>
    </div>
    <button v-else type="button" class="btn account__danger" @click="confirming = true">
      登出所有裝置
    </button>
  </section>
</template>

<style scoped>
.account {
  max-width: 520px;
  margin: 0 auto;
}

.account__info {
  margin: 0 0 var(--space-4);
  padding: var(--space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.account__info div {
  display: flex;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-2) 0;
}

.account__info dt {
  color: var(--color-text-muted);
}

.account__info dd {
  margin: 0;
  overflow-wrap: anywhere;
  text-align: right;
}

.account__links a {
  color: var(--color-primary);
}

.account__hint {
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

.account__confirm {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
}

.account__confirm p {
  flex: 1 1 100%;
  margin: 0;
}

.account__danger {
  background: var(--color-danger);
}

.account__link {
  border: 0;
  background: none;
  color: var(--color-primary);
  font: inherit;
  text-decoration: underline;
  cursor: pointer;
}
</style>
