package com.mymicroservice.product.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mymicroservice.common.web.RequireRoles;
import com.mymicroservice.product.controller.api.CategoryApi;
import com.mymicroservice.product.dto.category.addOneNewCategory.AddOneNewCategoryReq;
import com.mymicroservice.product.dto.category.addOneNewCategory.AddOneNewCategoryRes;
import com.mymicroservice.product.dto.category.getCategoriesTree.GetCategoriesTreeRes;
import com.mymicroservice.product.dto.category.getCategoryById.GetCategoryByIdRes;
import com.mymicroservice.product.dto.category.getCategoryBySlug.GetCategoryBySlugRes;
import com.mymicroservice.product.dto.category.updateOneCategory.UpdateOneCategoryReq;
import com.mymicroservice.product.dto.category.updateOneCategory.UpdateOneCategoryRes;
import com.mymicroservice.product.service.CategoryService;

import lombok.RequiredArgsConstructor;

// API 文件說明與參數驗證請見 CategoryApi
// 讀取公開（Gateway 對 GET 放行）；新增、修改、刪除限 ADMIN（@RequireRoles）
@RestController
@RequestMapping(value = "/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController implements CategoryApi {
    private final CategoryService categoryService;

    @Override
    @RequireRoles("ADMIN")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(value = HttpStatus.CREATED)
    public AddOneNewCategoryRes addOneNewCategory(
            @RequestBody AddOneNewCategoryReq addOneNewCategoryReq) {
        return categoryService.addOneNewCategory(addOneNewCategoryReq);
    }

    @Override
    @GetMapping("/{id}")
    @ResponseStatus(value = HttpStatus.OK)
    public GetCategoryByIdRes getCategoryById(
            @PathVariable UUID id) {
        return categoryService.getCategoryById(id);
    }

    @Override
    @GetMapping("/slug/{slug}")
    @ResponseStatus(value = HttpStatus.OK)
    public GetCategoryBySlugRes getCategoryBySlug(
            @PathVariable String slug) {
        return categoryService.getCategoryBySlug(slug);
    }

    @Override
    @GetMapping("/tree")
    @ResponseStatus(value = HttpStatus.OK)
    public GetCategoriesTreeRes getCategoriesTree() {
        return categoryService.getCategoriesTree();
    }

    @Override
    @RequireRoles("ADMIN")
    @PutMapping("/{id}")
    @ResponseStatus(value = HttpStatus.OK)
    public UpdateOneCategoryRes updateOneCategory(
            @PathVariable UUID id,
            @RequestBody UpdateOneCategoryReq updateOneCategoryReq) {
        return categoryService.updateOneCategory(id, updateOneCategoryReq);
    }

    @Override
    @RequireRoles("ADMIN")
    @DeleteMapping("/{id}")
    @ResponseStatus(value = HttpStatus.NO_CONTENT)
    public ResponseEntity<String> deleteOneCategoryById(
            @PathVariable UUID id) {
        categoryService.deleteOneCategoryById(id);
        return new ResponseEntity<String>("delete successfully.", HttpStatus.OK);
    }
}
