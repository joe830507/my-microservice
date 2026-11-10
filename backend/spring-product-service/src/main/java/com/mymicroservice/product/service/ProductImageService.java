package com.mymicroservice.product.service;

import java.util.UUID;

import com.mymicroservice.product.dto.productImage.addOneNewProductImage.AddOneNewProductImageReq;
import com.mymicroservice.product.dto.productImage.createUploadUrl.CreateUploadUrlReq;
import com.mymicroservice.product.dto.productImage.createUploadUrl.CreateUploadUrlRes;
import com.mymicroservice.product.dto.productImage.addOneNewProductImage.AddOneNewProductImageRes;
import com.mymicroservice.product.dto.productImage.getProductImages.GetProductImagesRes;
import com.mymicroservice.product.dto.productImage.reorderProductImages.ReorderProductImagesReq;
import com.mymicroservice.product.dto.productImage.updateOneProductImage.UpdateOneProductImageReq;
import com.mymicroservice.product.dto.productImage.updateOneProductImage.UpdateOneProductImageRes;

public interface ProductImageService {
    AddOneNewProductImageRes addOneNewProductImage(UUID productId, AddOneNewProductImageReq addOneNewProductImageReq);

    // includeUnpublished：false 時，不是上架中的商品回 404（和商品詳情一致）
    GetProductImagesRes getProductImages(UUID productId, boolean includeUnpublished);

    UpdateOneProductImageRes updateOneProductImage(UUID productId, UUID imageId,
            UpdateOneProductImageReq updateOneProductImageReq);

    void deleteOneProductImage(UUID productId, UUID imageId);

    GetProductImagesRes setPrimaryProductImage(UUID productId, UUID imageId);

    GetProductImagesRes reorderProductImages(UUID productId, ReorderProductImagesReq reorderProductImagesReq);


    public CreateUploadUrlRes createUploadUrl(UUID productId, CreateUploadUrlReq createUploadUrlReq);
}
