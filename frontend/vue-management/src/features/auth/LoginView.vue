<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
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
    <el-card class="login-card">
      <template #header>
        <h1 class="login-title">vue-management 後台登入</h1>
      </template>

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
        @submit.prevent="onSubmit"
      >
        <el-form-item label="Email" prop="email">
          <el-input v-model="form.email" name="email" type="email" autocomplete="username" />
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
        <el-button type="primary" native-type="submit" :loading="submitting" class="login-submit">
          登入
        </el-button>
      </el-form>
    </el-card>
  </main>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 16px;
  box-sizing: border-box;
}

.login-card {
  width: 100%;
  max-width: 400px;
}

.login-title {
  margin: 0;
  font-size: 18px;
}

.login-error {
  margin-bottom: 16px;
}

.login-submit {
  width: 100%;
}
</style>
