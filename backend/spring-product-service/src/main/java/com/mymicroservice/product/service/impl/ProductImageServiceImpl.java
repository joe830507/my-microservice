package com.mymicroservice.product.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.mymicroservice.product.dto.productImage.addOneNewProductImage.AddOneNewProductImageReq;
import com.mymicroservice.product.dto.productImage.createUploadUrl.CreateUploadUrlReq;
import com.mymicroservice.product.dto.productImage.createUploadUrl.CreateUploadUrlRes;
import com.mymicroservice.product.dto.productImage.addOneNewProductImage.AddOneNewProductImageRes;
import com.mymicroservice.product.dto.productImage.getProductImages.GetProductImagesRes;
import com.mymicroservice.product.dto.productImage.reorderProductImages.ReorderProductImagesReq;
import com.mymicroservice.product.dto.productImage.updateOneProductImage.UpdateOneProductImageReq;
import com.mymicroservice.product.dto.productImage.updateOneProductImage.UpdateOneProductImageRes;
import com.mymicroservice.product.config.StorageProperties;
import com.mymicroservice.product.entity.Product;
import com.mymicroservice.product.entity.ProductImage;
import com.mymicroservice.product.entity.ProductStatus;
import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.common.exception.CommonErrorCode;
import com.mymicroservice.product.exception.ProductErrorCode;
import com.mymicroservice.product.repository.ProductImageRepository;
import com.mymicroservice.product.repository.ProductRepository;
import com.mymicroservice.product.service.ProductImageService;
import com.mymicroservice.product.storage.ImageStorage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ProductImageServiceImpl implements ProductImageService {
    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final ImageStorage imageStorage;
    private final StorageProperties storageProperties;

    // 登記圖片：把用上傳網址傳到 tmp/ 的檔案搬到 products/，再寫入 product_image
    //   1. key 必須是這個商品的上傳網址給的格式，否則可以把別的商品的檔案登記到自己名下
    //   2. HEAD 確認檔案存在，實際類型、大小符合限制（上傳網址雖然簽了，這裡再檢查一次）
    //   3. 複製到 products/、刪掉 tmp（同一個檔案登記第二次時，tmp 已經不在 → IMAGE_UPLOAD_NOT_FOUND）
    //   4. 寫入資料表；交易 rollback 時刪掉剛搬好的物件，不留下沒有資料列的檔案
    @Override
    public AddOneNewProductImageRes addOneNewProductImage(UUID productId,
            AddOneNewProductImageReq addOneNewProductImageReq) {
        // 1. 查詢產品是否存在
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new AppException(ProductErrorCode.PRODUCT_NOT_FOUND));

        // 2. 驗證產品是否為封存
        if (product.getStatus() == ProductStatus.ARCHIVED) {
            throw new AppException(ProductErrorCode.PRODUCT_ARCHIVED);
        }

        // 3. 驗證 key、檔案，並搬到 products/
        String tmpObjectKey = addOneNewProductImageReq.objectKey().strip();
        if (!isUploadKeyOf(productId, tmpObjectKey)) {
            throw new AppException(ProductErrorCode.IMAGE_OBJECT_KEY_INVALID);
        }
        boolean acceptable = imageStorage.head(tmpObjectKey)
                .filter(stored -> stored.contentType() != null
                        && storageProperties.allowedContentTypes().contains(stored.contentType().toLowerCase(java.util.Locale.ROOT))
                        && stored.contentLength() > 0
                        && stored.contentLength() <= storageProperties.maxSize().toBytes())
                .isPresent();
        if (!acceptable) {
            throw new AppException(ProductErrorCode.IMAGE_UPLOAD_NOT_FOUND);
        }
        String objectKey = imageStorage.promote(tmpObjectKey);
        deleteObjectOnRollback(objectKey);

        ProductImage productImage = addOneNewProductImageReq.toEntity(productId, objectKey);
        List<ProductImage> productImages = productImageRepository.findAllByProductIdOrderBySortOrder(productId);
        // 4. 檢查是否已經有相同productId的圖片，沒有就設為主要圖片
        if (productImages.size() == 0) {
            productImage.setIsPrimary(Boolean.TRUE);
        } else {
            productImage.setSortOrder(productImages.getLast().getSortOrder() + 1);
        }

        ProductImage savedProductImage = productImageRepository.saveAndFlush(productImage);

        return AddOneNewProductImageRes.from(savedProductImage, imageStorage::publicUrl);
    }

    // tmp/products/{productId}/{uuid}.{jpg|png|webp}，和 ImageStorage.presignUpload 產生的格式一致
    private static boolean isUploadKeyOf(UUID productId, String objectKey) {
        return objectKey.matches("tmp/products/" + productId
                + "/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)");
    }

    // 物件已經搬到 products/，但資料列沒寫成功：刪掉物件，失敗只記 log（留給之後的對帳清理）
    private void deleteObjectOnRollback(String objectKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        imageStorage.delete(objectKey);
                    } catch (RuntimeException exception) {
                        log.warn("failed to delete object {} after rollback", objectKey, exception);
                    }
                }
            }
        });
    }

    @Override
    @Transactional(readOnly = true)
    public GetProductImagesRes getProductImages(UUID productId) {
        // 檢查產品存不存在
        if (!productRepository.existsById(productId)) {
            throw new AppException(ProductErrorCode.PRODUCT_NOT_FOUND);
        }
        List<ProductImage> productImages = productImageRepository.findAllByProductIdOrderBySortOrder(productId);

        return GetProductImagesRes.from(productId, productImages, imageStorage::publicUrl);
    }

    @Override
    public UpdateOneProductImageRes updateOneProductImage(UUID productId, UUID imageId,
            UpdateOneProductImageReq updateOneProductImageReq) {
        // 1. 查詢product
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new AppException(ProductErrorCode.PRODUCT_NOT_FOUND));

        // 2. 檢查是否為ARCHIVED
        if (ProductStatus.ARCHIVED == product.getStatus()) {
            throw new AppException(ProductErrorCode.PRODUCT_ARCHIVED);
        }

        ProductImage productImage = productImageRepository.findByIdAndProductId(imageId, productId)
                .orElseThrow(() -> new AppException(ProductErrorCode.PRODUCT_IMAGE_NOT_FOUND));
        // 3. 比對Version
        if (!updateOneProductImageReq.version().equals(productImage.getVersion())) {
            throw new AppException(CommonErrorCode.VERSION_CONFLICT);
        }

        updateOneProductImageReq.applyTo(productImage);
        productImageRepository.flush();
        return UpdateOneProductImageRes.from(productImage, imageStorage::publicUrl);
    }

    @Override
    public void deleteOneProductImage(UUID productId, UUID imageId) {
        // 1. 先確認該產品圖片在不在
        ProductImage productImage = productImageRepository.findByIdAndProductId(imageId, productId)
                .orElseThrow(() -> new AppException(ProductErrorCode.PRODUCT_IMAGE_NOT_FOUND));

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new AppException(ProductErrorCode.PRODUCT_NOT_FOUND));
        // 2. 檢查是否為ARCHIVED
        if (ProductStatus.ARCHIVED == product.getStatus()) {
            throw new AppException(ProductErrorCode.PRODUCT_ARCHIVED);
        }

        // 3. 檢查商品是否為ACTIVE且主圖不能刪除
        if (ProductStatus.ACTIVE == product.getStatus() && productImage.getIsPrimary()) {
            throw new AppException(ProductErrorCode.PRODUCT_IMAGE_PRIMARY_UNDELETABLE);
        }

        // 4.刪除圖片；物件在 commit 之後才刪，交易 rollback 時檔案還在
        productImageRepository.deleteById(imageId);
        deleteObjectAfterCommit(productImage.getObjectKey());
    }

    // 刪除失敗只記 log：資料列已經刪了，不能讓已經成功的請求回 500；漏刪的物件留給之後的對帳清理
    private void deleteObjectAfterCommit(String objectKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    imageStorage.delete(objectKey);
                } catch (RuntimeException exception) {
                    log.warn("failed to delete object {} of deleted product image", objectKey, exception);
                }
            }
        });
    }

    @Override
    public GetProductImagesRes setPrimaryProductImage(UUID productId, UUID imageId) {
        // 1. 查詢商品
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new AppException(ProductErrorCode.PRODUCT_NOT_FOUND));

        // 2. 檢查是否為ARCHIVED
        if (ProductStatus.ARCHIVED == product.getStatus()) {
            throw new AppException(ProductErrorCode.PRODUCT_ARCHIVED);
        }

        // 3. 先確認該產品圖片在不在
        ProductImage productImage = productImageRepository.findByIdAndProductId(imageId, productId)
                .orElseThrow(() -> new AppException(ProductErrorCode.PRODUCT_IMAGE_NOT_FOUND));

        // 4. 已經是主圖就不需變更
        if (productImage.getIsPrimary()) {
            return GetProductImagesRes.from(productId,
                    productImageRepository.findAllByProductIdOrderBySortOrder(productId), imageStorage::publicUrl);
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
                productImageRepository.findAllByProductIdOrderBySortOrder(productId), imageStorage::publicUrl);
    }

    @Override
    public GetProductImagesRes reorderProductImages(UUID productId, ReorderProductImagesReq reorderProductImagesReq) {
        // 1. 查詢商品
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new AppException(ProductErrorCode.PRODUCT_NOT_FOUND));

        // 2. 檢查是否為ARCHIVED
        if (ProductStatus.ARCHIVED == product.getStatus()) {
            throw new AppException(ProductErrorCode.PRODUCT_ARCHIVED);
        }

        // 查詢產品圖片
        List<ProductImage> images = productImageRepository.findAllByProductIdOrderBySortOrder(productId);
        Set<UUID> currentIds = images.stream().map(ProductImage::getId).collect(Collectors.toSet());
        // 3. 檢驗id清單是否與該商品的圖片完全一致
        if (!currentIds.equals(new HashSet<>(reorderProductImagesReq.imageIds()))) {
            throw new AppException(ProductErrorCode.PRODUCT_IMAGE_IDS_MISMATCH);
        }

        // 製成image map
        Map<UUID, ProductImage> imagesMap = images.stream().collect(Collectors.toMap((k) -> k.getId(), (v) -> v));
        // 重排序
        for (int i = 0; i < reorderProductImagesReq.imageIds().size(); i++) {
            imagesMap.get(reorderProductImagesReq.imageIds().get(i)).setSortOrder(i);
        }

        productImageRepository.flush();
        List<ProductImage> savedImages = productImageRepository.findAllByProductIdOrderBySortOrder(productId);
        return GetProductImagesRes.from(productId, savedImages, imageStorage::publicUrl);
    }

    // 申請上傳網址：商品必須存在且未封存；類型、大小在這裡先擋，上傳後登記時還會再用 HEAD 檢查一次
    // 不寫入資料庫，唯讀交易即可
    @Override
    @Transactional(readOnly = true)
    public CreateUploadUrlRes createUploadUrl(UUID productId, CreateUploadUrlReq createUploadUrlReq) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new AppException(ProductErrorCode.PRODUCT_NOT_FOUND));
        if (product.getStatus() == ProductStatus.ARCHIVED) {
            throw new AppException(ProductErrorCode.PRODUCT_ARCHIVED);
        }

        // 類型不分大小寫比對，但簽進網址的是統一成小寫的值
        String contentType = createUploadUrlReq.contentType().strip().toLowerCase(java.util.Locale.ROOT);
        if (!storageProperties.allowedContentTypes().contains(contentType)) {
            throw new AppException(ProductErrorCode.IMAGE_CONTENT_TYPE_NOT_ALLOWED, createUploadUrlReq.contentType(),
                    String.join(", ", storageProperties.allowedContentTypes()));
        }
        long maxBytes = storageProperties.maxSize().toBytes();
        if (createUploadUrlReq.contentLength() > maxBytes) {
            throw new AppException(ProductErrorCode.IMAGE_TOO_LARGE, createUploadUrlReq.contentLength(), maxBytes);
        }

        return CreateUploadUrlRes.from(
                imageStorage.presignUpload(productId, contentType, createUploadUrlReq.contentLength()));
    }
}
