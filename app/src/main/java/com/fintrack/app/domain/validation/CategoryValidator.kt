package com.fintrack.app.domain.validation

import com.fintrack.app.domain.model.CategoryType

sealed interface CategoryValidationError {
    data object NameBlank : CategoryValidationError
    data object ParentNotFound : CategoryValidationError
    data object ParentArchived : CategoryValidationError
    data object ParentTypeMismatch : CategoryValidationError
    /** Categories have at most two levels: a parent and its direct children. */
    data object ParentIsChildCategory : CategoryValidationError
    data object ParentIsSelf : CategoryValidationError
    data object CategoryHasChildren : CategoryValidationError
}

/** Minimal view of a parent category. Null means missing or soft-deleted. */
data class ParentCategoryRef(
    val id: String,
    val type: CategoryType,
    val parentId: String?,
    val isArchived: Boolean,
)

object CategoryValidator {

    /**
     * @param categoryId id of the category being edited, or null when creating.
     * @param hasChildren whether the edited category already has child categories.
     */
    fun validate(
        name: String,
        type: CategoryType,
        parentId: String?,
        parent: ParentCategoryRef?,
        categoryId: String? = null,
        hasChildren: Boolean = false,
        currentParentId: String? = null,
    ): List<CategoryValidationError> {
        val errors = mutableListOf<CategoryValidationError>()
        if (name.isBlank()) errors += CategoryValidationError.NameBlank
        if (parentId == null) return errors

        when {
            parentId == categoryId -> errors += CategoryValidationError.ParentIsSelf
            parent == null -> errors += CategoryValidationError.ParentNotFound
            else -> {
                if (parent.type != type) errors += CategoryValidationError.ParentTypeMismatch
                if (parent.parentId != null) errors += CategoryValidationError.ParentIsChildCategory
                if (parent.isArchived && parent.id != currentParentId) {
                    errors += CategoryValidationError.ParentArchived
                }
            }
        }
        if (hasChildren) errors += CategoryValidationError.CategoryHasChildren
        return errors
    }
}
