package com.fintrack.app.presentation.analysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.analytics.period.AnalysisPeriod
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class AnalysisMerchantTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val t = TestDatabase()

    @Before
    fun setUp() = runBlocking {
        val bca = t.wallets.createWallet("BCA", WalletType.BANK, 0).requireId()
        val other = t.wallets.createWallet("Mandiri", WalletType.BANK, 0).requireId()
        val food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        suspend fun expense(merchant: String?, amount: Long, at: String) =
            t.transactions.addTransaction(TransactionDraft(TransactionType.EXPENSE, bca, null, food, amount, Instant.parse(at), null, merchant)).requireId()
        // November 2026 (reported 1..25 Nov).
        expense("Indomaret", 100_000, "2026-11-02T03:00:00Z")
        expense("  INDOMARET ", 60_000, "2026-11-03T03:00:00Z")
        expense("Kopi Baru", 40_000, "2026-11-04T03:00:00Z")
        expense(null, 50_000, "2026-11-05T03:00:00Z")
        t.transactions.deleteTransaction(expense("Kopi Baru", 999_000, "2026-11-06T03:00:00Z")).requireId()
        t.transactions.addTransaction(TransactionDraft(TransactionType.TRANSFER, bca, other, null, 5_000_000, Instant.parse("2026-11-07T03:00:00Z"), null, "Indomaret")).requireId()
        // Lookback: Indomaret seen in March 2026 (inside 365 days); "Kopi Baru" only in 2025-01 (outside).
        expense("indomaret", 10_000, "2026-03-01T03:00:00Z")
        expense("Kopi Baru", 10_000, "2025-01-10T03:00:00Z")
        Unit
    }

    @After
    fun tearDown() = t.close()

    @Test
    fun `merchant tab merges variants, excludes transfers and deletions and finds new merchants`() = runBlocking {
        val vm = AnalysisViewModel(t.reports, t.analytics, t.categories, t.todayProvider(MutableClock(Instant.parse("2026-11-25T03:00:00Z"))))
        vm.onPeriodChange(AnalysisPeriod.ThisMonth)
        vm.onTabChange(AnalysisTab.MERCHANT)
        val report = (vm.state.awaitValue { it.content is TabContent.Merchants }.content as TabContent.Merchants).report
        assertEquals(listOf(160_000L, 40_000L), report.summary.merchants.map { it.total })
        assertEquals(2, report.summary.merchants.first().count)
        assertEquals(250_000L, report.summary.totalExpense)
        assertEquals(80.0, report.summary.coveragePercent!!, 1e-9)
        assertEquals(listOf("Kopi Baru"), report.newMerchants.map { it.displayName })
    }
}
