// 對應 product-service 的商品圖片 DTO

export interface ProductImage {
  id: string
  // 公開網址
  url: string
  altText: string | null
  sortOrder: number
  isPrimary: boolean
  version: number
}

// 查詢、設定主圖、調整排序共用的回應；images 的順序就是顯示順序
export interface ProductImagesRes {
  productId: string
  images: ProductImage[]
}

export interface CreateUploadUrlReq {
  contentType: string
  // bytes，會簽進上傳網址
  contentLength: number
}

export interface CreateUploadUrlRes {
  // 上傳完成後，用這個 key 登記圖片
  objectKey: string
  // 用 PUT 直接上傳到物件儲存
  uploadUrl: string
  // PUT 時必須帶上的 header（Content-Type、Content-Length），否則簽章不符
  requiredHeaders: Record<string, string>
  expiresAt: string
}
