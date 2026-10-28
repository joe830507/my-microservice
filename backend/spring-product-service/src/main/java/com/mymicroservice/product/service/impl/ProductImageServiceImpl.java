package com.mymicroservice.product.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mymicroservice.product.dto.productImage.addOneNewProductImage.AddOneNewProductImageReq;
import com.mymicroservice.product.dto.productImage.addOneNewProductImage.AddOneNewProductImageRes;
import com.mymicroservice.product.dto.productImage.getProductImages.GetProductImagesRes;
import com.mymicroservice.product.dto.productImage.reorderProductImages.ReorderProductImagesReq;
import com.mymicroservice.product.dto.productImage.updateOneProductImage.UpdateOneProductImageReq;
import com.mymicroservice.product.dto.productImage.updateOneProductImage.UpdateOneProductImageRes;
import com.mymicroservice.product.entity.Product;
import com.mymicroservice.product.entity.ProductImage;
import com.mymicroservice.product.entity.ProductStatus;
import com.mymicroservice.product.exception.AppException;
import com.mymicroservice.product.exception.ErrorCode;
import com.mymicroservice.product.repository.ProductImageRepository;
import com.mymicroservice.product.repository.ProductRepository;
import com.mymicroservice.product.service.ProductImageService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductImageServiceImpl implements ProductImageService {
    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;

    @Override
    public AddOneNewProductImageRes addOneNewProductImage(UUID productId,
            AddOneNewProductImageReq addOneNewProductImageReq) {
        // 1. 查詢產品是否存在
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

        // 2. 驗證產品是否為封存
        if (product.getStatus() == ProductStatus.ARCHIVED) {
            throw new AppException(ErrorCode.PRODUCT_ARCHIVED);
        }

        ProductImage productImage = addOneNewProductImageReq.toEntity(productId);
        List<ProductImage> productImages = productImageRepository.findAllByProductIdOrderBySortOrder(productId);
        // 3. 檢查是否已經有相同productId的圖片，沒有就設為主要圖片
        if (productImages.size() == 0) {
            productImage.setIsPrimary(Boolean.TRUE);
        } else {
            productImage.setSortOrder(productImages.getLast().getSortOrder() + 1);
        }

        ProductImage savedProductImage = productImageRepository.save(productImage);

        return AddOneNewProductImageRes.from(savedProductImage);
    }

    @Override
    @Transactional(readOnly = true)
    public GetProductImagesRes getProductImages(UUID productId) {
        // 檢查產品存不存在
        if (!productRepository.existsById(productId)) {
            throw new AppException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        List<ProductImage> productImages = productImageRepository.findAllByProductIdOrderBySortOrder(productId);

        return GetProductImagesRes.from(productId, productImages);
    }

    @Override
    public UpdateOneProductImageRes updateOneProductImage(UUID productId, UUID imageId,
            UpdateOneProductImageReq updateOneProductImageReq) {
        // 1. 查詢product
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

        // 2. 檢查是否為ARCHIVED
        if (ProductStatus.ARCHIVED == product.getStatus()) {
            throw new AppException(ErrorCode.PRODUCT_ARCHIVED);
        }

        ProductImage productImage = productImageRepository.findByIdAndProductId(imageId, productId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_IMAGE_NOT_FOUND));
        // 3. 比對Version
        if (!updateOneProductImageReq.version().equals(productImage.getVersion())) {
            throw new AppException(ErrorCode.VERSION_CONFLICT);
        }

        updateOneProductImageReq.applyTo(productImage);
        productImageRepository.flush();
        return UpdateOneProductImageRes.from(productImage);
    }

    @Override
    public void deleteOneProductImage(UUID productId, UUID imageId) {
        // 1. 先確認該產品圖片在不在
        ProductImage productImage = productImageRepository.findByIdAndProductId(imageId, productId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_IMAGE_NOT_FOUND));

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));
        // 2. 檢查是否為ARCHIVED
        if (ProductStatus.ARCHIVED == product.getStatus()) {
            throw new AppException(ErrorCode.PRODUCT_ARCHIVED);
        }

        // 3. 檢查商品是否為ACTIVE且主圖不能刪除
        if (ProductStatus.ACTIVE == product.getStatus() && productImage.getIsPrimary()) {
            throw new AppException(ErrorCode.PRODUCT_IMAGE_PRIMARY_UNDELETABLE);
        }

        // 4.刪除圖片
        productImageRepository.deleteById(imageId);
    }

    @Override
    public GetProductImagesRes setPrimaryProductImage(UUID productId, UUID imageId) {
        // 1. 查詢商品
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

        // 2. 檢查是否為ARCHIVED
        if (ProductStatus.ARCHIVED == product.getStatus()) {
            throw new AppException(ErrorCode.PRODUCT_ARCHIVED);
        }

        // 3. 先確認該產品圖片在不在
        ProductImage productImage = productImageRepository.findByIdAndProductId(imageId, productId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_IMAGE_NOT_FOUND));

        // 4. 已經是主圖就不需變更
        if (productImage.getIsPrimary()) {
            return GetProductImagesRes.from(productId,
                    productImageRepository.findAllByProductIdOrderBySortOrder(productId));
        }

        // 5. 原主圖須取消
        productImageRepository.findByProductIdAndIsPrimaryTrue(productId)
                .ifPresent((oldProductImage) -> oldProductImage.setIsPrimary(Boolean.FALSE));

        // 要先flush讓Hibernate更新舊主圖狀態
        productImageRepository.flush();

        // 6. 設定新主圖
        productImage.setIsPrimary(Boolean.TRUE);
        productImageRepository.flush();

        return GetProductImagesRes.from(productId,
                productImageRepository.findAllByProductIdOrderBySortOrder(productId));
    }

    @Override
    public GetProductImagesRes reorderProductImages(UUID productId, ReorderProductImagesReq reorderProductImagesReq) {
        // 1. 查詢商品
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

        // 2. 檢查是否為ARCHIVED
        if (ProductStatus.ARCHIVED == product.getStatus()) {
            throw new AppException(ErrorCode.PRODUCT_ARCHIVED);
        }

        // 查詢產品圖片
        List<ProductImage> images = productImageRepository.findAllByProductIdOrderBySortOrder(productId);
        Set<UUID> currentIds = images.stream().map(ProductImage::getId).collect(Collectors.toSet());
        // 3. 檢驗id清單是否與該商品的圖片完全一致
        if (!currentIds.equals(new HashSet<>(reorderProductImagesReq.imageIds()))) {
            throw new AppException(ErrorCode.PRODUCT_IMAGE_IDS_MISMATCH);
        }

        // 製成image map
        Map<UUID, ProductImage> imagesMap = images.stream().collect(Collectors.toMap((k) -> k.getId(), (v) -> v));
        // 重排序
        for (int i = 0; i < reorderProductImagesReq.imageIds().size(); i++) {
            imagesMap.get(reorderProductImagesReq.imageIds().get(i)).setSortOrder(i);
        }

        productImageRepository.flush();
        List<ProductImage> savedImages = productImageRepository.findAllByProductIdOrderBySortOrder(productId);
        return GetProductImagesRes.from(productId, savedImages);
    }
}
