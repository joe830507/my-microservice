package com.mymicroservice.product.controller.api;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import com.mymicroservice.product.dto.productImage.addOneNewProductImage.AddOneNewProductImageReq;
import com.mymicroservice.product.dto.productImage.addOneNewProductImage.AddOneNewProductImageRes;
import com.mymicroservice.product.dto.productImage.getProductImages.GetProductImagesRes;
import com.mymicroservice.product.dto.productImage.reorderProductImages.ReorderProductImagesReq;
import com.mymicroservice.product.dto.productImage.updateOneProductImage.UpdateOneProductImageReq;
import com.mymicroservice.product.dto.productImage.updateOneProductImage.UpdateOneProductImageRes;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

// 放 Swagger 文件註解與參數驗證（@Valid、@Size 等）；路由與參數綁定（@PathVariable、@RequestBody 等）寫在 controller
// Bean Validation 規定覆寫方法不可新增參數驗證，故驗證註解只能寫在介面，controller 不可再加
// 錯誤回應皆為 ProblemDetail（application/problem+json），含 code 欄位對應 ErrorCode，由 GlobalExceptionHandler 產生
// 圖片檔案由前端自行上傳至外部儲存，此處只記錄 url
// 所有 /{imageId} 操作皆須確認圖片屬於 path 上的 productId，否則視為不存在
@Tag(name = "Product Image", description = "商品圖片管理")
public interface ProductImageApi {

        @Operation(summary = "新增商品圖片", description = "商品的第一張圖片自動設為主圖；已封存的商品不可新增。")
        @ApiResponses({
                        @ApiResponse(responseCode = "201", description = "新增成功"),
                        @ApiResponse(responseCode = "400", description = "欄位驗證失敗，或商品已封存", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
                        @ApiResponse(responseCode = "404", description = "商品不存在", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
        })
        AddOneNewProductImageRes addOneNewProductImage(
                        @Parameter(description = "商品 id（UUIDv7）", example = "01a17c88-9d2b-7788-a9e8-b7530ab28287") UUID productId,
                        @Valid AddOneNewProductImageReq addOneNewProductImageReq);

        @Operation(summary = "查詢商品圖片列表", description = "依 sortOrder 排序。")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "查詢成功"),
                        @ApiResponse(responseCode = "404", description = "商品不存在", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
        })
        GetProductImagesRes getProductImages(
                        @Parameter(description = "商品 id（UUIDv7）", example = "01a17c88-9d2b-7788-a9e8-b7530ab28287") UUID productId);

        @Operation(summary = "修改商品圖片資訊", description = "只可修改描述類欄位（如 altText），主圖與排序請使用專用端點；version 須與目前版本一致。")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "修改成功"),
                        @ApiResponse(responseCode = "400", description = "欄位驗證失敗，或商品已封存", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
                        @ApiResponse(responseCode = "404", description = "商品或圖片不存在", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
                        @ApiResponse(responseCode = "409", description = "version 不一致（已被他人修改）", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
        })
        UpdateOneProductImageRes updateOneProductImage(
                        @Parameter(description = "商品 id（UUIDv7）", example = "01a17c88-9d2b-7788-a9e8-b7530ab28287") UUID productId,
                        @Parameter(description = "圖片 id（UUIDv7）", example = "01a17c88-9d2b-7788-a9e8-b7530ab28287") UUID imageId,
                        @Valid UpdateOneProductImageReq updateOneProductImageReq);

        @Operation(summary = "刪除商品圖片", description = "上架中的商品不可刪除主圖，須先將其他圖片設為主圖。")
        @ApiResponses({
                        @ApiResponse(responseCode = "204", description = "刪除成功"),
                        @ApiResponse(responseCode = "400", description = "商品已封存，或欲刪除上架中商品的主圖", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
                        @ApiResponse(responseCode = "404", description = "商品或圖片不存在", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
        })
        void deleteOneProductImage(
                        @Parameter(description = "商品 id（UUIDv7）", example = "01a17c88-9d2b-7788-a9e8-b7530ab28287") UUID productId,
                        @Parameter(description = "圖片 id（UUIDv7）", example = "01a17c88-9d2b-7788-a9e8-b7530ab28287") UUID imageId);

        @Operation(summary = "設定主圖", description = "原主圖自動取消；回傳更新後的完整圖片列表。")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "設定成功"),
                        @ApiResponse(responseCode = "400", description = "商品已封存", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
                        @ApiResponse(responseCode = "404", description = "商品或圖片不存在", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
        })
        GetProductImagesRes setPrimaryProductImage(
                        @Parameter(description = "商品 id（UUIDv7）", example = "01a17c88-9d2b-7788-a9e8-b7530ab28287") UUID productId,
                        @Parameter(description = "圖片 id（UUIDv7）", example = "01a17c88-9d2b-7788-a9e8-b7530ab28287") UUID imageId);

        @Operation(summary = "調整圖片排序", description = "傳入該商品所有圖片 id 的新順序，須完整且不可重複；回傳更新後的完整圖片列表。")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "調整成功"),
                        @ApiResponse(responseCode = "400", description = "id 清單不完整、重複或含其他商品的圖片，或商品已封存", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
                        @ApiResponse(responseCode = "404", description = "商品不存在", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
        })
        GetProductImagesRes reorderProductImages(
                        @Parameter(description = "商品 id（UUIDv7）", example = "01a17c88-9d2b-7788-a9e8-b7530ab28287") UUID productId,
                        @Valid ReorderProductImagesReq reorderProductImagesReq);
}
