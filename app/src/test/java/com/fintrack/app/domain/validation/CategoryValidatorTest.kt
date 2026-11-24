package com.fintrack.app.domain.validation

import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.validation.CategoryValidationError.CategoryHasChildren
import com.fintrack.app.domain.validation.CategoryValidationError.NameBlank
import com.fintrack.app.domain.validation.CategoryValidationError.ParentArchived
import com.fintrack.app.domain.validation.CategoryValidationError.ParentIsChildCategory
import com.fintrack.app.domain.validation.CategoryValidationError.ParentIsSelf
import com.fintrack.app.domain.validation.CategoryValidationError.ParentNotFound
import com.fintrack.app.domain.validation.CategoryValidationError.ParentTypeMismatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryValidatorTest {

    private val food = ParentCategoryRef("food", CategoryType.EXPENSE, parentId = null, isArchived = false)

    @Test
    fun `top-level category passes`() {
        assertTrue(CategoryValidator.validate("Makanan", CategoryType.EXPENSE, null, null).isEmpty())
    }

    @Test
    fun `blank name is rejected`() {
        assertEquals(listOf(NameBlank), CategoryValidator.validate("  ", CategoryType.EXPENSE, null, null))
    }

    @Test
    fun `child of same-type parent passes`() {
        assertTrue(CategoryValidator.validate("Restoran", CategoryType.EXPENSE, "food", food).isEmpty())
    }

    @Test
    fun `parent with different type is rejected`() {
        assertEquals(
            listOf(ParentTypeMismatch),
            CategoryValidator.validate("Bonus", CategoryType.INCOME, "food", food),
        )
    }

    @Test
    fun `missing parent is rejected`() {
        assertEquals(listOf(ParentNotFound), CategoryValidator.validate("Restoran", CategoryType.EXPENSE, "x", null))
    }

    @Test
    fun `third level is rejected`() {
        val child = food.copy(id = "restaurant", parentId = "food")
        assertEquals(
            listOf(ParentIsChildCategory),
            CategoryValidator.validate("Kafe", CategoryType.EXPENSE, "restaurant", child),
        )
    }

    @Test
    fun `archived parent is rejected for new child`() {
        assertEquals(
            listOf(ParentArchived),
            CategoryValidator.validate("Restoran", CategoryType.EXPENSE, "food", food.copy(isArchived = true)),
        )
    }

    @Test
    fun `category cannot be its own parent`() {
        assertEquals(
            listOf(ParentIsSelf),
            CategoryValidator.validate("Makanan", CategoryType.EXPENSE, "food", food, categoryId = "food"),
        )
    }

    @Test
    fun `category with children cannot become a child`() {
        val transport = food.copy(id = "transport")
        assertEquals(
            listOf(CategoryHasChildren),
            CategoryValidator.validate(
                "Makanan", CategoryType.EXPENSE, "transport", transport, categoryId = "food", hasChildren = true,
            ),
        )
    }
}
