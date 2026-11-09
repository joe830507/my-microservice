package com.mymicroservice.product.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 所有錯誤訊息集中於此；名稱即回應中的 code，前端可依 code 判斷錯誤類型，不需比對訊息文字
// message 可含 %s 佔位符，由 AppException 建構時帶入參數
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // Common
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "request validation failed."),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "data has been modified by others, please reload."),
    DATA_CONFLICT(HttpStatus.CONFLICT, "data conflicts with existing data."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "server error, please contact administrator."),

    // Category
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "category not found."),
    CATEGORY_INACTIVE(HttpStatus.BAD_REQUEST, "category is inactive."),
    CATEGORY_PARENT_NOT_FOUND(HttpStatus.BAD_REQUEST, "parent category does not exist."),
    CATEGORY_PARENT_INVALID(HttpStatus.BAD_REQUEST, "parent cannot be itself or its descendant."),
    CATEGORY_SLUG_DUPLICATED(HttpStatus.CONFLICT, "category slug is duplicated."),
    CATEGORY_NAME_DUPLICATED(HttpStatus.CONFLICT, "category name is duplicated under the same parent."),

    // Product
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "product not found."),
    PRODUCT_SKU_DUPLICATED(HttpStatus.CONFLICT, "product sku is duplicated."),
    PRODUCT_ARCHIVED(HttpStatus.BAD_REQUEST, "product is archived and cannot be modified."),
    PRODUCT_ALREADY_ARCHIVED(HttpStatus.BAD_REQUEST, "product is already archived."),
    // 參數：目前狀態、欲執行的動作（例如 published）
    PRODUCT_STATUS_TRANSITION_INVALID(HttpStatus.BAD_REQUEST, "product in status %s cannot be %s."),
    PRODUCT_ACTIVE_CATEGORY_REQUIRED(HttpStatus.BAD_REQUEST, "active product must have an active category."),
    PRODUCT_PRIMARY_IMAGE_REQUIRED(HttpStatus.BAD_REQUEST, "product must have a primary image."),

    // Product Image
    PRODUCT_IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "product image not found."),
    PRODUCT_IMAGE_PRIMARY_UNDELETABLE(HttpStatus.BAD_REQUEST,
            "primary image of an active product cannot be deleted, please set another primary image first."),
    PRODUCT_IMAGE_IDS_MISMATCH(HttpStatus.BAD_REQUEST, "imageIds must contain exactly all images of the product.");

    private final HttpStatus status;
    private final String message;
}
