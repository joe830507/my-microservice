<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { loginErrorMessage, safeRedirect } from './messages'
import { useAuthStore } from './store'
import { validateEmail, validateLoginPassword } from './validation'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const form = reactive({ email: '', password: '' })
const errors = reactive({ email: '', password: '' })
const submitting = ref(false)
const errorMessage = ref('')

function validate(): boolean {
  errors.email = validateEmail(form.email)
  errors.password = validateLoginPassword(form.password)
  return !errors.email && !errors.password
}

async function onSubmit(): Promise<void> {
  // 送出中不再送第二次
  if (submitting.value || !validate()) {
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
  <section class="auth-card">
    <h1>登入</h1>
    <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
    <form novalidate @submit.prevent="onSubmit">
      <div class="field">
        <label for="login-email">Email</label>
        <input
          id="login-email"
          v-model="form.email"
          name="email"
          type="email"
          autocomplete="username"
        />
        <p v-if="errors.email" class="field-error">{{ errors.email }}</p>
      </div>
      <div class="field">
        <label for="login-password">密碼</label>
        <input
          id="login-password"
          v-model="form.password"
          name="password"
          type="password"
          autocomplete="current-password"
        />
        <p v-if="errors.password" class="field-error">{{ errors.password }}</p>
      </div>
      <button class="btn btn-block" type="submit" :disabled="submitting">登入</button>
    </form>
    <p class="auth-switch">
      還沒有帳號？
      <RouterLink :to="{ name: 'register', query: route.query }">註冊</RouterLink>
    </p>
  </section>
</template>
