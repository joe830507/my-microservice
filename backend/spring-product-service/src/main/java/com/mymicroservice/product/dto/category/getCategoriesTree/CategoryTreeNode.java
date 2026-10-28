package com.mymicroservice.product.dto.category.getCategoriesTree;

import java.util.List;
import java.util.UUID;

public record CategoryTreeNode(
        UUID id,
        String name,
        String slug,
        List<CategoryTreeNode> children) {

}
