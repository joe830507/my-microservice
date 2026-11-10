<script setup lang="ts">
import type { FormInstance, FormItemRule, FormRules, TreeNodeData } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { confirmDanger } from '@/composables/useConfirm'
import { getAllCategories } from '@/features/categories/api'
import { buildTree } from '@/features/categories/tree'
import type { Category, CategoryNode } from '@/features/categories/types'
import * as productApi from './api'
import type { StatusAction } from './api'
import ProductImages from './images/ProductImages.vue'
import ProductInventory from './inventory/ProductInventory.vue'
import { productErrorMessage } from './messages'
import {
  canArchive,
  canEdit,
  canPublish,
  canUnpublish,
  STATUS_LABELS,
  STATUS_TAG_TYPES,
} from './status'
import type { Product, ProductReq } from './types'

const route = useRoute()
const router = useRouter()

// /products/new 是新增，/products/:id 是編輯
const productId = computed(() =>
  typeof route.params.id === 'string' && route.params.id !== '' ? route.params.id : null,
)

const product = ref<Product | null>(null)
const categories = ref<Category[]>([])
const loading = ref(false)
const loadError = ref('')
const saving = ref(false)
const changingStatus = ref<StatusAction | null>(null)
// 儲存、上下架失敗的訊息
const errorMessage = ref('')

const readOnly = computed(() => product.value !== null && !canEdit(product.value.status))

// 和後端 NUMERIC(12,2) 一致
const MAX_PRICE = 9999999999.99

const formRef = ref<FormInstance>()
const form = reactive({
  sku: '',
  name: '',
  categoryId: null as string | null,
  brand: '',
  price: null as number | null,
  originalPrice: null as number | null,
  description: '',
})

function fillForm(source: Product | null): void {
  form.sku = source?.sku ?? ''
  form.name = source?.name ?? ''
  form.categoryId = source?.categoryId ?? null
  form.brand = source?.brand ?? ''
  form.price = source?.price ?? null
  form.originalPrice = source?.originalPrice ?? null
  form.description = source?.description ?? ''
}

const originalPriceRule: FormItemRule = {
  validator: (_rule, value: number | null | undefined, callback) => {
    if (value !== null && value !== undefined && form.price !== null && value < form.price) {
      callback(new Error('原價不能低於售價'))
    } else {
      callback()
    }
  },
  trigger: 'change',
}

const rules: FormRules<typeof form> = {
  sku: [
    { required: true, whitespace: true, message: '請輸入 SKU', trigger: 'blur' },
    { max: 64, message: 'SKU 最多 64 個字', trigger: 'blur' },
  ],
  name: [
    { required: true, whitespace: true, message: '請輸入名稱', trigger: 'blur' },
    { max: 255, message: '名稱最多 255 個字', trigger: 'blur' },
  ],
  brand: [{ max: 100, message: '品牌最多 100 個字', trigger: 'blur' }],
  price: [{ required: true, type: 'number', message: '請輸入售價', trigger: 'change' }],
  originalPrice: [originalPriceRule],
}

// 分類選單：停用的分類不能選（後端回 CATEGORY_INACTIVE），但仍顯示，已選到的才看得到名稱
const categoryTree = computed(() => buildTree(categories.value))
const categoryProps = {
  label: 'name',
  children: 'children',
  // Element Plus 的型別是通用的 TreeNodeData，這裡的節點實際上是 CategoryNode
  disabled: (data: TreeNodeData) => !(data as CategoryNode).active,
}

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  errorMessage.value = ''
  try {
    const [loadedCategories, loadedProduct] = await Promise.all([
      getAllCategories(),
      productId.value ? productApi.getProduct(productId.value) : Promise.resolve(null),
    ])
    categories.value = loadedCategories
    product.value = loadedProduct
    fillForm(loadedProduct)
  } catch (error) {
    loadError.value = productErrorMessage(error)
  } finally {
    loading.value = false
  }
}

watch(productId, load, { immediate: true })

function toRequest(): ProductReq {
  const brand = form.brand.trim()
  const description = form.description.trim()
  return {
    sku: form.sku.trim(),
    name: form.name.trim(),
    description: description === '' ? null : description,
    categoryId: form.categoryId || null,
    brand: brand === '' ? null : brand,
    price: form.price ?? 0,
    originalPrice: form.originalPrice ?? null,
  }
}

async function onSave(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }

  saving.value = true
  errorMessage.value = ''
  try {
    if (product.value) {
      product.value = await productApi.updateProduct(product.value.id, {
        ...toRequest(),
        version: product.value.version,
      })
      fillForm(product.value)
      ElMessage.success('商品已更新')
    } else {
      const created = await productApi.createProduct(toRequest())
      ElMessage.success('商品已新增')
      // 新增後進入編輯頁：之後要在這裡上傳圖片、設定庫存、上架
      await router.replace({ name: 'product-edit', params: { id: created.id } })
    }
  } catch (error) {
    errorMessage.value = productErrorMessage(error)
  } finally {
    saving.value = false
  }
}

const STATUS_ACTION_MESSAGES: Record<StatusAction, string> = {
  publish: '商品已上架',
  unpublish: '商品已下架',
  archive: '商品已封存',
}

async function onChangeStatus(action: StatusAction): Promise<void> {
  const current = product.value
  if (!current) {
    return
  }
  if (
    action === 'archive' &&
    !(await confirmDanger(`確定要封存「${current.name}」嗎？封存後不能再修改或上架。`))
  ) {
    return
  }

  changingStatus.value = action
  errorMessage.value = ''
  try {
    const changed = await productApi.changeProductStatus(current.id, action, current.version)
    product.value = {
      ...current,
      status: changed.status,
      version: changed.version,
      updatedAt: changed.updatedAt,
    }
    ElMessage.success(STATUS_ACTION_MESSAGES[action])
  } catch (error) {
    errorMessage.value = productErrorMessage(error)
  } finally {
    changingStatus.value = null
  }
}

// 表單有沒有未儲存的修改：上架前提醒（上架檢查的是已儲存的內容）
const dirty = computed(() => {
  if (!product.value) {
    return false
  }
  const saved = product.value
  const current = toRequest()
  return (
    current.sku !== saved.sku ||
    current.name !== saved.name ||
    current.description !== (saved.description ?? null) ||
    current.categoryId !== (saved.categoryId ?? null) ||
    current.brand !== (saved.brand ?? null) ||
    current.price !== saved.price ||
    current.originalPrice !== (saved.originalPrice ?? null)
  )
})
</script>

<template>
  <el-card v-loading="loading">
    <template #header>
      <div class="header">
        <h2 class="title">{{ productId ? '編輯商品' : '新增商品' }}</h2>
        <div v-if="product" class="status-bar">
          <el-tag class="status-tag" :type="STATUS_TAG_TYPES[product.status]">
            {{ STATUS_LABELS[product.status] }}
          </el-tag>
          <el-button
            class="publish-button"
            type="success"
            :disabled="!canPublish(product.status) || changingStatus !== null"
            :loading="changingStatus === 'publish'"
            @click="onChangeStatus('publish')"
          >
            上架
          </el-button>
          <el-button
            class="unpublish-button"
            type="warning"
            :disabled="!canUnpublish(product.status) || changingStatus !== null"
            :loading="changingStatus === 'unpublish'"
            @click="onChangeStatus('unpublish')"
          >
            下架
          </el-button>
          <el-button
            class="archive-button"
            type="danger"
            plain
            :disabled="!canArchive(product.status) || changingStatus !== null"
            :loading="changingStatus === 'archive'"
            @click="onChangeStatus('archive')"
          >
            封存
          </el-button>
        </div>
      </div>
    </template>

    <el-alert
      v-if="loadError"
      class="load-error"
      type="error"
      :title="`無法讀取商品：${loadError}`"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="readOnly"
      class="archived-notice"
      type="info"
      title="商品已封存，不能修改"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="dirty && product && canPublish(product.status)"
      class="dirty-notice"
      type="warning"
      title="有尚未儲存的修改；上架檢查的是已儲存的內容"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="errorMessage"
      class="form-error"
      type="error"
      :title="errorMessage"
      :closable="false"
      show-icon
    />

    <el-form
      v-if="!loadError"
      ref="formRef"
      :model="form"
      :rules="rules"
      :disabled="readOnly"
      label-width="96px"
      class="product-form"
      @submit.prevent="onSave"
    >
      <el-form-item label="SKU" prop="sku">
        <el-input v-model="form.sku" name="sku" maxlength="64" />
      </el-form-item>
      <el-form-item label="名稱" prop="name">
        <el-input v-model="form.name" name="name" maxlength="255" />
      </el-form-item>
      <el-form-item label="分類" prop="categoryId">
        <el-tree-select
          v-model="form.categoryId"
          :data="categoryTree"
          :props="categoryProps"
          node-key="id"
          check-strictly
          default-expand-all
          clearable
          placeholder="草稿可以先不選，上架前要選"
          class="full-width"
        />
      </el-form-item>
      <el-form-item label="品牌" prop="brand">
        <el-input v-model="form.brand" name="brand" maxlength="100" />
      </el-form-item>
      <el-form-item label="售價" prop="price">
        <el-input-number
          v-model="form.price"
          name="price"
          :min="0"
          :max="MAX_PRICE"
          :precision="2"
          :controls="false"
          :value-on-clear="null"
        />
      </el-form-item>
      <el-form-item label="原價" prop="originalPrice">
        <el-input-number
          v-model="form.originalPrice"
          name="originalPrice"
          :min="0"
          :max="MAX_PRICE"
          :precision="2"
          :controls="false"
          :value-on-clear="null"
          placeholder="沒有就留空"
        />
      </el-form-item>
      <el-form-item label="說明" prop="description">
        <el-input v-model="form.description" name="description" type="textarea" :rows="5" />
      </el-form-item>
      <el-form-item v-if="!readOnly">
        <el-button type="primary" native-type="submit" :loading="saving" class="save-button">
          {{ product ? '儲存' : '新增' }}
        </el-button>
        <el-button @click="router.push({ name: 'products' })">回列表</el-button>
      </el-form-item>
    </el-form>

    <!-- 圖片要等商品建立後才能上傳 -->
    <template v-if="product">
      <el-divider />
      <ProductImages :product-id="product.id" :read-only="readOnly" />
      <el-divider />
      <ProductInventory :sku="product.sku" :read-only="readOnly" />
    </template>
  </el-card>
</template>

<style scoped>
.header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.title {
  margin: 0;
  font-size: var(--el-font-size-large);
}

.status-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.status-tag {
  margin-right: 4px;
}

.load-error,
.archived-notice,
.dirty-notice,
.form-error {
  margin-bottom: 16px;
}

.product-form {
  max-width: 640px;
}

.full-width {
  width: 100%;
}
</style>
