package com.mymicroservice.product.service;

import java.util.UUID;

import com.mymicroservice.product.dto.productImage.addOneNewProductImage.AddOneNewProductImageReq;
import com.mymicroservice.product.dto.productImage.addOneNewProductImage.AddOneNewProductImageRes;
import com.mymicroservice.product.dto.productImage.getProductImages.GetProductImagesRes;
import com.mymicroservice.product.dto.productImage.reorderProductImages.ReorderProductImagesReq;
import com.mymicroservice.product.dto.productImage.updateOneProductImage.UpdateOneProductImageReq;
import com.mymicroservice.product.dto.productImage.updateOneProductImage.UpdateOneProductImageRes;

public interface ProductImageService {
    AddOneNewProductImageRes addOneNewProductImage(UUID productId, AddOneNewProductImageReq addOneNewProductImageReq);

    GetProductImagesRes getProductImages(UUID productId);

    UpdateOneProductImageRes updateOneProductImage(UUID productId, UUID imageId,
            UpdateOneProductImageReq updateOneProductImageReq);

    void deleteOneProductImage(UUID productId, UUID imageId);

    GetProductImagesRes setPrimaryProductImage(UUID productId, UUID imageId);

    GetProductImagesRes reorderProductImages(UUID productId, ReorderProductImagesReq reorderProductImagesReq);

}
