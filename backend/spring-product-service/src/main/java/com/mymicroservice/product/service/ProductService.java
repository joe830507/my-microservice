package com.mymicroservice.product.service;

import java.util.List;
import java.util.UUID;

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

public interface ProductService {
    AddOneNewProductRes addOneNewProduct(AddOneNewProductReq addOneNewProductReq);

    GetProductByIdRes getProductById(UUID id);

    GetProductBySkuRes getProductBySku(String sku);

    ProductQueryResponse getProductList(ProductQueryRequest productQueryRequest);

    GetProductsByIdsRes getProductsByIds(List<UUID> ids);

    UpdateOneProductRes updateOneProduct(UUID id, UpdateOneProductReq updateOneProductReq);

    ChangeProductStatusRes publishProduct(UUID id, ChangeProductStatusReq changeProductStatusReq);

    ChangeProductStatusRes unpublishProduct(UUID id, ChangeProductStatusReq changeProductStatusReq);

    ChangeProductStatusRes archiveProduct(UUID id, ChangeProductStatusReq changeProductStatusReq);
}
