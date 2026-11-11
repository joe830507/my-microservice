<script setup lang="ts"></script>

<template>
  <section class="not-found">
    <h1>404</h1>
    <p>找不到這個頁面</p>
    <RouterLink :to="{ name: 'home' }" class="btn">回首頁</RouterLink>
  </section>
</template>

<style scoped>
.not-found {
  padding: var(--space-8) 0;
  text-align: center;
}
</style>
