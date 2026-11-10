import axios from 'axios'
import { ApiError, toApiError } from '@/api/errors'
import apiClient from '@/api/http'
import type {
  CreateUploadUrlReq,
  CreateUploadUrlRes,
  ProductImage,
  ProductImagesRes,
} from './types'

function base(productId: string): string {
  return `/products/${productId}/images`
}

export async function getImages(productId: string): Promise<ProductImage[]> {
  const { data } = await apiClient.get<ProductImagesRes>(base(productId))
  return data.images
}

export async function createUploadUrl(
  productId: string,
  req: CreateUploadUrlReq,
): Promise<CreateUploadUrlRes> {
  const { data } = await apiClient.post<CreateUploadUrlRes>(`${base(productId)}/upload-url`, req)
  return data
}

// 檔案已經 PUT 到物件儲存之後才登記；第一張圖片會自動成為主圖
export async function registerImage(productId: string, objectKey: string): Promise<ProductImage> {
  const { data } = await apiClient.post<ProductImage>(base(productId), {
    objectKey,
    altText: null,
  })
  return data
}

export async function deleteImage(productId: string, imageId: string): Promise<void> {
  await apiClient.delete(`${base(productId)}/${imageId}`)
}

export async function setPrimaryImage(productId: string, imageId: string): Promise<ProductImage[]> {
  const { data } = await apiClient.post<ProductImagesRes>(`${base(productId)}/${imageId}/primary`)
  return data.images
}

// 必須包含這個商品所有圖片的 id，順序就是新的排序
export async function reorderImages(
  productId: string,
  imageIds: string[],
): Promise<ProductImage[]> {
  const { data } = await apiClient.put<ProductImagesRes>(`${base(productId)}/order`, { imageIds })
  return data.images
}

// 上傳到物件儲存用另一個 axios 實例：
// - 不能帶 Authorization（不是我們的 API），也不能帶 credentials（SeaweedFS 回 Allow-Origin: *）
// - 網址是完整的 presigned URL，不套用 baseURL
// 匯出給測試 spy：vitest 4.1 的 jsdom 橋接無法把 Blob、File 當成請求 body（MSW 會失敗），
// 所以上傳本體在測試裡直接攔 storageClient.put，其他 API 照樣用 MSW
export const storageClient = axios.create({ withCredentials: false })

// Content-Length 是瀏覽器禁止手動設定的 header，會依 body 自動帶上；簽章裡的值和檔案大小一致即可
const BROWSER_MANAGED_HEADERS = ['content-length', 'host']

export async function putToStorage(
  upload: CreateUploadUrlRes,
  file: File,
  onProgress?: (percent: number) => void,
): Promise<void> {
  const headers = Object.fromEntries(
    Object.entries(upload.requiredHeaders).filter(
      ([name]) => !BROWSER_MANAGED_HEADERS.includes(name.toLowerCase()),
    ),
  )
  try {
    await storageClient.put(upload.uploadUrl, file, {
      headers,
      onUploadProgress: (event) => {
        if (onProgress && event.total) {
          onProgress(Math.round((event.loaded / event.total) * 100))
        }
      },
    })
  } catch (error) {
    // 物件儲存回的是 S3 的 XML 錯誤，不是 ProblemDetail；統一成一個前端的錯誤代碼
    const cause = toApiError(error)
    throw new ApiError(cause.status, 'IMAGE_UPLOAD_FAILED', '圖片上傳失敗，請重新上傳', error)
  }
}

// 完整的上傳流程：申請上傳網址 → PUT 到物件儲存 → 登記
// PUT 失敗就不登記，避免留下指向不存在檔案的圖片
export async function uploadProductImage(
  productId: string,
  file: File,
  onProgress?: (percent: number) => void,
): Promise<ProductImage> {
  const upload = await createUploadUrl(productId, {
    contentType: file.type,
    contentLength: file.size,
  })
  await putToStorage(upload, file, onProgress)
  return registerImage(productId, upload.objectKey)
}
