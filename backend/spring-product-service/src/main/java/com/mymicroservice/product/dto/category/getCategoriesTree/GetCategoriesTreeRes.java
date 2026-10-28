package com.mymicroservice.product.dto.category.getCategoriesTree;

import java.util.List;

// 只放頂層分類，子分類由各節點的 children 往下展開
public record GetCategoriesTreeRes(
        List<CategoryTreeNode> roots) {

}
