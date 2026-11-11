<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import ProductListing from './ProductListing.vue'

// /search?q=：沒有關鍵字時列出全部商品
const route = useRoute()
const keyword = computed(() => {
  const q = Array.isArray(route.query.q) ? route.query.q[0] : route.query.q
  return (q ?? '').trim()
})
</script>

<template>
  <section>
    <h1>{{ keyword ? `搜尋「${keyword}」` : '全部商品' }}</h1>
    <ProductListing />
  </section>
</template>
