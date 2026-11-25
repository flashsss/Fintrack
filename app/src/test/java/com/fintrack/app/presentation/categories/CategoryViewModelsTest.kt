package com.fintrack.app.presentation.categories

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.R
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType
import com.fintrack.app.presentation.MainDispatcherRule
import com.fintrack.app.presentation.awaitValue
import com.fintrack.app.presentation.navigation.Routes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class CategoryViewModelsTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val t = TestDatabase()

    @After
    fun tearDown() = t.close()

    private fun newForm(type: CategoryType = CategoryType.EXPENSE) =
        CategoryFormViewModel(SavedStateHandle(mapOf(Routes.ARG_TYPE to type.name)), t.categories)

    private fun editForm(id: String) =
        CategoryFormViewModel(SavedStateHandle(mapOf(Routes.ARG_CATEGORY_ID to id)), t.categories)

    private suspend fun CategoryFormViewModel.saveAndAwait(): CategoryFormState {
        save()
        return state.awaitValue { !it.isSaving }
    }

    @Test
    fun `creates custom category with parent`() = runTest {
        t.categories.seedDefaultCategories()
        val food = t.categories.observeAllCategories().first().single { it.name == "Makanan dan minuman" }

        val form = newForm()
        val options = form.parentOptions.awaitValue { it.isNotEmpty() }
        assertTrue(options.none { it.label == "Gaji" })

        form.onNameChange("Kopi")
        form.onParentChange(food.id)
        assertTrue(form.saveAndAwait().isSaved)

        val kopi = t.categories.observeAllCategories().first().single { it.name == "Kopi" }
        assertEquals(food.id, kopi.parentId)
        assertFalse(kopi.isDefault)
    }

    @Test
    fun `invalid category submissions are reported`() = runTest {
        val form = newForm()
        assertEquals(R.string.error_name_required, form.saveAndAwait().nameError)

        val food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        val child = t.categories.createCategory("Restoran", CategoryType.EXPENSE, food, null, null).requireId()
        form.onNameChange("Kafe")
        form.onParentChange(child)
        assertEquals(R.string.error_parent_is_child, form.saveAndAwait().parentError)
    }

    @Test
    fun `edit keeps type and renames`() = runTest {
        val id = t.categories.createCategory("Bonus", CategoryType.INCOME, null, null, null).requireId()
        val form = editForm(id)
        form.state.awaitValue { !it.isLoading }
        form.onTypeChange(CategoryType.EXPENSE)
        form.onNameChange("Bonus tahunan")
        assertTrue(form.saveAndAwait().isSaved)

        val updated = t.categories.getCategory(id)!!
        assertEquals("Bonus tahunan", updated.name)
        assertEquals(CategoryType.INCOME, updated.type)
    }

    @Test
    fun `archive keeps historical references readable`() = runTest {
        val wallet = t.wallets.createWallet("Tunai", WalletType.CASH, 100_000).requireId()
        val food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        val tx = t.transactions.addTransaction(
            TransactionDraft(TransactionType.EXPENSE, wallet, null, food, 20_000, Instant.EPOCH),
        ).requireId()

        val form = editForm(food)
        form.state.awaitValue { !it.isLoading }
        form.archive()
        assertTrue(form.state.awaitValue { it.isArchivedNow }.isArchived)

        val list = CategoryListViewModel(t.categories).state.awaitValue { !it.isLoading }
        assertTrue(list.active.isEmpty())
        assertEquals(listOf("Makanan"), list.archived.map { it.name })
        assertEquals(food, t.transactions.getTransaction(tx)!!.categoryId)
        assertEquals("Makanan", t.categories.getCategory(food)!!.name)
    }

    @Test
    fun `list shows parents followed by children for the selected type`() = runTest {
        val food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        t.categories.createCategory("Restoran", CategoryType.EXPENSE, food, null, null).requireId()
        t.categories.createCategory("Belanja", CategoryType.EXPENSE, null, null, null).requireId()
        t.categories.createCategory("Gaji", CategoryType.INCOME, null, null, null).requireId()

        val vm = CategoryListViewModel(t.categories)
        val expense = vm.state.awaitValue { !it.isLoading }
        assertEquals(listOf("Belanja", "Makanan", "Restoran"), expense.active.map { it.name })
        assertEquals(listOf(false, false, true), expense.active.map { it.isChild })

        vm.onTypeChange(CategoryType.INCOME)
        assertEquals(listOf("Gaji"), vm.state.awaitValue { it.type == CategoryType.INCOME }.active.map { it.name })
    }

    @Test
    fun `active child of archived parent stays listed`() = runTest {
        val food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        t.categories.createCategory("Restoran", CategoryType.EXPENSE, food, null, null).requireId()
        t.categories.archiveCategory(food).requireId()

        val state = CategoryListViewModel(t.categories).state.awaitValue { !it.isLoading }
        assertEquals(listOf("Restoran"), state.active.map { it.name })
        assertEquals(listOf("Makanan"), state.archived.map { it.name })
    }
}
