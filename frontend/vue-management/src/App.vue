<script setup lang="ts"></script>

<template>
  <RouterView />
</template>

<style>
/* 全站基本樣式：拿掉瀏覽器預設的 body 外距，背景交給 Element Plus 的色彩變數 */
body {
  margin: 0;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  font-family:
    -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Noto Sans TC', 'Microsoft JhengHei', sans-serif;
}
</style>
