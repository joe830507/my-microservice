package com.mymicroservice.product.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mymicroservice.product.controller.api.ProductImageApi;
import com.mymicroservice.product.dto.productImage.addOneNewProductImage.AddOneNewProductImageReq;
import com.mymicroservice.product.dto.productImage.addOneNewProductImage.AddOneNewProductImageRes;
import com.mymicroservice.product.dto.productImage.getProductImages.GetProductImagesRes;
import com.mymicroservice.product.dto.productImage.reorderProductImages.ReorderProductImagesReq;
import com.mymicroservice.product.dto.productImage.updateOneProductImage.UpdateOneProductImageReq;
import com.mymicroservice.product.dto.productImage.updateOneProductImage.UpdateOneProductImageRes;
import com.mymicroservice.product.service.ProductImageService;

import lombok.RequiredArgsConstructor;

// API 文件說明與參數驗證請見 ProductImageApi
@RestController
@RequestMapping(value = "/api/v1/products/{productId}/images")
@RequiredArgsConstructor
public class ProductImageController implements ProductImageApi {
    private final ProductImageService productImageService;

    @Override
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(value = HttpStatus.CREATED)
    public AddOneNewProductImageRes addOneNewProductImage(
            @PathVariable UUID productId,
            @RequestBody AddOneNewProductImageReq addOneNewProductImageReq) {
        return productImageService.addOneNewProductImage(productId, addOneNewProductImageReq);
    }

    @Override
    @GetMapping
    @ResponseStatus(value = HttpStatus.OK)
    public GetProductImagesRes getProductImages(
            @PathVariable UUID productId) {
        return productImageService.getProductImages(productId);
    }

    @Override
    @PutMapping("/{imageId}")
    @ResponseStatus(value = HttpStatus.OK)
    public UpdateOneProductImageRes updateOneProductImage(
            @PathVariable UUID productId,
            @PathVariable UUID imageId,
            @RequestBody UpdateOneProductImageReq updateOneProductImageReq) {
        return productImageService.updateOneProductImage(productId, imageId, updateOneProductImageReq);
    }

    @Override
    @DeleteMapping("/{imageId}")
    @ResponseStatus(value = HttpStatus.NO_CONTENT)
    public void deleteOneProductImage(
            @PathVariable UUID productId,
            @PathVariable UUID imageId) {
        productImageService.deleteOneProductImage(productId, imageId);
    }

    @Override
    @PostMapping("/{imageId}/primary")
    @ResponseStatus(value = HttpStatus.OK)
    public GetProductImagesRes setPrimaryProductImage(
            @PathVariable UUID productId,
            @PathVariable UUID imageId) {
        return productImageService.setPrimaryProductImage(productId, imageId);
    }

    @Override
    @PutMapping(value = "/order", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(value = HttpStatus.OK)
    public GetProductImagesRes reorderProductImages(
            @PathVariable UUID productId,
            @RequestBody ReorderProductImagesReq reorderProductImagesReq) {
        return productImageService.reorderProductImages(productId, reorderProductImagesReq);
    }
}
