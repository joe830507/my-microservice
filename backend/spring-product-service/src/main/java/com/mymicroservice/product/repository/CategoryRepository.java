package com.mymicroservice.product.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mymicroservice.product.entity.Category;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    public boolean existsBySlug(String slug);

    public boolean existsByParentIdAndName(UUID parentId, String name);

    // 修改時使用，排除自己以免誤判為重複
    public boolean existsBySlugAndIdNot(String slug, UUID id);

    public boolean existsByParentIdAndNameAndIdNot(UUID parentId, String name, UUID id);

    public Optional<Category> findBySlugAndActiveTrue(String slug);

    public List<Category> findAllByActiveTrue();

    public boolean existsByIdAndActiveTrue(UUID id);
}