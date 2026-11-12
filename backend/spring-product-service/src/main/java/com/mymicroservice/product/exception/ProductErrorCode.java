package com.mymicroservice.product.exception;

import org.springframework.http.HttpStatus;

import com.mymicroservice.common.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// product-service 的業務錯誤碼；共用錯誤碼（VALIDATION_FAILED 等）在 common 的 CommonErrorCode
// 名稱即回應中的 code，前端可依 code 判斷錯誤類型，不需比對訊息文字
// message 可含 %s 佔位符，由 AppException 建構時帶入參數
@Getter
@RequiredArgsConstructor
public enum ProductErrorCode implements ErrorCode {
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

    // Search
    SEARCH_RESULT_WINDOW_EXCEEDED(HttpStatus.BAD_REQUEST,
            "search results beyond the first 10000 are not available, please narrow the search."),

    // Product Image
    PRODUCT_IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "product image not found."),
    PRODUCT_IMAGE_PRIMARY_UNDELETABLE(HttpStatus.BAD_REQUEST,
            "primary image of an active product cannot be deleted, please set another primary image first."),
    PRODUCT_IMAGE_IDS_MISMATCH(HttpStatus.BAD_REQUEST, "imageIds must contain exactly all images of the product."),
    // 申請上傳網址：類型不在允許清單、超過大小上限
    IMAGE_CONTENT_TYPE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "image content type %s is not allowed, allowed types: %s."),
    IMAGE_TOO_LARGE(HttpStatus.BAD_REQUEST, "image size %s bytes exceeds the limit of %s bytes."),
    // 登記圖片：key 不是這個商品的上傳網址給的格式（例如別的商品的檔案）
    IMAGE_OBJECT_KEY_INVALID(HttpStatus.BAD_REQUEST, "image object key is invalid for this product."),
    // 登記圖片：找不到上傳的檔案（沒上傳、已過期被刪、已經登記過），或實際類型、大小不符合限制
    IMAGE_UPLOAD_NOT_FOUND(HttpStatus.BAD_REQUEST, "uploaded image not found or does not meet the limits.");

    private final HttpStatus status;
    private final String message;
}
