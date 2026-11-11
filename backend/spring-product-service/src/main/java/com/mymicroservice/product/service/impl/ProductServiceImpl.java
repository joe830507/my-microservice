package com.mymicroservice.product.service.impl;

import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mymicroservice.product.cache.ProductDetailCache;
import com.mymicroservice.product.dto.product.addOneNewProduct.AddOneNewProductReq;
import com.mymicroservice.product.dto.product.addOneNewProduct.AddOneNewProductRes;
import com.mymicroservice.product.dto.product.changeProductStatus.ChangeProductStatusReq;
import com.mymicroservice.product.dto.product.changeProductStatus.ChangeProductStatusRes;
import com.mymicroservice.product.dto.product.getProductById.GetProductByIdRes;
import com.mymicroservice.product.dto.product.getProductBySku.GetProductBySkuRes;
import com.mymicroservice.product.dto.product.getProductList.ProductQueryRequest;
import com.mymicroservice.product.dto.product.getProductList.ProductQueryResponse;
import com.mymicroservice.product.dto.product.getProductsByIds.GetProductsByIdsRes;
import com.mymicroservice.product.dto.product.updateOneProduct.UpdateOneProductReq;
import com.mymicroservice.product.dto.product.updateOneProduct.UpdateOneProductRes;
import com.mymicroservice.product.entity.Category;
import com.mymicroservice.product.entity.Product;
import com.mymicroservice.product.entity.ProductImage;
import com.mymicroservice.product.entity.ProductStatus;
import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.common.exception.CommonErrorCode;
import com.mymicroservice.common.events.OutboxEventWriter;
import com.mymicroservice.product.exception.ProductErrorCode;
import com.mymicroservice.product.repository.CategoryRepository;
import com.mymicroservice.product.repository.ProductImageRepository;
import com.mymicroservice.product.repository.ProductRepository;
import com.mymicroservice.product.service.ProductService;
import com.mymicroservice.product.storage.ImageStorage;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductServiceImpl implements ProductService {
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductImageRepository productImageRepository;
    private final ProductDetailCache productDetailCache;
    private final ImageStorage imageStorage;
    private final OutboxEventWriter outbox;

    @Override
    public AddOneNewProductRes addOneNewProduct(AddOneNewProductReq addOneNewProductReq) {
        // 先 flush 取得資料庫精度一致的 createdAt 與 version，再把同一份快照寫入 outbox。
        Product savedProduct = productRepository.saveAndFlush(addOneNewProductReq.toEntity());
        publishProductEvent(savedProduct, "ProductCreated");

        // 建立前可能有人查過這個 sku，查不到的結果會被短暫快取
        productDetailCache.evictSkuMappingAfterCommit(savedProduct.getSku());
        return AddOneNewProductRes.from(savedProduct);
    }

    // 不開交易：快取命中時不需要拿 DB 連線；查 DB 時由 ProductDetailCache 自己開唯讀交易
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public GetProductByIdRes getProductById(UUID id, boolean includeUnpublished) {
        // 可見性在讀快取之後判斷：快取存的是完整資料，不能把「這個人看不到」的結果快取起來
        return productDetailCache.findById(id)
                .filter(detail -> includeUnpublished || detail.status() == ProductStatus.ACTIVE)
                .orElseThrow(() -> new AppException(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    // 不開交易的原因同 getProductById
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public GetProductBySkuRes getProductBySku(String sku, boolean includeUnpublished) {
        // 對照本身查不到，代表 sku 不存在（查不到的結果也有快取），直接回 404，不重查 DB
        if (productDetailCache.findIdBySku(sku).isEmpty()) {
            throw new AppException(ProductErrorCode.PRODUCT_NOT_FOUND);
        }

        // 對照存在但商品的 sku 已經不同，代表 sku 被改過、對照過期，刪掉後再從 DB 查一次
        Optional<GetProductByIdRes> detail = findDetailBySku(sku);
        if (detail.isEmpty()) {
            productDetailCache.evictSkuMapping(sku);
            detail = findDetailBySku(sku);
        }

        return detail.filter(found -> includeUnpublished || found.status() == ProductStatus.ACTIVE)
                .map(GetProductBySkuRes::from)
                .orElseThrow(() -> new AppException(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    // 先由 sku 對照出 id，再取商品詳情，並確認詳情的 sku 與查詢的一致
    private Optional<GetProductByIdRes> findDetailBySku(String sku) {
        return productDetailCache.findIdBySku(sku)
                .flatMap(productDetailCache::findById)
                .filter(detail -> detail.sku().equals(sku));
    }

    @Override
    @Transactional(readOnly = true)
    public ProductQueryResponse getProductList(ProductQueryRequest productQueryRequest, boolean includeUnpublished) {
        // 前台只看得到上架中的商品：忽略傳入的 status，固定查 ACTIVE
        ProductStatus status = includeUnpublished ? productQueryRequest.status() : ProductStatus.ACTIVE;
        Page<Product> productPage = productRepository.findAll(toSpecification(productQueryRequest, status),
                productQueryRequest.toPageable());
        return ProductQueryResponse.from(productPage, primaryImageUrls(productPage.getContent()));
    }

    // 分類自己加上所有子孫分類的 id（含停用的，商品的可見性另外由狀態決定）
    // 分類數量有限，一次讀出全部再往下展開；用 result 記錄看過的 id，資料有循環也不會無限迴圈
    private Set<UUID> withDescendants(UUID rootId) {
        Map<UUID, List<UUID>> childrenByParentId = categoryRepository.findAll().stream()
                .filter(category -> category.getParentId() != null)
                .collect(Collectors.groupingBy(Category::getParentId,
                        Collectors.mapping(Category::getId, Collectors.toList())));
        Set<UUID> result = new HashSet<>();
        Deque<UUID> pending = new ArrayDeque<>(List.of(rootId));
        while (!pending.isEmpty()) {
            UUID current = pending.pop();
            if (result.add(current)) {
                pending.addAll(childrenByParentId.getOrDefault(current, List.of()));
            }
        }
        return result;
    }

    // 一次查出這一頁商品的主圖（每個商品最多一張主圖），轉成公開網址
    private Map<UUID, String> primaryImageUrls(List<Product> products) {
        if (products.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = products.stream().map(Product::getId).toList();
        return productImageRepository.findAllByProductIdInAndIsPrimaryTrue(ids).stream()
                .collect(Collectors.toMap(ProductImage::getProductId,
                        image -> imageStorage.publicUrl(image.getObjectKey()),
                        (first, second) -> first));
    }

    // 只組合有傳入的條件，全部未傳入時等同查詢全部
    private Specification<Product> toSpecification(ProductQueryRequest productQueryRequest, ProductStatus status) {
        Specification<Product> specification = Specification.unrestricted();

        if (productQueryRequest.keyword() != null && !productQueryRequest.keyword().isBlank()) {
            String pattern = "%" + escapeLike(productQueryRequest.keyword().trim().toLowerCase()) + "%";
            specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern, '\\'));
        }
        if (productQueryRequest.categoryId() != null) {
            if (Boolean.TRUE.equals(productQueryRequest.includeSubcategories())) {
                Set<UUID> categoryIds = withDescendants(productQueryRequest.categoryId());
                specification = specification.and((root, query, cb) -> root.get("categoryId").in(categoryIds));
            } else {
                specification = specification.and((root, query, cb) -> cb.equal(root.get("categoryId"),
                        productQueryRequest.categoryId()));
            }
        }
        if (status != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (productQueryRequest.minPrice() != null) {
            specification = specification.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("price"),
                    productQueryRequest.minPrice()));
        }
        if (productQueryRequest.maxPrice() != null) {
            specification = specification.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("price"),
                    productQueryRequest.maxPrice()));
        }
        return specification;
    }

    // 跳脫 LIKE 的萬用字元，避免使用者輸入 % 或 _ 時被當成模糊比對
    private String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // 不開交易的原因同 getProductById
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public GetProductsByIdsRes getProductsByIds(List<UUID> ids, boolean includeUnpublished) {
        return GetProductsByIdsRes.fromDetails(productDetailCache.findAllByIds(ids), includeUnpublished);
    }

    @Override
    public UpdateOneProductRes updateOneProduct(UUID id, UpdateOneProductReq updateOneProductReq) {
        // 1. 檢查商品是否存在與版本是否相同
        Product product = findProductWithVersionCheck(id, updateOneProductReq.version());

        // 2. 檢查商品是否封存
        if (product.getStatus() == ProductStatus.ARCHIVED) {
            throw new AppException(ProductErrorCode.PRODUCT_ARCHIVED);
        }

        // 3. 檢查sku是否重複
        if (productRepository.existsBySkuAndIdNot(updateOneProductReq.sku(), id)) {
            throw new AppException(ProductErrorCode.PRODUCT_SKU_DUPLICATED);
        }

        // 4. 檢查上架的產品是否有分類
        if (product.getStatus() == ProductStatus.ACTIVE && updateOneProductReq.categoryId() == null) {
            throw new AppException(ProductErrorCode.PRODUCT_ACTIVE_CATEGORY_REQUIRED);
        }

        // 5. 檢查分類是否啟用
        if (updateOneProductReq.categoryId() != null
                && !categoryRepository.existsByIdAndActiveTrue(updateOneProductReq.categoryId())) {
            throw new AppException(ProductErrorCode.CATEGORY_INACTIVE);
        }

        // product 為 managed 狀態，不需 save；flush 後 updatedAt 與 version 才會更新
        String oldSku = product.getSku();
        updateOneProductReq.applyTo(product);
        productRepository.flush();
        publishProductEvent(product, "ProductUpdated");

        // sku 有改的話，舊 sku 的對照會過期，新 sku 可能有快取到查不到的結果，兩者都要刪
        productDetailCache.evictAfterCommit(id);
        productDetailCache.evictSkuMappingAfterCommit(oldSku);
        if (!oldSku.equals(product.getSku())) {
            productDetailCache.evictSkuMappingAfterCommit(product.getSku());
        }
        return UpdateOneProductRes.from(product);
    }

    @Override
    public ChangeProductStatusRes publishProduct(UUID id, ChangeProductStatusReq changeProductStatusReq) {
        // 1. 檢查商品是否存在與版本是否相同
        Product product = findProductWithVersionCheck(id, changeProductStatusReq.version());

        // 2. 檢查狀態是否為DRAFT或INACTIVE
        if (!EnumSet.of(ProductStatus.DRAFT, ProductStatus.INACTIVE).contains(product.getStatus())) {
            throw new AppException(ProductErrorCode.PRODUCT_STATUS_TRANSITION_INVALID, product.getStatus(), "published");
        }

        // 3. 檢查產品是否已經有分類
        if (product.getCategoryId() == null || !categoryRepository.existsByIdAndActiveTrue(product.getCategoryId())) {
            throw new AppException(ProductErrorCode.PRODUCT_ACTIVE_CATEGORY_REQUIRED);
        }

        // 4. 檢查產品是否有主圖
        if (!productImageRepository.existsByProductIdAndIsPrimaryTrue(product.getId())) {
            throw new AppException(ProductErrorCode.PRODUCT_PRIMARY_IMAGE_REQUIRED);
        }

        // 5. 改狀態並儲存
        product.setStatus(ProductStatus.ACTIVE);
        product = productRepository.saveAndFlush(product);
        publishProductEvent(product, "ProductStatusChanged");

        productDetailCache.evictAfterCommit(id);
        return ChangeProductStatusRes.from(product);
    }

    @Override
    public ChangeProductStatusRes unpublishProduct(UUID id, ChangeProductStatusReq changeProductStatusReq) {
        // 1. 檢查商品是否存在與版本是否相同
        Product product = findProductWithVersionCheck(id, changeProductStatusReq.version());

        // 2. 檢查狀態是否為ACTIVE
        if (ProductStatus.ACTIVE != product.getStatus()) {
            throw new AppException(ProductErrorCode.PRODUCT_STATUS_TRANSITION_INVALID, product.getStatus(), "unpublished");
        }

        // 3. 改狀態並儲存
        product.setStatus(ProductStatus.INACTIVE);
        product = productRepository.saveAndFlush(product);
        publishProductEvent(product, "ProductStatusChanged");

        productDetailCache.evictAfterCommit(id);
        return ChangeProductStatusRes.from(product);
    }

    @Override
    public ChangeProductStatusRes archiveProduct(UUID id, ChangeProductStatusReq changeProductStatusReq) {
        // 1. 檢查商品是否存在與版本是否相同
        Product product = findProductWithVersionCheck(id, changeProductStatusReq.version());

        // 2. 檢查狀態是否已經封存
        if (ProductStatus.ARCHIVED == product.getStatus()) {
            throw new AppException(ProductErrorCode.PRODUCT_ALREADY_ARCHIVED);
        }

        // 3. 改狀態並儲存
        product.setStatus(ProductStatus.ARCHIVED);
        product = productRepository.saveAndFlush(product);
        publishProductEvent(product, "ProductStatusChanged");

        productDetailCache.evictAfterCommit(id);
        return ChangeProductStatusRes.from(product);
    }

    private Product findProductWithVersionCheck(UUID id, Long version) {
        // 1. 查詢product
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new AppException(ProductErrorCode.PRODUCT_NOT_FOUND));

        // 2. 比對Version
        if (!version.equals(product.getVersion())) {
            throw new AppException(CommonErrorCode.VERSION_CONFLICT);
        }

        return product;
    }

    private void publishProductEvent(Product product, String type) {
        outbox.append("product.events", type, product.getId().toString(), Map.of(
                "productId", product.getId().toString(),
                "sku", product.getSku(),
                "name", product.getName(),
                "description", product.getDescription() == null ? "" : product.getDescription(),
                "brand", product.getBrand() == null ? "" : product.getBrand(),
                "createdAt", product.getCreatedAt().truncatedTo(ChronoUnit.MICROS).toString(),
                "price", product.getPrice(),
                "categoryId", product.getCategoryId() == null ? "" : product.getCategoryId().toString(),
                "status", product.getStatus().name(),
                "version", product.getVersion()));
    }
}
