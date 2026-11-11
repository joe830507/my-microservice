<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus'
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { APP_TITLE } from '@/router'
import { loginErrorMessage, safeRedirect } from './messages'
import { useAuthStore } from './store'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const formRef = ref<FormInstance>()
const form = reactive({ email: '', password: '' })
const rules: FormRules<typeof form> = {
  email: [
    { required: true, message: '請輸入 Email', trigger: 'blur' },
    { type: 'email', message: 'Email 格式不正確', trigger: 'blur' },
  ],
  password: [{ required: true, message: '請輸入密碼', trigger: 'blur' }],
}

// Email 看起來格式正確時，在欄位右邊顯示勾號（只是提示，真正的檢查在送出時）
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const emailLooksValid = computed(() => EMAIL_PATTERN.test(form.email.trim()))

const submitting = ref(false)
const errorMessage = ref('')

async function onSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }

  submitting.value = true
  errorMessage.value = ''
  try {
    await auth.login({ email: form.email.trim(), password: form.password })
    await router.replace(safeRedirect(route.query.redirect))
  } catch (error) {
    errorMessage.value = loginErrorMessage(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-card">
      <!-- 左上角的裝飾葉片 -->
      <svg class="login-leaves" viewBox="0 0 220 220" aria-hidden="true">
        <defs>
          <linearGradient id="leaf-blue" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stop-color="#3b5bdb" />
            <stop offset="100%" stop-color="#4c6ef5" stop-opacity="0.75" />
          </linearGradient>
          <linearGradient id="leaf-teal" x1="0" y1="1" x2="1" y2="0">
            <stop offset="0%" stop-color="#0ca678" />
            <stop offset="100%" stop-color="#22d3ee" stop-opacity="0.85" />
          </linearGradient>
        </defs>
        <path d="M0 0 H150 C150 85 85 150 0 150 Z" fill="url(#leaf-blue)" opacity="0.85" />
        <path
          d="M0 40 C95 40 160 105 160 200 C65 200 0 135 0 40 Z"
          fill="url(#leaf-teal)"
          opacity="0.8"
        />
      </svg>

      <div class="login-content">
        <header class="login-brand">
          <div class="brand-row">
            <svg class="brand-mark" viewBox="0 0 32 32" aria-hidden="true">
              <path d="M4 28 C4 14 14 4 28 4 C28 18 18 28 4 28 Z" fill="#22d3ee" />
              <path d="M4 28 L20 12" stroke="#2f3145" stroke-width="2" stroke-linecap="round" />
            </svg>
            <span class="brand-name">{{ APP_TITLE }}</span>
          </div>
          <p class="brand-tagline">商品、訂單與庫存管理</p>
        </header>

        <h1 class="login-title">歡迎回來</h1>

        <el-alert
          v-if="errorMessage"
          class="login-error"
          type="error"
          :title="errorMessage"
          :closable="false"
          show-icon
          role="alert"
        />

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          class="login-form"
          @submit.prevent="onSubmit"
        >
          <el-form-item label="Email" prop="email">
            <el-input v-model="form.email" name="email" type="email" autocomplete="username">
              <template #suffix>
                <svg
                  v-if="emailLooksValid"
                  class="email-check"
                  viewBox="0 0 16 16"
                  aria-label="Email 格式正確"
                >
                  <path
                    d="M3 8.5 L6.5 12 L13 4.5"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  />
                </svg>
              </template>
            </el-input>
          </el-form-item>
          <el-form-item label="密碼" prop="password">
            <el-input
              v-model="form.password"
              name="password"
              type="password"
              autocomplete="current-password"
              show-password
            />
          </el-form-item>
          <el-button native-type="submit" :loading="submitting" class="login-submit">
            登入
          </el-button>
        </el-form>
      </div>
    </section>
  </main>
</template>

<style scoped>
/* 登入頁固定使用深色設計，不跟隨系統的淺色、深色主題 */
.login-page {
  --login-bg: #3a3c52;
  --login-card: #44465e;
  --login-text: #f1f3f9;
  --login-muted: #b8bccd;
  --login-line: #8a8ea6;
  --login-accent: #22d3ee;
  --login-accent-hover: #67e8f9;
  --login-accent-text: #12304a;

  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 32px 16px;
  box-sizing: border-box;
  background: var(--login-bg);
  color: var(--login-text);
}

.login-card {
  position: relative;
  width: 100%;
  max-width: 920px;
  min-height: 560px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 24px;
  background: var(--login-card);
  box-shadow: 0 24px 60px rgb(0 0 0 / 35%);
}

.login-leaves {
  position: absolute;
  top: 0;
  left: 0;
  width: 200px;
  height: 200px;
  pointer-events: none;
}

.login-content {
  position: relative;
  width: 100%;
  max-width: 340px;
  padding: 48px 24px;
  text-align: center;
}

.brand-row {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.brand-mark {
  width: 32px;
  height: 32px;
}

.brand-name {
  font-size: 28px;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.brand-tagline {
  margin: 6px 0 0;
  font-size: var(--el-font-size-extra-small);
  color: var(--login-muted);
}

.login-title {
  margin: 36px 0 28px;
  font-size: 24px;
  font-weight: 500;
}

.login-error {
  margin-bottom: 16px;
  text-align: left;
}

.login-form {
  text-align: left;
}

/* 標籤：小字、淺色 */
.login-form :deep(.el-form-item__label) {
  padding-bottom: 2px;
  font-size: var(--el-font-size-extra-small);
  line-height: 18px;
  color: var(--login-text);
}

/* 輸入框改成只有底線 */
.login-form :deep(.el-input__wrapper) {
  padding: 0 2px;
  border-radius: 0;
  background: transparent;
  box-shadow: 0 1px 0 0 var(--login-line);
}

.login-form :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 2px 0 0 var(--login-accent);
}

.login-form :deep(.el-form-item.is-error .el-input__wrapper) {
  box-shadow: 0 2px 0 0 var(--el-color-danger);
}

.login-form :deep(.el-input__inner) {
  color: var(--login-text);
}

.login-form :deep(.el-input__inner::placeholder) {
  color: var(--login-muted);
}

/* 密碼的顯示切換（眼睛圖示）用強調色 */
.login-form :deep(.el-input__password),
.email-check {
  color: var(--login-accent);
}

.email-check {
  width: 16px;
  height: 16px;
}

.login-form :deep(.el-form-item) {
  margin-bottom: 22px;
}

.login-submit {
  width: 100%;
  height: 40px;
  margin-top: 8px;
  border: none;
  border-radius: 6px;
  font-weight: 600;
  color: var(--login-accent-text);
  background: var(--login-accent);
}

.login-submit:hover,
.login-submit:focus-visible {
  color: var(--login-accent-text);
  background: var(--login-accent-hover);
}

@media (max-width: 600px) {
  .login-page {
    padding: 16px;
  }

  .login-card {
    min-height: auto;
    border-radius: 16px;
  }

  .login-leaves {
    width: 120px;
    height: 120px;
  }

  .login-content {
    padding: 56px 16px 40px;
  }
}
</style>
