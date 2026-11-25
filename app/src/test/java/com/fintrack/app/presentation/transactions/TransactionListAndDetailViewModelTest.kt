package com.fintrack.app.presentation.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TransactionListAndDetailViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    // The test clock is 2026-01-01T00:00Z, i.e. 1 January 2026 07:00 in Jakarta.
    private val t = TestDatabase()
    private lateinit var bca: String
    private lateinit var mandiri: String
    private lateinit var food: String

    @Before
    fun setUp() = runTest {
        bca = t.wallets.createWallet("BCA", WalletType.BANK, 10_000_000).requireId()
        mandiri = t.wallets.createWallet("Mandiri", WalletType.BANK, 5_000_000).requireId()
        food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
    }

    @After
    fun tearDown() = t.close()

    private suspend fun expense(at: String, description: String) = t.transactions.addTransaction(
        TransactionDraft(TransactionType.EXPENSE, bca, null, food, 10_000, Instant.parse(at), description),
    ).requireId()

    private fun list() = TransactionListViewModel(t.transactions, t.wallets, t.categories, t.clock)

    private fun detail(id: String) = TransactionDetailViewModel(
        SavedStateHandle(mapOf(Routes.ARG_TRANSACTION_ID to id)), t.transactions, t.wallets, t.categories,
    )

    @Test
    fun `history groups by Jakarta day and shows transfer once and neutrally`() = runTest {
        expense("2025-12-31T18:00:00Z", "pagi 1 Jan WIB")
        t.transactions.addTransaction(
            TransactionDraft(TransactionType.TRANSFER, bca, mandiri, null, 3_000_000, Instant.parse("2025-12-31T10:00:00Z")),
        ).requireId()

        val state = list().state.awaitValue { !it.isLoading }
        assertEquals(listOf(LocalDate.of(2026, 1, 1), LocalDate.of(2025, 12, 31)), state.groups.map { it.date })
        val transfer = state.groups.last().rows.single()
        assertEquals(null, transfer.categoryLabel)
        assertEquals("BCA → Mandiri", transfer.walletLabel)
        assertEquals(AmountDirection.NEUTRAL, transfer.direction)
        assertEquals("Rp3.000.000", transfer.amountText)
    }

    @Test
    fun `search and period filters narrow the history`() = runTest {
        expense("2025-12-15T05:00:00Z", "Kopi Desember")
        expense("2026-01-01T05:00:00Z", "Kopi Januari")
        expense("2026-01-01T06:00:00Z", "Bensin")
        val vm = list()
        vm.state.awaitValue { !it.isLoading && it.groups.sumOf { g -> g.rows.size } == 3 }

        vm.onQueryChange("kopi")
        assertEquals("kopi", vm.query.value)
        vm.state.awaitValue { it.query == "kopi" && it.groups.sumOf { g -> g.rows.size } == 2 }

        vm.onPeriodChange(PeriodFilter.ThisMonth)
        val thisMonth = vm.state.awaitValue { it.period == PeriodFilter.ThisMonth }
        assertEquals(listOf("Kopi Januari"), thisMonth.groups.flatMap { g -> g.rows.map { it.note } })
        assertTrue(thisMonth.isFiltered)

        vm.onPeriodChange(PeriodFilter.LastMonth)
        val lastMonth = vm.state.awaitValue { it.period == PeriodFilter.LastMonth }
        assertEquals(listOf("Kopi Desember"), lastMonth.groups.flatMap { g -> g.rows.map { it.note } })

        vm.onQueryChange("")
        vm.onPeriodChange(PeriodFilter.Custom(LocalDate.of(2025, 12, 1), LocalDate.of(2026, 1, 1)))
        vm.state.awaitValue { it.period is PeriodFilter.Custom && it.groups.sumOf { g -> g.rows.size } == 3 }
    }

    @Test
    fun `detail shows archived wallet and category of a historical transaction`() = runTest {
        val id = expense("2026-01-01T05:00:00Z", "Makan siang")
        t.wallets.archiveWallet(bca).requireId()
        t.categories.archiveCategory(food).requireId()

        val detail = detail(id).state.awaitValue { !it.isLoading }.detail!!
        assertEquals(ReferenceLabel("BCA", isArchived = true), detail.wallet)
        assertEquals(ReferenceLabel("Makanan", isArchived = true), detail.category)
        assertEquals("Rp10.000", detail.amountText)
        assertEquals("1 Jan 2026", detail.dateText)
    }

    @Test
    fun `delete from detail soft deletes and restores balance`() = runTest {
        val id = expense("2026-01-01T05:00:00Z", "Makan siang")
        val vm = detail(id)
        vm.state.awaitValue { it.detail != null }

        vm.delete()
        vm.deleteState.awaitValue { it.isDeleted }
        assertEquals(10_000_000L, t.wallets.observeBalances().first().single { it.walletId == bca }.balance)
        assertEquals(null, t.transactions.getTransaction(id))
        t.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM transactions WHERE id = ?", arrayOf(id)).use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
    }
}
