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
        // 前台有關鍵字時查 Elasticsearch（名稱、品牌、描述、SKU），後台與搜尋引擎無法使用時退回模糊比對商品名稱
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

        // 限定可排序欄位，避免依任意欄位排序；relevance 只有關鍵字搜尋有意義，沒有關鍵字（或退回資料庫查詢）時等同 createdAt
        // 沒傳時保留 null：有關鍵字的前台搜尋預設依相關性，其他情況依建立時間，由查詢的那一端決定
        @Pattern(regexp = "^(relevance|createdAt|price|name)$") String sortBy,

        // 沒傳時由 direction() 補成 desc
        @Pattern(regexp = "^(asc|desc)$") String sortDirection) {

    // 未傳入的分頁參數補上預設值：第一頁、每頁 20 筆
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
    }

    // 任一邊未傳入時不檢查，交由各自的欄位驗證處理
    @AssertTrue(message = "minPrice must be less than or equal to maxPrice")
    public boolean isPriceRangeValid() {
        return minPrice == null || maxPrice == null || minPrice.compareTo(maxPrice) <= 0;
    }

    public boolean hasKeyword() {
        return keyword != null && !keyword.isBlank();
    }

    public Sort.Direction direction() {
        return sortDirection == null ? Sort.Direction.DESC : Sort.Direction.fromString(sortDirection);
    }

    // 資料庫查詢用：沒指定或 relevance 時最新的排前面
    public Pageable toPageable() {
        String column = sortBy == null || "relevance".equals(sortBy) ? "createdAt" : sortBy;
        return PageRequest.of(page, size, Sort.by(direction(), column).and(Sort.by(Sort.Direction.ASC, "id")));
    }
}
