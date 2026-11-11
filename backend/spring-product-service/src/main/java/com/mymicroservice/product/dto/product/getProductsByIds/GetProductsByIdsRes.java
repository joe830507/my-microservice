package com.mymicroservice.product.dto.product.getProductsByIds;

import java.util.List;

import com.mymicroservice.product.dto.product.getProductById.GetProductByIdRes;
import com.mymicroservice.product.entity.Product;

// 不存在的 id 不會出現在 products 中，由呼叫端比對傳入的 id 判斷缺少哪些
public record GetProductsByIdsRes(
        List<ProductBatchItem> products) {
    public static GetProductsByIdsRes from(List<Product> products) {
        return new GetProductsByIdsRes(products.stream().map(ProductBatchItem::from).toList());
    }

    public static GetProductsByIdsRes fromDetails(List<GetProductByIdRes> details, boolean includeUnpublished) {
        return new GetProductsByIdsRes(
                details.stream().map(detail -> ProductBatchItem.forVisibility(detail, includeUnpublished)).toList());
    }
}
