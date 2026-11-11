<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { toApiError } from '@/api/errors'
import { confirmDanger } from '@/composables/useConfirm'
import * as categoryApi from './api'
import CategoryFormDialog from './CategoryFormDialog.vue'
import { deleteErrorMessage } from './messages'
import { buildTree } from './tree'
import type { Category, CategoryNode } from './types'

const categories = ref<Category[]>([])
const tree = computed(() => buildTree(categories.value))
const loading = ref(false)
const loadError = ref('')

// 表單對話框的狀態；null 代表關閉
interface DialogState {
  category?: Category
  defaultParentId?: string | null
}
const dialog = ref<DialogState | null>(null)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    categories.value = await categoryApi.getAllCategories()
  } catch (error) {
    loadError.value = toApiError(error).detail
  } finally {
    loading.value = false
  }
}

function openCreate(parent?: Category): void {
  dialog.value = { defaultParentId: parent?.id ?? null }
}

function openEdit(category: Category): void {
  dialog.value = { category }
}

async function onSaved(message: string): Promise<void> {
  dialog.value = null
  ElMessage.success(message)
  await load()
}

async function onDelete(category: Category): Promise<void> {
  const confirmed = await confirmDanger(`確定要刪除「${category.name}」嗎？刪除後無法復原。`)
  if (!confirmed) {
    return
  }
  try {
    await categoryApi.deleteCategory(category.id)
    ElMessage.success('分類已刪除')
  } catch (error) {
    ElMessage.error(deleteErrorMessage(error))
  }
  // 成功或失敗都重新讀取：失敗可能是因為資料已被其他人改過
  await load()
}

onMounted(load)
</script>

<template>
  <el-card>
    <div class="toolbar">
      <el-button type="primary" class="create-button" @click="openCreate()">新增分類</el-button>
      <el-button :loading="loading" @click="load">重新整理</el-button>
    </div>

    <el-alert
      v-if="loadError"
      class="load-error"
      type="error"
      :title="`無法讀取分類：${loadError}`"
      :closable="false"
      show-icon
    />

    <el-table
      v-loading="loading"
      :data="tree"
      row-key="id"
      default-expand-all
      empty-text="還沒有分類"
    >
      <el-table-column prop="name" label="名稱" min-width="180" />
      <el-table-column prop="slug" label="slug" min-width="160" />
      <el-table-column prop="sortOrder" label="排序" width="80" align="right" />
      <el-table-column label="狀態" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.active" type="success">啟用</el-tag>
          <el-tag v-else type="info">停用</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <!-- el-table 的 row 型別是通用的 DefaultRow，呼叫時轉成實際的型別 -->
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            class="create-child-button"
            @click="openCreate(row as CategoryNode)"
          >
            新增子分類
          </el-button>
          <el-button link type="primary" class="edit-button" @click="openEdit(row as CategoryNode)"
            >編輯</el-button
          >
          <el-button
            link
            type="danger"
            class="delete-button"
            @click="onDelete(row as CategoryNode)"
          >
            刪除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <CategoryFormDialog
      v-if="dialog"
      :categories="categories"
      :category="dialog.category"
      :default-parent-id="dialog.defaultParentId"
      @saved="onSaved"
      @close="dialog = null"
    />
  </el-card>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.load-error {
  margin-bottom: 16px;
}
</style>
