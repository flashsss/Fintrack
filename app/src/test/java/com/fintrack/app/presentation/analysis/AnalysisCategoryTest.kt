package com.fintrack.app.presentation.analysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.core.common.MutableClock
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType
import com.fintrack.app.presentation.MainDispatcherRule
import com.fintrack.app.presentation.awaitValue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Category tab against Room, including live label updates (addendum B5). */
@RunWith(AndroidJUnit4::class)
class AnalysisCategoryTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val t = TestDatabase()
    private lateinit var food: String
    private lateinit var cafe: String
    private lateinit var transport: String

    @Before
    fun setUp() = runBlocking {
        val bca = t.wallets.createWallet("BCA", WalletType.BANK, 0).requireId()
        val other = t.wallets.createWallet("Mandiri", WalletType.BANK, 0).requireId()
        food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        cafe = t.categories.createCategory("Kafe", CategoryType.EXPENSE, food, null, null).requireId()
        transport = t.categories.createCategory("Transportasi", CategoryType.EXPENSE, null, null, null).requireId()
        suspend fun expense(category: String, amount: Long, at: String) =
            t.transactions.addTransaction(TransactionDraft(TransactionType.EXPENSE, bca, null, category, amount, Instant.parse(at))).requireId()
        expense(food, 400_000, "2026-11-03T03:00:00Z")
        expense(cafe, 100_000, "2026-11-04T03:00:00Z")
        expense(transport, 250_000, "2026-11-05T03:00:00Z")
        expense(transport, 200_000, "2026-10-05T03:00:00Z") // previous span
        t.transactions.addTransaction(TransactionDraft(TransactionType.TRANSFER, bca, other, null, 9_000_000, Instant.parse("2026-11-06T03:00:00Z"))).requireId()
        Unit
    }

    @After
    fun tearDown() = t.close()

    private fun viewModel() = AnalysisViewModel(t.reports, t.analytics, t.categories, t.todayProvider(MutableClock(Instant.parse("2026-11-25T03:00:00Z")))).apply {
        onPeriodChange(com.fintrack.app.analytics.period.AnalysisPeriod.ThisMonth)
        onTabChange(AnalysisTab.CATEGORY)
    }

    private suspend fun AnalysisViewModel.categories(predicate: (CategoryReport) -> Boolean = { true }) =
        (state.awaitValue { (it.content as? TabContent.Categories)?.report?.let(predicate) == true }.content as TabContent.Categories).report

    @Test
    fun `totals exclude transfers and roll children up to parents`() = runBlocking {
        val report = viewModel().categories()
        assertEquals(750_000L, report.totalExpense)
        assertEquals(500_000L, report.parents.single { it.categoryId == food }.rolledUp)
        assertEquals(mapOf(transport to 200_000L), report.previousById)
        assertEquals(50_000L, report.movers.single { it.categoryId == transport }.absolute)
    }

    @Test
    fun `renaming a category updates the label without new transactions`() = runBlocking {
        val vm = viewModel()
        vm.categories()
        t.categories.updateCategory(food, "Makan & Minum", null, null, null).requireId()
        val renamed = vm.categories { it.categoriesById[food]?.name == "Makan & Minum" }
        assertEquals(750_000L, renamed.totalExpense)
    }

    @Test
    fun `archiving keeps the category in historical analysis with its archived state`() = runBlocking {
        val vm = viewModel()
        vm.categories()
        t.categories.archiveCategory(transport).requireId()
        val archived = vm.categories { it.categoriesById[transport]?.isArchived == true }
        assertEquals(250_000L, archived.flat.single { it.categoryId == transport }.total)
    }

    @Test
    fun `moving a child to another parent changes parent totals but not the total`() = runBlocking {
        val vm = viewModel()
        vm.categories()
        t.categories.updateCategory(cafe, "Kafe", transport, null, null).requireId()
        val moved = vm.categories { r -> r.parents.single { it.categoryId == transport }.rolledUp == 350_000L }
        assertEquals(400_000L, moved.parents.single { it.categoryId == food }.rolledUp)
        assertEquals(750_000L, moved.parents.sumOf { it.rolledUp })
    }

    @Test
    fun `selecting a category produces its monthly series and selecting again clears it`() = runBlocking {
        val vm = viewModel()
        vm.categories()
        vm.onCategorySelected(transport)
        val selected = vm.categories { it.selectedCategoryId == transport }
        assertEquals(listOf(250_000L), selected.selectedMonthly.map { it.second })
        assertTrue(selected.lastMonthIsPartial)
        vm.onCategorySelected(transport)
        assertTrue(vm.categories { it.selectedCategoryId == null }.selectedMonthly.isEmpty())
    }
}
