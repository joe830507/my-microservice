package com.mymicroservice.product.service.impl;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mymicroservice.product.cache.CategoryTreeCache;
import com.mymicroservice.product.dto.category.addOneNewCategory.AddOneNewCategoryReq;
import com.mymicroservice.product.dto.category.addOneNewCategory.AddOneNewCategoryRes;
import com.mymicroservice.product.dto.category.getCategoriesTree.CategoryTreeNode;
import com.mymicroservice.product.dto.category.getCategoriesTree.GetCategoriesTreeRes;
import com.mymicroservice.product.dto.category.getCategoryById.GetCategoryByIdRes;
import com.mymicroservice.product.dto.category.getCategoryBySlug.GetCategoryBySlugRes;
import com.mymicroservice.product.dto.category.updateOneCategory.UpdateOneCategoryReq;
import com.mymicroservice.product.dto.category.updateOneCategory.UpdateOneCategoryRes;
import com.mymicroservice.product.entity.Category;
import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.common.exception.CommonErrorCode;
import com.mymicroservice.product.exception.ProductErrorCode;
import com.mymicroservice.product.repository.CategoryRepository;
import com.mymicroservice.product.service.CategoryService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class CategoryServiceImpl implements CategoryService {
    // 同一層依 sortOrder 排序，相同時再依 name，確保前端拿到的順序穩定
    private static final Comparator<Category> SIBLING_ORDER = Comparator
            .comparing(Category::getSortOrder)
            .thenComparing(Category::getName);

    private final CategoryRepository categoryRepository;
    private final CategoryTreeCache categoryTreeCache;

    @Override
    public AddOneNewCategoryRes addOneNewCategory(AddOneNewCategoryReq addOneNewCategoryReq) {
        // 1. 檢查parentId是否存在
        if (addOneNewCategoryReq.parentId() != null
                && !categoryRepository.existsById(addOneNewCategoryReq.parentId())) {
            throw new AppException(ProductErrorCode.CATEGORY_PARENT_NOT_FOUND);
        }

        // 2. 驗證slug是否已經存在
        if (categoryRepository.existsBySlug(addOneNewCategoryReq.slug())) {
            throw new AppException(ProductErrorCode.CATEGORY_SLUG_DUPLICATED);
        }

        // 3. 同一個parent下是否有同樣的name
        if (categoryRepository.existsByParentIdAndName(addOneNewCategoryReq.parentId(),
                addOneNewCategoryReq.name())) {
            throw new AppException(ProductErrorCode.CATEGORY_NAME_DUPLICATED);
        }

        Category savedCategory = categoryRepository.save(addOneNewCategoryReq.toEntity());
        categoryTreeCache.evictAfterCommit();
        return AddOneNewCategoryRes.from(savedCategory);
    }

    @Override
    public void deleteOneCategoryById(UUID id) {
        categoryRepository.deleteById(id);
        categoryTreeCache.evictAfterCommit();
    }

    @Override
    @Transactional(readOnly = true)
    public GetCategoryByIdRes getCategoryById(UUID id) {
        return categoryRepository.findById(id)
                .map(GetCategoryByIdRes::from)
                .orElseThrow(() -> new AppException(ProductErrorCode.CATEGORY_NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public GetCategoryBySlugRes getCategoryBySlug(String slug) {
        // 前台使用，停用的分類視同不存在
        return categoryRepository.findBySlugAndActiveTrue(slug)
                .map(GetCategoryBySlugRes::from)
                .orElseThrow(() -> new AppException(ProductErrorCode.CATEGORY_NOT_FOUND));
    }

    // 不開交易：快取命中時不需要拿 DB 連線；查 DB 時由 repository 自己開唯讀交易
    // 組樹只用到 id、name、slug、parentId，沒有 lazy 關聯，在交易外讀取不會出錯
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public GetCategoriesTreeRes getCategoriesTree() {
        return categoryTreeCache.get().orElseGet(() -> {
            GetCategoriesTreeRes tree = loadCategoriesTree();
            categoryTreeCache.put(tree);
            return tree;
        });
    }

    private GetCategoriesTreeRes loadCategoriesTree() {
        // 前台使用，只取啟用中的分類；父分類停用時，其子樹也會一併隱藏
        List<Category> categories = categoryRepository.findAllByActiveTrue();

        // groupingBy 不接受 null key，所以頂層分類要另外挑出來
        List<Category> roots = categories.stream()
                .filter(category -> category.getParentId() == null)
                .toList();
        Map<UUID, List<Category>> childrenByParentId = categories.stream()
                .filter(category -> category.getParentId() != null)
                .collect(Collectors.groupingBy(Category::getParentId));

        return new GetCategoriesTreeRes(toTreeNodes(roots, childrenByParentId));
    }

    private List<CategoryTreeNode> toTreeNodes(List<Category> categories,
            Map<UUID, List<Category>> childrenByParentId) {
        return categories.stream()
                .sorted(SIBLING_ORDER)
                .map(category -> toTreeNode(category, childrenByParentId))
                .toList();
    }

    // 從頂層往下遞迴，連不到頂層的分類（例如循環引用）不會出現在樹中
    private CategoryTreeNode toTreeNode(Category category, Map<UUID, List<Category>> childrenByParentId) {
        List<Category> children = childrenByParentId.getOrDefault(category.getId(), List.of());
        return new CategoryTreeNode(
                category.getId(),
                category.getName(),
                category.getSlug(),
                toTreeNodes(children, childrenByParentId));
    }

    @Override
    public UpdateOneCategoryRes updateOneCategory(UUID id, UpdateOneCategoryReq updateOneCategoryReq) {
        // 1. 驗證是否存在
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new AppException(ProductErrorCode.CATEGORY_NOT_FOUND));

        // 2. 樂觀鎖：前端帶回的版本須與目前版本一致，避免覆蓋他人的修改
        if (!category.getVersion().equals(updateOneCategoryReq.version())) {
            throw new AppException(CommonErrorCode.VERSION_CONFLICT);
        }

        // 3. 檢查新的parent是否存在，且不可為自己或自己的子孫
        if (updateOneCategoryReq.parentId() != null) {
            validateNewParent(id, updateOneCategoryReq.parentId());
        }

        // 4. 驗證slug是否與其他分類重複
        if (categoryRepository.existsBySlugAndIdNot(updateOneCategoryReq.slug(), id)) {
            throw new AppException(ProductErrorCode.CATEGORY_SLUG_DUPLICATED);
        }

        // 5. 同一個parent下是否有其他分類同名
        if (categoryRepository.existsByParentIdAndNameAndIdNot(updateOneCategoryReq.parentId(),
                updateOneCategoryReq.name(), id)) {
            throw new AppException(ProductErrorCode.CATEGORY_NAME_DUPLICATED);
        }

        // category 為 managed 狀態，不需 save；flush 後 updatedAt 與 version 才會更新
        updateOneCategoryReq.applyTo(category);
        categoryRepository.flush();
        categoryTreeCache.evictAfterCommit();
        return UpdateOneCategoryRes.from(category);
    }

    // 從新的 parent 沿著 parentId 往上走到頂層，途中遇到自己代表會形成循環
    private void validateNewParent(UUID id, UUID newParentId) {
        Set<UUID> visited = new HashSet<>();
        UUID currentId = newParentId;
        while (currentId != null) {
            if (currentId.equals(id)) {
                throw new AppException(ProductErrorCode.CATEGORY_PARENT_INVALID);
            }
            // 防止 DB 中已存在的循環資料造成無窮迴圈
            if (!visited.add(currentId)) {
                break;
            }
            currentId = categoryRepository.findById(currentId)
                    .orElseThrow(() -> new AppException(ProductErrorCode.CATEGORY_PARENT_NOT_FOUND))
                    .getParentId();
        }
    }
}
