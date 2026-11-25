package com.fintrack.app.presentation.dashboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.analytics.metrics.PercentChange
import com.fintrack.app.analytics.metrics.PeriodTotals
import com.fintrack.app.analytics.period.ReportPeriod
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType
import com.fintrack.app.presentation.MainDispatcherRule
import com.fintrack.app.presentation.awaitValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class DashboardViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val t = TestDatabase()

    // 25 Nov 2026, 10:00 WIB.
    private val clock = Clock.fixed(Instant.parse("2026-11-25T03:00:00Z"), ZoneOffset.UTC)

    @After
    fun tearDown() = t.close()

    private fun viewModel() = DashboardViewModel(t.reports, t.wallets, t.categories, clock)

    private suspend fun seed(): Map<String, String> {
        val bca = t.wallets.createWallet("BCA", WalletType.BANK, 10_000_000).requireId()
        val mandiri = t.wallets.createWallet("Mandiri", WalletType.BANK, 5_000_000).requireId()
        val salary = t.categories.createCategory("Gaji", CategoryType.INCOME, null, null, null).requireId()
        val food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        suspend fun add(type: TransactionType, amount: Long, at: String, category: String?, dest: String? = null) =
            t.transactions.addTransaction(TransactionDraft(type, bca, dest, category, amount, Instant.parse(at))).requireId()

        // October: income 4.000.000 by the 25th, plus 1.000.000 later; expense 1.000.000 by the 25th.
        add(TransactionType.INCOME, 4_000_000, "2026-10-05T03:00:00Z", salary)
        add(TransactionType.EXPENSE, 1_000_000, "2026-10-20T03:00:00Z", food)
        add(TransactionType.INCOME, 1_000_000, "2026-10-28T03:00:00Z", salary)
        // November (until the 25th): income 5.000.000, expense 2.000.000, transfer 3.000.000.
        add(TransactionType.INCOME, 5_000_000, "2026-11-01T03:00:00Z", salary)
        add(TransactionType.EXPENSE, 2_000_000, "2026-11-10T03:00:00Z", food)
        val transfer = add(TransactionType.TRANSFER, 3_000_000, "2026-11-24T03:00:00Z", null, dest = mandiri)
        return mapOf("bca" to bca, "mandiri" to mandiri, "transfer" to transfer)
    }

    @Test
    fun `empty dashboard has no transactions and zero totals`() = runTest {
        val state = viewModel().state.awaitValue { !it.isLoading }
        assertFalse(state.hasAnyTransactions)
        assertEquals(PeriodTotals.EMPTY, state.report!!.totals)
        assertTrue(state.report.breakdown.isEmpty())
        assertEquals(0L, state.wallets!!.total.total)
        assertTrue(state.recent.isEmpty())
        // The daily series still spans the elapsed days, all zero; nothing beyond today.
        assertEquals(25, state.report.daily.size)
        assertTrue(state.report.daily.all { it.income == 0L && it.expense == 0L })
    }

    @Test
    fun `this month dashboard aggregates from the database`() = runTest {
        val ids = seed()
        val state = viewModel().state.awaitValue { !it.isLoading && it.hasAnyTransactions }
        val report = state.report!!

        assertEquals(PeriodTotals(income = 5_000_000, expense = 2_000_000, transactionCount = 3, transferCount = 1), report.totals)
        assertEquals(3_000_000L, report.totals!!.netCashFlow)
        assertEquals(60.0, report.totals.savingRatePercent!!, 1e-9)

        // Balances: BCA 10M + 5M + 4M + 1M - 1M - 2M - 3M = 14M; Mandiri 5M + 3M = 8M.
        assertEquals(22_000_000L, state.wallets!!.total.total)
        assertEquals(mapOf(ids["bca"] to 14_000_000L, ids["mandiri"] to 8_000_000L), state.wallets.wallets.associate { it.first.id to it.second })

        assertEquals(LocalDate.of(2026, 11, 25), report.daily.last().date)
        assertEquals(5_000_000L, report.daily.first().income)
        assertEquals(0L, report.daily.single { it.date == LocalDate.of(2026, 11, 24) }.expense) // transfer day

        assertEquals(listOf("Makanan"), report.breakdown.map { it.label })
        assertEquals(100.0, report.breakdown.single().slice.percent, 1e-9)

        // Same elapsed days: 1-25 Nov vs 1-25 Oct; the 28 Oct income is only in the full month.
        val comparison = report.comparison!!
        assertEquals(PercentChange.Value(25.0), comparison.income.percent)
        assertEquals(1_000_000L, comparison.income.absolute)
        assertEquals(PercentChange.Value(100.0), comparison.expense.percent)
        assertEquals(PeriodTotals(5_000_000, 1_000_000, 3, 0), comparison.previousFullMonth)

        assertEquals(ids["transfer"], state.recent.first().row.id)
        assertEquals("BCA → Mandiri", state.recent.first().row.walletLabel)
        assertEquals(5, state.recent.size)
    }

    @Test
    fun `changing the period updates every period-dependent part but not balances`() = runTest {
        seed()
        val vm = viewModel()
        val before = vm.state.awaitValue { it.hasAnyTransactions }

        vm.onPeriodChange(ReportPeriod.LastMonth)
        val october = vm.state.awaitValue { it.selectedPeriod == ReportPeriod.LastMonth && it.report?.period?.first == LocalDate.of(2026, 10, 1) }
        val report = october.report!!
        assertEquals(PeriodTotals(5_000_000, 1_000_000, 3, 0), report.totals)
        assertEquals(31, report.daily.size)
        assertEquals(PercentChange.ZeroBaseline, report.comparison!!.income.percent) // September is empty
        assertEquals(before.wallets, october.wallets)

        vm.onPeriodChange(ReportPeriod.Custom(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 12, 5)))
        val custom = vm.state.awaitValue { it.selectedPeriod is ReportPeriod.Custom }.report!!
        assertEquals(LocalDate.of(2026, 11, 25), custom.period.reportedLast)
        assertEquals(6, custom.daily.size)
        assertNull(custom.comparison)
        assertEquals(1, custom.totals!!.transactionCount)
    }

    @Test
    fun `future custom period reports nothing`() = runTest {
        seed()
        val vm = viewModel()
        vm.onPeriodChange(ReportPeriod.Custom(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31)))
        val report = vm.state.awaitValue { it.selectedPeriod is ReportPeriod.Custom }.report!!
        assertNull(report.totals)
        assertTrue(report.daily.isEmpty())
    }

    @Test
    fun `archived category stays visible in the breakdown`() = runTest {
        seed()
        val food = t.categories.observeAllCategories().first().single { it.name == "Makanan" }
        t.categories.archiveCategory(food.id).requireId()
        val entry = viewModel().state.awaitValue { it.hasAnyTransactions }.report!!.breakdown.single()
        assertEquals("Makanan", entry.label)
        assertTrue(entry.isArchived)
    }
}
