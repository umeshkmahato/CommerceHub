package com.commercehub.product.repository;

import com.commercehub.product.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Category lookups — small table, safe to cache results in service layer. */
public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findBySlug(String slug);

    /** Top-level categories for storefront navigation (parent_id IS NULL). */
    List<Category> findByParentIsNullOrderByNameAsc();
}
