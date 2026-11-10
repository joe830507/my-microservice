<!-- 暫時的首頁：Step 4 做 AdminLayout 時換掉（登出按鈕移到頂部列） -->
<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/features/auth/store'

const auth = useAuthStore()
const router = useRouter()

async function onLogout(): Promise<void> {
  await auth.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <main>
    <h1>vue-management</h1>
    <p v-if="auth.user">{{ auth.user.displayName }}（{{ auth.user.email }}）</p>
    <el-button type="primary" @click="onLogout">登出</el-button>
  </main>
</template>
