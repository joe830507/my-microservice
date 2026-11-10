package com.mymicroservice.product.dto.category.getAllCategories;

import java.util.List;

// 後台用：所有分類（含停用）的平面列表，不分頁（分類數量有限）
// 依 sortOrder、name 排序，和分類樹同一層的順序一致
public record GetAllCategoriesRes(
        List<CategoryListItem> categories) {
}
