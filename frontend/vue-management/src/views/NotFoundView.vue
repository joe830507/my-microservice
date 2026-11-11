<script setup lang="ts"></script>

<template>
  <main class="status-page">
    <el-result icon="warning" title="404" sub-title="找不到這個頁面">
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
