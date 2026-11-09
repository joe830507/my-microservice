package com.mymicroservice.product.service.impl;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
import com.mymicroservice.product.entity.Product;
import com.mymicroservice.product.entity.ProductStatus;
import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.common.exception.CommonErrorCode;
import com.mymicroservice.product.exception.ProductErrorCode;
import com.mymicroservice.product.repository.CategoryRepository;
import com.mymicroservice.product.repository.ProductImageRepository;
import com.mymicroservice.product.repository.ProductRepository;
import com.mymicroservice.product.service.ProductService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductServiceImpl implements ProductService {
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductImageRepository productImageRepository;
    private final ProductDetailCache productDetailCache;

    @Override
    public AddOneNewProductRes addOneNewProduct(AddOneNewProductReq addOneNewProductReq) {
        Product savedProduct = productRepository.save(addOneNewProductReq.toEntity());

        // 建立前可能有人查過這個 sku，查不到的結果會被短暫快取
        productDetailCache.evictSkuMappingAfterCommit(savedProduct.getSku());
        return AddOneNewProductRes.from(savedProduct);
    }

    // 不開交易：快取命中時不需要拿 DB 連線；查 DB 時由 ProductDetailCache 自己開唯讀交易
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public GetProductByIdRes getProductById(UUID id) {
        return productDetailCache.findById(id)
                .orElseThrow(() -> new AppException(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    // 不開交易的原因同 getProductById
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public GetProductBySkuRes getProductBySku(String sku) {
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

        return detail.map(GetProductBySkuRes::from)
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
    public ProductQueryResponse getProductList(ProductQueryRequest productQueryRequest) {
        Page<Product> productPage = productRepository.findAll(toSpecification(productQueryRequest),
                productQueryRequest.toPageable());
        return ProductQueryResponse.from(productPage);
    }

    // 只組合有傳入的條件，全部未傳入時等同查詢全部
    private Specification<Product> toSpecification(ProductQueryRequest productQueryRequest) {
        Specification<Product> specification = Specification.unrestricted();

        if (productQueryRequest.keyword() != null && !productQueryRequest.keyword().isBlank()) {
            String pattern = "%" + escapeLike(productQueryRequest.keyword().trim().toLowerCase()) + "%";
            specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern, '\\'));
        }
        if (productQueryRequest.categoryId() != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("categoryId"),
                    productQueryRequest.categoryId()));
        }
        if (productQueryRequest.status() != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("status"),
                    productQueryRequest.status()));
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
    public GetProductsByIdsRes getProductsByIds(List<UUID> ids) {
        return GetProductsByIdsRes.fromDetails(productDetailCache.findAllByIds(ids));
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
}
