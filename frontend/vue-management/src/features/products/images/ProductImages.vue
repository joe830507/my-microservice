<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { confirmDanger } from '@/composables/useConfirm'
import * as imageApi from './api'
import { imageErrorMessage } from './messages'
import type { ProductImage } from './types'
import { ALLOWED_IMAGE_TYPES, moveItem, validateImageFile } from './validate'

const props = defineProps<{
  productId: string
  // 封存的商品不能修改圖片
  readOnly?: boolean
}>()

const images = ref<ProductImage[]>([])
const loading = ref(false)
const errorMessage = ref('')
// 上傳中顯示進度；同一時間只做一件事，避免排序和上傳互相覆蓋
const uploadPercent = ref<number | null>(null)
const busy = ref(false)

const hasPrimary = computed(() => images.value.some((image) => image.isPrimary))
const editable = computed(() => !props.readOnly && !busy.value)

const fileInput = ref<HTMLInputElement>()

async function load(): Promise<void> {
  loading.value = true
  try {
    images.value = await imageApi.getImages(props.productId)
  } catch (error) {
    errorMessage.value = imageErrorMessage(error)
  } finally {
    loading.value = false
  }
}

watch(() => props.productId, load, { immediate: true })

// 每個操作的共同處理：清掉舊錯誤、標記忙碌、失敗時顯示訊息
async function run(action: () => Promise<void>): Promise<void> {
  busy.value = true
  errorMessage.value = ''
  try {
    await action()
  } catch (error) {
    errorMessage.value = imageErrorMessage(error)
  } finally {
    busy.value = false
  }
}

function chooseFile(): void {
  fileInput.value?.click()
}

async function onFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 清空，下次選同一個檔案也會觸發 change
  input.value = ''
  if (!file) {
    return
  }

  const invalid = validateImageFile(file)
  if (invalid) {
    errorMessage.value = invalid
    return
  }

  await run(async () => {
    uploadPercent.value = 0
    try {
      await imageApi.uploadProductImage(props.productId, file, (percent) => {
        uploadPercent.value = percent
      })
    } finally {
      uploadPercent.value = null
    }
    ElMessage.success('圖片已上傳')
    // 重新讀取：第一張會自動成為主圖，順序也以後端為準
    images.value = await imageApi.getImages(props.productId)
  })
}

async function onSetPrimary(image: ProductImage): Promise<void> {
  await run(async () => {
    images.value = await imageApi.setPrimaryImage(props.productId, image.id)
    ElMessage.success('已設為主圖')
  })
}

async function onDelete(image: ProductImage): Promise<void> {
  const confirmed = await confirmDanger(
    image.isPrimary ? '確定要刪除主圖嗎？刪除後要另外設定主圖才能上架。' : '確定要刪除這張圖片嗎？',
  )
  if (!confirmed) {
    return
  }
  await run(async () => {
    try {
      await imageApi.deleteImage(props.productId, image.id)
      ElMessage.success('圖片已刪除')
    } finally {
      // 成功或失敗都重新讀取，失敗可能是因為資料已被其他人改過
      images.value = await imageApi.getImages(props.productId)
    }
  })
}

// 先在畫面上換好順序，再送出；失敗時改回原本的順序
async function reorder(from: number, to: number): Promise<void> {
  if (from === to || to < 0 || to >= images.value.length) {
    return
  }
  const previous = images.value
  images.value = moveItem(previous, from, to)
  await run(async () => {
    try {
      images.value = await imageApi.reorderImages(
        props.productId,
        images.value.map((image) => image.id),
      )
    } catch (error) {
      images.value = previous
      throw error
    }
  })
}

// 拖曳排序（HTML5 drag and drop）；左右移動按鈕是不能拖曳時的替代方式
const draggingIndex = ref<number | null>(null)

function onDragStart(index: number, event: DragEvent): void {
  draggingIndex.value = index
  event.dataTransfer?.setData('text/plain', String(index))
}

async function onDrop(index: number): Promise<void> {
  const from = draggingIndex.value
  draggingIndex.value = null
  if (from !== null) {
    await reorder(from, index)
  }
}
</script>

<template>
  <section v-loading="loading" class="product-images">
    <div class="images-header">
      <h3 class="images-title">商品圖片</h3>
      <template v-if="!readOnly">
        <input
          ref="fileInput"
          type="file"
          class="file-input"
          :accept="ALLOWED_IMAGE_TYPES.join(',')"
          @change="onFileChange"
        />
        <el-button
          type="primary"
          class="upload-button"
          :disabled="!editable"
          :loading="uploadPercent !== null"
          @click="chooseFile"
        >
          {{ uploadPercent !== null ? `上傳中 ${uploadPercent}%` : '上傳圖片' }}
        </el-button>
        <span class="images-hint">JPEG、PNG、WebP，最大 5 MB；拖曳圖片可以調整順序</span>
      </template>
    </div>

    <el-alert
      v-if="errorMessage"
      class="image-error"
      type="error"
      :title="errorMessage"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="images.length > 0 && !hasPrimary"
      class="no-primary-notice"
      type="warning"
      title="還沒有主圖，上架前要先設定主圖"
      :closable="false"
      show-icon
    />

    <el-empty v-if="!loading && images.length === 0" description="還沒有圖片" :image-size="80" />

    <ul v-else class="image-list">
      <li
        v-for="(image, index) in images"
        :key="image.id"
        class="image-card"
        :class="{ dragging: draggingIndex === index }"
        :draggable="editable"
        @dragstart="onDragStart(index, $event)"
        @dragover.prevent
        @drop.prevent="onDrop(index)"
        @dragend="draggingIndex = null"
      >
        <img :src="image.url" :alt="image.altText ?? ''" class="image-thumb" loading="lazy" />
        <div class="image-meta">
          <el-tag v-if="image.isPrimary" type="success" size="small" class="primary-tag">
            主圖
          </el-tag>
          <span class="image-position">第 {{ index + 1 }} 張</span>
        </div>
        <div v-if="!readOnly" class="image-actions">
          <el-button
            size="small"
            class="move-left-button"
            :disabled="!editable || index === 0"
            aria-label="往前移"
            @click="reorder(index, index - 1)"
          >
            ←
          </el-button>
          <el-button
            size="small"
            class="move-right-button"
            :disabled="!editable || index === images.length - 1"
            aria-label="往後移"
            @click="reorder(index, index + 1)"
          >
            →
          </el-button>
          <el-button
            size="small"
            class="primary-button"
            :disabled="!editable || image.isPrimary"
            @click="onSetPrimary(image)"
          >
            設為主圖
          </el-button>
          <el-button
            size="small"
            type="danger"
            plain
            class="delete-button"
            :disabled="!editable"
            @click="onDelete(image)"
          >
            刪除
          </el-button>
        </div>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.images-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.images-title {
  margin: 0;
  font-size: 16px;
}

.images-hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.file-input {
  display: none;
}

.image-error,
.no-primary-notice {
  margin-bottom: 16px;
}

.image-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 16px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.image-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-bg-color);
}

.image-card[draggable='true'] {
  cursor: grab;
}

.image-card.dragging {
  opacity: 0.5;
}

.image-thumb {
  width: 100%;
  aspect-ratio: 1;
  object-fit: cover;
  border-radius: 4px;
  background: var(--el-fill-color-light);
}

.image-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.image-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.image-actions .el-button + .el-button {
  margin-left: 0;
}
</style>
