package com.mymicroservice.product.service;

import java.util.UUID;

import com.mymicroservice.product.dto.category.addOneNewCategory.AddOneNewCategoryReq;
import com.mymicroservice.product.dto.category.addOneNewCategory.AddOneNewCategoryRes;
import com.mymicroservice.product.dto.category.getAllCategories.GetAllCategoriesRes;
import com.mymicroservice.product.dto.category.getCategoriesTree.GetCategoriesTreeRes;
import com.mymicroservice.product.dto.category.getCategoryById.GetCategoryByIdRes;
import com.mymicroservice.product.dto.category.getCategoryBySlug.GetCategoryBySlugRes;
import com.mymicroservice.product.dto.category.updateOneCategory.UpdateOneCategoryReq;
import com.mymicroservice.product.dto.category.updateOneCategory.UpdateOneCategoryRes;

public interface CategoryService {

    public AddOneNewCategoryRes addOneNewCategory(AddOneNewCategoryReq addOneNewCategoryReq);

    public GetCategoryByIdRes getCategoryById(UUID id);

    public GetCategoryBySlugRes getCategoryBySlug(String slug);

    public GetCategoriesTreeRes getCategoriesTree();

    public GetAllCategoriesRes getAllCategories();

    public UpdateOneCategoryRes updateOneCategory(UUID id, UpdateOneCategoryReq updateOneCategoryReq);

    public void deleteOneCategoryById(UUID id);
}
