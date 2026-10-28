package com.mymicroservice.product.service.impl;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import com.mymicroservice.product.exception.AppException;
import com.mymicroservice.product.exception.ErrorCode;
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

    @Override
    public AddOneNewProductRes addOneNewProduct(AddOneNewProductReq addOneNewProductReq) {
        Product savedProduct = productRepository.save(addOneNewProductReq.toEntity());
        return AddOneNewProductRes.from(savedProduct);
    }

    @Override
    @Transactional(readOnly = true)
    public GetProductByIdRes getProductById(UUID id) {
        return productRepository.findById(id).map(GetProductByIdRes::from)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public GetProductBySkuRes getProductBySku(String sku) {
        return productRepository.findBySku(sku).map(GetProductBySkuRes::from)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));
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

    @Override
    @Transactional(readOnly = true)
    public GetProductsByIdsRes getProductsByIds(List<UUID> ids) {
        return GetProductsByIdsRes.from(productRepository.findAllById(ids));
    }

    @Override
    public UpdateOneProductRes updateOneProduct(UUID id, UpdateOneProductReq updateOneProductReq) {
        // 1. 檢查商品是否存在與版本是否相同
        Product product = findProductWithVersionCheck(id, updateOneProductReq.version());

        // 2. 檢查商品是否封存
        if (product.getStatus() == ProductStatus.ARCHIVED) {
            throw new AppException(ErrorCode.PRODUCT_ARCHIVED);
        }

        // 3. 檢查sku是否重複
        if (productRepository.existsBySkuAndIdNot(updateOneProductReq.sku(), id)) {
            throw new AppException(ErrorCode.PRODUCT_SKU_DUPLICATED);
        }

        // 4. 檢查上架的產品是否有分類
        if (product.getStatus() == ProductStatus.ACTIVE && updateOneProductReq.categoryId() == null) {
            throw new AppException(ErrorCode.PRODUCT_ACTIVE_CATEGORY_REQUIRED);
        }

        // 5. 檢查分類是否啟用
        if (updateOneProductReq.categoryId() != null
                && !categoryRepository.existsByIdAndActiveTrue(updateOneProductReq.categoryId())) {
            throw new AppException(ErrorCode.CATEGORY_INACTIVE);
        }

        // product 為 managed 狀態，不需 save；flush 後 updatedAt 與 version 才會更新
        updateOneProductReq.applyTo(product);
        productRepository.flush();
        return UpdateOneProductRes.from(product);
    }

    @Override
    public ChangeProductStatusRes publishProduct(UUID id, ChangeProductStatusReq changeProductStatusReq) {
        // 1. 檢查商品是否存在與版本是否相同
        Product product = findProductWithVersionCheck(id, changeProductStatusReq.version());

        // 2. 檢查狀態是否為DRAFT或INACTIVE
        if (!EnumSet.of(ProductStatus.DRAFT, ProductStatus.INACTIVE).contains(product.getStatus())) {
            throw new AppException(ErrorCode.PRODUCT_STATUS_TRANSITION_INVALID, product.getStatus(), "published");
        }

        // 3. 檢查產品是否已經有分類
        if (product.getCategoryId() == null || !categoryRepository.existsByIdAndActiveTrue(product.getCategoryId())) {
            throw new AppException(ErrorCode.PRODUCT_ACTIVE_CATEGORY_REQUIRED);
        }

        // 4. 檢查產品是否有主圖
        if (!productImageRepository.existsByProductIdAndIsPrimaryTrue(product.getId())) {
            throw new AppException(ErrorCode.PRODUCT_PRIMARY_IMAGE_REQUIRED);
        }

        // 5. 改狀態並儲存
        product.setStatus(ProductStatus.ACTIVE);
        product = productRepository.saveAndFlush(product);

        return ChangeProductStatusRes.from(product);
    }

    @Override
    public ChangeProductStatusRes unpublishProduct(UUID id, ChangeProductStatusReq changeProductStatusReq) {
        // 1. 檢查商品是否存在與版本是否相同
        Product product = findProductWithVersionCheck(id, changeProductStatusReq.version());

        // 2. 檢查狀態是否為ACTIVE
        if (ProductStatus.ACTIVE != product.getStatus()) {
            throw new AppException(ErrorCode.PRODUCT_STATUS_TRANSITION_INVALID, product.getStatus(), "unpublished");
        }

        // 3. 改狀態並儲存
        product.setStatus(ProductStatus.INACTIVE);
        product = productRepository.saveAndFlush(product);

        return ChangeProductStatusRes.from(product);
    }

    @Override
    public ChangeProductStatusRes archiveProduct(UUID id, ChangeProductStatusReq changeProductStatusReq) {
        // 1. 檢查商品是否存在與版本是否相同
        Product product = findProductWithVersionCheck(id, changeProductStatusReq.version());

        // 2. 檢查狀態是否已經封存
        if (ProductStatus.ARCHIVED == product.getStatus()) {
            throw new AppException(ErrorCode.PRODUCT_ALREADY_ARCHIVED);
        }

        // 3. 改狀態並儲存
        product.setStatus(ProductStatus.ARCHIVED);
        product = productRepository.saveAndFlush(product);

        return ChangeProductStatusRes.from(product);
    }

    private Product findProductWithVersionCheck(UUID id, Long version) {
        // 1. 查詢product
        Product product = productRepository.findById(id).orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

        // 2. 比對Version
        if (!version.equals(product.getVersion())) {
            throw new AppException(ErrorCode.VERSION_CONFLICT);
        }

        return product;
    }
}
