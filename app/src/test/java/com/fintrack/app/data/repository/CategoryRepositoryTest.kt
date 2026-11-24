package com.fintrack.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.WriteResult
import com.fintrack.app.domain.validation.CategoryValidationError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryRepositoryTest {

    private val t = TestDatabase()

    @After
    fun tearDown() = t.close()

    private suspend fun create(name: String, type: CategoryType = CategoryType.EXPENSE, parentId: String? = null) =
        t.categories.createCategory(name, type, parentId, null, null)

    @Test
    fun `selectable list is filtered by type and excludes archived categories`() = runTest {
        val food = create("Makanan").requireId()
        create("Transportasi").requireId()
        create("Gaji", CategoryType.INCOME).requireId()

        t.categories.archiveCategory(food).requireId()

        val selectable = t.categories.observeSelectableCategories(CategoryType.EXPENSE).first()
        assertEquals(listOf("Transportasi"), selectable.map { it.name })
        assertTrue(t.categories.getCategory(food)!!.isArchived)
        assertEquals(3, t.categories.observeAllCategories().first().size)
    }

    @Test
    fun `child category is stored under its parent`() = runTest {
        val food = create("Makanan").requireId()
        val restaurant = create("Restoran", parentId = food).requireId()
        assertEquals(food, t.categories.getCategory(restaurant)!!.parentId)
    }

    @Test
    fun `parent rules are enforced against the database`() = runTest {
        val food = create("Makanan").requireId()
        val restaurant = create("Restoran", parentId = food).requireId()

        assertEquals(
            WriteResult.Invalid(listOf(CategoryValidationError.ParentIsChildCategory)),
            create("Kafe", parentId = restaurant),
        )
        assertEquals(
            WriteResult.Invalid(listOf(CategoryValidationError.ParentTypeMismatch)),
            create("Bonus", CategoryType.INCOME, parentId = food),
        )
        assertEquals(
            WriteResult.Invalid(listOf(CategoryValidationError.ParentNotFound)),
            create("Kafe", parentId = "missing"),
        )
    }

    @Test
    fun `category with children cannot be moved under another parent`() = runTest {
        val food = create("Makanan").requireId()
        create("Restoran", parentId = food).requireId()
        val transport = create("Transportasi").requireId()

        assertEquals(
            WriteResult.Invalid(listOf(CategoryValidationError.CategoryHasChildren)),
            t.categories.updateCategory(food, "Makanan", transport, null, null),
        )
    }

    @Test
    fun `update keeps type and changes editable fields`() = runTest {
        val food = create("Makanan").requireId()
        t.categories.updateCategory(food, "Makan & Minum", null, "restaurant", "#AA5500").requireId()
        val updated = t.categories.getCategory(food)!!
        assertEquals("Makan & Minum", updated.name)
        assertEquals(CategoryType.EXPENSE, updated.type)
        assertEquals("restaurant", updated.icon)
    }
}
