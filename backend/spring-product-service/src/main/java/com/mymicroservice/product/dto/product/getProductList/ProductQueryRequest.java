package com.mymicroservice.product.dto.product.getProductList;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.mymicroservice.product.entity.ProductStatus;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

// 以 query string 傳入，所有篩選條件皆為選填，未傳入代表不篩選
public record ProductQueryRequest(
        // 模糊比對商品名稱
        @Size(max = 255) String keyword,

        UUID categoryId,
        // true 時連同 categoryId 的所有子孫分類一起查（前台點上層分類要看到底下的商品）；預設 false
        Boolean includeSubcategories,

        ProductStatus status,

        @DecimalMin("0") BigDecimal minPrice,

        @DecimalMin("0") BigDecimal maxPrice,

        // 從 0 開始
        @PositiveOrZero Integer page,

        @Min(1) @Max(100) Integer size,

        // 限定可排序欄位，避免依任意欄位排序
        @Pattern(regexp = "^(createdAt|price|name)$") String sortBy,

        @Pattern(regexp = "^(asc|desc)$") String sortDirection) {

    // 未傳入的分頁、排序參數補上預設值：第一頁、每頁 20 筆、最新的排前面
    public ProductQueryRequest {
        if (includeSubcategories == null) {
            includeSubcategories = Boolean.FALSE;
        }
        if (page == null) {
            page = 0;
        }
        if (size == null) {
            size = 20;
        }
        if (sortBy == null) {
            sortBy = "createdAt";
        }
        if (sortDirection == null) {
            sortDirection = "desc";
        }
    }

    // 任一邊未傳入時不檢查，交由各自的欄位驗證處理
    @AssertTrue(message = "minPrice must be less than or equal to maxPrice")
    public boolean isPriceRangeValid() {
        return minPrice == null || maxPrice == null || minPrice.compareTo(maxPrice) <= 0;
    }

    public Pageable toPageable() {
        return PageRequest.of(page, size, Sort.by(Sort.Direction.fromString(sortDirection), sortBy));
    }
}
