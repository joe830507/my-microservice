<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { registerErrorMessage, safeRedirect } from './messages'
import { useAuthStore } from './store'
import { validateDisplayName, validateEmail, validateNewPassword } from './validation'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const form = reactive({ email: '', password: '', displayName: '' })
const errors = reactive({ email: '', password: '', displayName: '' })
const submitting = ref(false)
const errorMessage = ref('')

function validate(): boolean {
  errors.email = validateEmail(form.email)
  errors.password = validateNewPassword(form.password)
  errors.displayName = validateDisplayName(form.displayName)
  return !errors.email && !errors.password && !errors.displayName
}

async function onSubmit(): Promise<void> {
  if (submitting.value || !validate()) {
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    await auth.register({
      email: form.email.trim(),
      password: form.password,
      displayName: form.displayName.trim(),
    })
    // 註冊成功就已經登入，回到原本要去的頁面
    await router.replace(safeRedirect(route.query.redirect))
  } catch (error) {
    errorMessage.value = registerErrorMessage(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="auth-card">
    <h1>註冊</h1>
    <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
    <form novalidate @submit.prevent="onSubmit">
      <div class="field">
        <label for="register-email">Email</label>
        <input
          id="register-email"
          v-model="form.email"
          name="email"
          type="email"
          autocomplete="username"
        />
        <p v-if="errors.email" class="field-error">{{ errors.email }}</p>
      </div>
      <div class="field">
        <label for="register-password">密碼（8–72 個字元）</label>
        <input
          id="register-password"
          v-model="form.password"
          name="password"
          type="password"
          autocomplete="new-password"
        />
        <p v-if="errors.password" class="field-error">{{ errors.password }}</p>
      </div>
      <div class="field">
        <label for="register-name">顯示名稱</label>
        <input
          id="register-name"
          v-model="form.displayName"
          name="displayName"
          type="text"
          autocomplete="nickname"
        />
        <p v-if="errors.displayName" class="field-error">{{ errors.displayName }}</p>
      </div>
      <button class="btn btn-block" type="submit" :disabled="submitting">註冊</button>
    </form>
    <p class="auth-switch">
      已經有帳號？
      <RouterLink :to="{ name: 'login', query: route.query }">登入</RouterLink>
    </p>
  </section>
</template>
