package com.mymicroservice.product.controller.api;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import com.mymicroservice.product.dto.category.addOneNewCategory.AddOneNewCategoryReq;
import com.mymicroservice.product.dto.category.addOneNewCategory.AddOneNewCategoryRes;
import com.mymicroservice.product.dto.category.getAllCategories.GetAllCategoriesRes;
import com.mymicroservice.product.dto.category.getCategoriesTree.GetCategoriesTreeRes;
import com.mymicroservice.product.dto.category.getCategoryById.GetCategoryByIdRes;
import com.mymicroservice.product.dto.category.getCategoryBySlug.GetCategoryBySlugRes;
import com.mymicroservice.product.dto.category.updateOneCategory.UpdateOneCategoryReq;
import com.mymicroservice.product.dto.category.updateOneCategory.UpdateOneCategoryRes;

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
@Tag(name = "Category", description = "商品分類管理")
public interface CategoryApi {

    @Operation(summary = "新增分類", description = "parentId 為 null 時建立頂層分類；sortOrder 未傳入時預設為 0，active 未傳入時預設為 true。")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "新增成功"),
            @ApiResponse(responseCode = "400", description = "欄位驗證失敗，或 parent 不存在",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "slug 重複，或同一個 parent 下已有同名分類",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    AddOneNewCategoryRes addOneNewCategory(@Valid AddOneNewCategoryReq addOneNewCategoryReq);

    @Operation(summary = "取得所有分類（後台用）", description = "限 ADMIN。回傳平面列表，包含停用的分類；前端依 parentId 組成樹。依 sortOrder、name 排序，不分頁。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查詢成功"),
            @ApiResponse(responseCode = "401", description = "沒有身分",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "不是 ADMIN",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    GetAllCategoriesRes getAllCategories();

    @Operation(summary = "依 id 查詢分類（後台用）", description = "回傳完整資料，包含停用的分類與 version。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查詢成功"),
            @ApiResponse(responseCode = "404", description = "分類不存在",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    GetCategoryByIdRes getCategoryById(
            @Parameter(description = "分類 id（UUIDv7）", example = "01a17c88-9d2b-7788-a9e8-b7530ab28287") UUID id);

    @Operation(summary = "依 slug 查詢分類（前台用）", description = "只查詢啟用中的分類，停用的分類視同不存在。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查詢成功"),
            @ApiResponse(responseCode = "404", description = "分類不存在或已停用",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    GetCategoryBySlugRes getCategoryBySlug(
            @Parameter(description = "分類 slug", example = "mens-shoes") String slug);

    @Operation(summary = "取得分類樹（前台用）", description = "只包含啟用中的分類，父分類停用時其子樹一併隱藏；同一層依 sortOrder、name 排序。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查詢成功")
    })
    GetCategoriesTreeRes getCategoriesTree();

    @Operation(summary = "修改分類", description = "整筆取代，所有可編輯欄位皆須帶入；version 須與目前版本一致，成功後回傳新的 version。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "修改成功"),
            @ApiResponse(responseCode = "400", description = "欄位驗證失敗、parent 不存在，或 parent 為自己或自己的子孫",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "分類不存在",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "version 不一致（已被他人修改）、slug 重複，或同一個 parent 下已有同名分類",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    UpdateOneCategoryRes updateOneCategory(
            @Parameter(description = "分類 id（UUIDv7）", example = "01a17c88-9d2b-7788-a9e8-b7530ab28287") UUID id,
            @Valid UpdateOneCategoryReq updateOneCategoryReq);

    @Operation(summary = "刪除分類")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "刪除成功",
                    content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    })
    ResponseEntity<String> deleteOneCategoryById(
            @Parameter(description = "分類 id（UUIDv7）", example = "01a17c88-9d2b-7788-a9e8-b7530ab28287") UUID id);
}
