// 對應 product-service 的 CategoryListItem（GET /categories）與新增、修改的 request

export interface Category {
  id: string
  // null 代表頂層分類
  parentId: string | null
  name: string
  slug: string
  description: string | null
  sortOrder: number
  active: boolean
  createdAt: string
  updatedAt: string
  // 修改時要帶回，做樂觀鎖檢查
  version: number
}

// 前端依 parentId 組成的樹；沒有子分類時不放 children，表格才不會顯示展開箭頭
export interface CategoryNode extends Category {
  children?: CategoryNode[]
}

export interface CategoryReq {
  parentId: string | null
  name: string
  slug: string
  description: string | null
  sortOrder: number
  active: boolean
}

export interface UpdateCategoryReq extends CategoryReq {
  version: number
}

export interface GetAllCategoriesRes {
  categories: Category[]
}
