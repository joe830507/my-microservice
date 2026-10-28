package com.mymicroservice.product.controller.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

// 放 Swagger 文件註解與參數驗證（@Valid、@Size 等）；路由與參數綁定（@PathVariable、@RequestBody 等）寫在 controller
// Bean Validation 規定覆寫方法不可新增參數驗證，故驗證註解只能寫在介面，controller 不可再加
// 錯誤回應皆為 ProblemDetail（application/problem+json），含 code 欄位對應 ErrorCode，由 GlobalExceptionHandler 產生
// 商品不提供刪除，訂單仍會引用商品，以封存（archive）取代
@Tag(name = "Product", description = "商品管理")
public interface ProductApi {

    @Operation(summary = "新增商品", description = "建立時狀態固定為 DRAFT；categoryId 須為啟用中的分類。")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "新增成功"),
            @ApiResponse(responseCode = "400", description = "欄位驗證失敗，或分類不存在、已停用",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "sku 重複",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    AddOneNewProductRes addOneNewProduct(@Valid AddOneNewProductReq addOneNewProductReq);

    @Operation(summary = "依 id 查詢商品（後台用）", description = "回傳完整資料，包含所有狀態的商品與 version。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查詢成功"),
            @ApiResponse(responseCode = "404", description = "商品不存在",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    GetProductByIdRes getProductById(
            @Parameter(description = "商品 id（UUIDv7）", example = "01a17c88-9d2b-7788-a9e8-b7530ab28287") UUID id);

    @Operation(summary = "依 sku 查詢商品（後台用）")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查詢成功"),
            @ApiResponse(responseCode = "404", description = "商品不存在",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    GetProductBySkuRes getProductBySku(
            @Parameter(description = "商品 sku", example = "SHOE-NK-001") String sku);

    @Operation(summary = "查詢商品列表", description = "可依分類、狀態篩選，支援分頁與排序。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查詢成功"),
            @ApiResponse(responseCode = "400", description = "查詢參數驗證失敗",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    ProductQueryResponse getProductList(@Valid ProductQueryRequest productQueryRequest);

    @Operation(summary = "依多個 id 批次查詢商品（供 order-service 使用）", description = "不存在的 id 直接略過，不回 404。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查詢成功")
    })
    GetProductsByIdsRes getProductsByIds(
            @Parameter(description = "商品 id 清單，以逗號分隔") @Size(max = 100, message = "list length should not greater than 100") List<UUID> ids);

    @Operation(summary = "修改商品", description = "整筆取代基本資料，不可修改狀態（請使用 publish / unpublish / archive）；version 須與目前版本一致。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "修改成功"),
            @ApiResponse(responseCode = "400", description = "欄位驗證失敗、分類不存在或已停用，或商品已封存",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "商品不存在",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "version 不一致（已被他人修改），或 sku 重複",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    UpdateOneProductRes updateOneProduct(
            @Parameter(description = "商品 id（UUIDv7）", example = "01a17c88-9d2b-7788-a9e8-b7530ab28287") UUID id,
            @Valid UpdateOneProductReq updateOneProductReq);

    @Operation(summary = "上架商品", description = "DRAFT 或 INACTIVE → ACTIVE；須已有分類與主圖。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "上架成功"),
            @ApiResponse(responseCode = "400", description = "目前狀態不可上架，或必要資料不齊全",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "商品不存在",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "version 不一致（已被他人修改）",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    ChangeProductStatusRes publishProduct(
            @Parameter(description = "商品 id（UUIDv7）", example = "01a17c88-9d2b-7788-a9e8-b7530ab28287") UUID id,
            @Valid ChangeProductStatusReq changeProductStatusReq);

    @Operation(summary = "下架商品", description = "ACTIVE → INACTIVE。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "下架成功"),
            @ApiResponse(responseCode = "400", description = "目前狀態不可下架",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "商品不存在",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "version 不一致（已被他人修改）",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    ChangeProductStatusRes unpublishProduct(
            @Parameter(description = "商品 id（UUIDv7）", example = "01a17c88-9d2b-7788-a9e8-b7530ab28287") UUID id,
            @Valid ChangeProductStatusReq changeProductStatusReq);

    @Operation(summary = "封存商品", description = "任何狀態 → ARCHIVED，封存後不可再變更狀態。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "封存成功"),
            @ApiResponse(responseCode = "400", description = "商品已封存",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "商品不存在",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "version 不一致（已被他人修改）",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    ChangeProductStatusRes archiveProduct(
            @Parameter(description = "商品 id（UUIDv7）", example = "01a17c88-9d2b-7788-a9e8-b7530ab28287") UUID id,
            @Valid ChangeProductStatusReq changeProductStatusReq);
}
