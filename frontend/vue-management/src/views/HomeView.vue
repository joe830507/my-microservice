<!-- 後台首頁：目前只有歡迎訊息；之後可以放待出貨訂單數之類的摘要 -->
<script setup lang="ts">
import { useAuthStore } from '@/features/auth/store'

const auth = useAuthStore()
</script>

<template>
  <el-card>
    <h2 class="home-title">歡迎回來{{ auth.user ? `，${auth.user.displayName}` : '' }}</h2>
    <p>從左側選單選擇要管理的項目。</p>
  </el-card>
</template>

<style scoped>
.home-title {
  margin-top: 0;
}
</style>
