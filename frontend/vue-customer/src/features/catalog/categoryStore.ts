import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as catalogApi from './api'
import type { CategoryTreeNode } from './types'

// 分類樹：頂部列、首頁、分類頁都會用，載入一次就共用；後端另外有快取
export const useCategoryStore = defineStore('categories', () => {
  const tree = ref<CategoryTreeNode[]>([])
  const loaded = ref(false)
  let loading: Promise<void> | null = null

  // 同時多個元件呼叫只發一次請求；失敗時下次呼叫會重試，頂部列等次要的地方不會因此壞掉
  function load(): Promise<void> {
    if (loaded.value) {
      return Promise.resolve()
    }
    loading ??= catalogApi
      .getCategoryTree()
      .then((roots) => {
        tree.value = roots
        loaded.value = true
      })
      .catch(() => {
        // 保持空的樹：分類入口不出現，商品照常可以搜尋、瀏覽
      })
      .finally(() => {
        loading = null
      })
    return loading
  }

  // 依 slug 在整棵樹找節點（含子孫）
  function findBySlug(slug: string): CategoryTreeNode | null {
    function search(nodes: CategoryTreeNode[]): CategoryTreeNode | null {
      for (const node of nodes) {
        if (node.slug === slug) return node
        const found = search(node.children)
        if (found) return found
      }
      return null
    }
    return search(tree.value)
  }

  return { tree, loaded, load, findBySlug }
})
