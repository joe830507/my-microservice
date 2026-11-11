<script setup lang="ts"></script>

<template>
  <RouterView />
</template>

<style>
/* 全站基本樣式：拿掉瀏覽器預設的 body 外距，背景、文字用主題變數（src/styles/theme.css） */
body {
  margin: 0;
  background: var(--el-bg-color-page);
  color: var(--el-text-color-primary);
  font-size: var(--el-font-size-base);
  font-family:
    -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Noto Sans TC', 'Microsoft JhengHei', sans-serif;
}
</style>
