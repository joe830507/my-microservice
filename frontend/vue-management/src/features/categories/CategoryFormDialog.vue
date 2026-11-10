<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus'
import { computed, reactive, ref } from 'vue'
import * as categoryApi from './api'
import { saveErrorMessage } from './messages'
import { buildTree, subtreeIds } from './tree'
import type { Category, CategoryReq } from './types'

const props = defineProps<{
  // 所有分類，用來選上層分類
  categories: Category[]
  // 有值是修改，沒有是新增
  category?: Category
  // 新增時預設的上層分類（從某一列按「新增子分類」）
  defaultParentId?: string | null
}>()

const emit = defineEmits<{
  saved: [message: string]
  close: []
}>()

const isEdit = computed(() => props.category !== undefined)

// 和後端 @Pattern 一致：小寫英數字，以單一連字號分隔
const SLUG_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/

const formRef = ref<FormInstance>()
const form = reactive({
  parentId: props.category?.parentId ?? props.defaultParentId ?? null,
  name: props.category?.name ?? '',
  slug: props.category?.slug ?? '',
  description: props.category?.description ?? '',
  sortOrder: props.category?.sortOrder ?? 0,
  active: props.category?.active ?? true,
})

const rules: FormRules<typeof form> = {
  name: [
    { required: true, whitespace: true, message: '請輸入名稱', trigger: 'blur' },
    { max: 100, message: '名稱最多 100 個字', trigger: 'blur' },
  ],
  slug: [
    { required: true, message: '請輸入 slug', trigger: 'blur' },
    { max: 100, message: 'slug 最多 100 個字', trigger: 'blur' },
    {
      pattern: SLUG_PATTERN,
      message: '只能用小寫英文、數字，以單一連字號分隔，例如 mens-shoes',
      trigger: 'blur',
    },
  ],
  sortOrder: [{ required: true, type: 'number', min: 0, message: '排序不能小於 0' }],
}

// 上層分類的選項：修改時排除自己與自己的子孫（後端也會擋，回 CATEGORY_PARENT_INVALID）
const parentOptions = computed(() => {
  const excluded = props.category
    ? subtreeIds(props.categories, props.category.id)
    : new Set<string>()
  return buildTree(props.categories.filter((category) => !excluded.has(category.id)))
})

const submitting = ref(false)
const errorMessage = ref('')

function toRequest(): CategoryReq {
  const description = form.description.trim()
  return {
    // el-tree-select 清除後可能是 '' 或 undefined
    parentId: form.parentId || null,
    name: form.name.trim(),
    slug: form.slug.trim(),
    description: description === '' ? null : description,
    sortOrder: form.sortOrder,
    active: form.active,
  }
}

async function onSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }

  submitting.value = true
  errorMessage.value = ''
  try {
    if (props.category) {
      await categoryApi.updateCategory(props.category.id, {
        ...toRequest(),
        version: props.category.version,
      })
      emit('saved', '分類已更新')
    } else {
      await categoryApi.createCategory(toRequest())
      emit('saved', '分類已新增')
    }
  } catch (error) {
    errorMessage.value = saveErrorMessage(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="true"
    :title="isEdit ? '編輯分類' : '新增分類'"
    width="min(520px, calc(100vw - 32px))"
    :close-on-click-modal="false"
    @close="emit('close')"
  >
    <el-alert
      v-if="errorMessage"
      class="form-error"
      type="error"
      :title="errorMessage"
      :closable="false"
      show-icon
    />

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="96px"
      @submit.prevent="onSubmit"
    >
      <el-form-item label="上層分類" prop="parentId">
        <el-tree-select
          v-model="form.parentId"
          :data="parentOptions"
          :props="{ label: 'name', children: 'children' }"
          node-key="id"
          check-strictly
          default-expand-all
          clearable
          placeholder="不選就是頂層分類"
          class="full-width"
        />
      </el-form-item>
      <el-form-item label="名稱" prop="name">
        <el-input v-model="form.name" name="name" maxlength="100" />
      </el-form-item>
      <el-form-item label="slug" prop="slug">
        <el-input v-model="form.slug" name="slug" maxlength="100" placeholder="例如 mens-shoes" />
      </el-form-item>
      <el-form-item label="說明" prop="description">
        <el-input v-model="form.description" name="description" type="textarea" :rows="3" />
      </el-form-item>
      <el-form-item label="排序" prop="sortOrder">
        <el-input-number
          v-model="form.sortOrder"
          name="sortOrder"
          :min="0"
          :step="1"
          step-strictly
        />
      </el-form-item>
      <el-form-item label="啟用" prop="active">
        <el-switch v-model="form.active" name="active" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="submitting" class="submit-button" @click="onSubmit">
        儲存
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.form-error {
  margin-bottom: 16px;
}

.full-width {
  width: 100%;
}
</style>
