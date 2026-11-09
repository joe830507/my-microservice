package com.mymicroservice.product.controller;

import java.util.List;
import java.util.UUID;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mymicroservice.common.web.RequireRoles;
import com.mymicroservice.product.controller.api.ProductApi;
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
import com.mymicroservice.product.service.ProductService;

import lombok.RequiredArgsConstructor;

// API 文件說明與參數驗證請見 ProductApi
// 讀取公開（Gateway 對 GET 放行）；新增、修改、刪除限 ADMIN（@RequireRoles）
@RestController
@RequestMapping(value = "/api/v1/products")
@RequiredArgsConstructor
public class ProductController implements ProductApi {
    private final ProductService productService;

    @Override
    @RequireRoles("ADMIN")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(value = HttpStatus.CREATED)
    public AddOneNewProductRes addOneNewProduct(
            @RequestBody AddOneNewProductReq addOneNewProductReq) {
        return productService.addOneNewProduct(addOneNewProductReq);
    }

    @Override
    @GetMapping("/{id}")
    @ResponseStatus(value = HttpStatus.OK)
    public GetProductByIdRes getProductById(
            @PathVariable UUID id) {
        return productService.getProductById(id);
    }

    @Override
    @GetMapping("/sku/{sku}")
    @ResponseStatus(value = HttpStatus.OK)
    public GetProductBySkuRes getProductBySku(
            @PathVariable String sku) {
        return productService.getProductBySku(sku);
    }

    // @ParameterObject 讓 springdoc 把 record 欄位展開成個別查詢參數，與綁定方式相關故留在 controller
    @Override
    @GetMapping
    @ResponseStatus(value = HttpStatus.OK)
    public ProductQueryResponse getProductList(
            @ParameterObject ProductQueryRequest productQueryRequest) {
        return productService.getProductList(productQueryRequest);
    }

    // @Size 寫在 ProductApi，controller 不可再加驗證註解
    @Override
    @GetMapping("/batch")
    @ResponseStatus(value = HttpStatus.OK)
    public GetProductsByIdsRes getProductsByIds(
            @RequestParam List<UUID> ids) {
        return productService.getProductsByIds(ids);
    }

    @Override
    @RequireRoles("ADMIN")
    @PutMapping("/{id}")
    @ResponseStatus(value = HttpStatus.OK)
    public UpdateOneProductRes updateOneProduct(
            @PathVariable UUID id,
            @RequestBody UpdateOneProductReq updateOneProductReq) {
        return productService.updateOneProduct(id, updateOneProductReq);
    }

    @Override
    @RequireRoles("ADMIN")
    @PostMapping("/{id}/publish")
    @ResponseStatus(value = HttpStatus.OK)
    public ChangeProductStatusRes publishProduct(
            @PathVariable UUID id,
            @RequestBody ChangeProductStatusReq changeProductStatusReq) {
        return productService.publishProduct(id, changeProductStatusReq);
    }

    @Override
    @RequireRoles("ADMIN")
    @PostMapping("/{id}/unpublish")
    @ResponseStatus(value = HttpStatus.OK)
    public ChangeProductStatusRes unpublishProduct(
            @PathVariable UUID id,
            @RequestBody ChangeProductStatusReq changeProductStatusReq) {
        return productService.unpublishProduct(id, changeProductStatusReq);
    }

    @Override
    @RequireRoles("ADMIN")
    @PostMapping("/{id}/archive")
    @ResponseStatus(value = HttpStatus.OK)
    public ChangeProductStatusRes archiveProduct(
            @PathVariable UUID id,
            @RequestBody ChangeProductStatusReq changeProductStatusReq) {
        return productService.archiveProduct(id, changeProductStatusReq);
    }
}
