<!-- 後端回 403 時可以導到這裡；前端的角色檢查只在登入時做（非 ADMIN 不能登入） -->
<script setup lang="ts"></script>

<template>
  <main class="status-page">
    <el-result icon="error" title="403" sub-title="你沒有權限查看這個頁面">
      <template #extra>
        <el-button type="primary" @click="$router.push({ name: 'home' })">回首頁</el-button>
      </template>
    </el-result>
  </main>
</template>

<style scoped>
.status-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
}
</style>
