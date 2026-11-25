package com.fintrack.app.presentation.analysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.analytics.metrics.DateSpan
import com.fintrack.app.analytics.metrics.PercentChange
import com.fintrack.app.analytics.period.AnalysisPeriod
import com.fintrack.app.analytics.trends.Granularity
import com.fintrack.app.core.common.MutableClock
import com.fintrack.app.core.common.TodayProvider
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType
import com.fintrack.app.presentation.MainDispatcherRule
import com.fintrack.app.presentation.awaitValue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class AnalysisTrendTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val t = TestDatabase()

    // 25 Nov 2026, 10:00 WIB.
    private val clock = MutableClock(Instant.parse("2026-11-25T03:00:00Z"))
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private fun d(text: String) = LocalDate.parse(text)

    @Before
    fun setUp() = runBlocking {
        val bca = t.wallets.createWallet("BCA", WalletType.BANK, 0).requireId()
        val old = t.wallets.createWallet("Lama", WalletType.CASH, 0).requireId()
        val mandiri = t.wallets.createWallet("Mandiri", WalletType.BANK, 0).requireId()
        val salary = t.categories.createCategory("Gaji", CategoryType.INCOME, null, null, null).requireId()
        val food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        suspend fun add(type: TransactionType, amount: Long, at: String, wallet: String = bca, category: String? = null, dest: String? = null) =
            t.transactions.addTransaction(TransactionDraft(type, wallet, dest, category, amount, Instant.parse(at))).requireId()
        // June–August (the 3 months before September) for the previous-span comparison.
        add(TransactionType.INCOME, 9_000_000, "2026-07-01T03:00:00Z", category = salary)
        add(TransactionType.EXPENSE, 3_000_000, "2026-08-10T03:00:00Z", category = food)
        // September–November 2026.
        add(TransactionType.INCOME, 10_000_000, "2026-09-01T03:00:00Z", category = salary)
        add(TransactionType.EXPENSE, 1_000_000, "2026-09-30T16:59:59Z", category = food) // 30 Sep 23:59:59 WIB
        add(TransactionType.EXPENSE, 2_000_000, "2026-09-30T17:00:00Z", category = food) // 1 Oct 00:00 WIB
        add(TransactionType.EXPENSE, 500_000, "2026-11-20T03:00:00Z", wallet = old, category = food) // archived wallet later
        add(TransactionType.TRANSFER, 4_000_000, "2026-10-15T03:00:00Z", dest = mandiri)
        t.transactions.deleteTransaction(add(TransactionType.EXPENSE, 999_000, "2026-11-01T03:00:00Z", category = food)).requireId()
        add(TransactionType.EXPENSE, 777_000, "2026-11-28T03:00:00Z", category = food) // future-dated
        t.wallets.archiveWallet(old).requireId()
        Unit
    }

    @After
    fun tearDown() = t.close()

    private fun viewModel() = AnalysisViewModel(t.reports, TodayProvider(clock, changes, emptyFlow()))

    private suspend fun AnalysisViewModel.trend() =
        (state.awaitValue { it.content is TabContent.Trend }.content as TabContent.Trend).report

    @Test
    fun `default is the last three months with monthly buckets summing to the period total`() = runBlocking {
        val report = viewModel().trend()
        assertEquals(DateSpan(d("2026-09-01"), d("2026-11-25")), report.reported)
        assertEquals(Granularity.MONTHLY, report.granularity)
        assertEquals(listOf(d("2026-09-01"), d("2026-10-01"), d("2026-11-01")), report.buckets.map { it.first })
        // Transfer, soft-deleted and future-dated rows excluded; archived wallet's expense included.
        assertEquals(10_000_000L, report.totals.income)
        assertEquals(3_500_000L, report.totals.expense)
        assertEquals(listOf(1_000_000L, 2_000_000L, 500_000L), report.buckets.map { it.expense })
        assertEquals(report.totals.expense, report.buckets.sumOf { it.expense })
        assertEquals(listOf(false, false, true), report.buckets.map { it.isPartial })
        // Ē = 3.500.000 / 86 reported days (1 Sep .. 25 Nov).
        assertEquals(86, report.reportedDays)
        assertEquals(3_500_000.0 / 86, report.averageDailyExpense!!, 1e-6)
        // Only September and October are complete months.
        assertEquals(1_500_000.0, report.averageFullBucketExpense!!, 1e-6)
    }

    @Test
    fun `previous span has the same length and comparison uses its totals`() = runBlocking {
        val report = viewModel().trend()
        assertEquals(DateSpan(d("2026-06-07"), d("2026-08-31")), report.previous) // 86 days before 1 Sep
        assertEquals(PercentChange.Value((10_000_000.0 - 9_000_000) * 100 / 9_000_000), report.income.percent)
        assertEquals(500_000L, report.expense.absolute)
        // Saving rate 65% now vs 66,67% before: about -1,67 points.
        assertEquals(65.0 - 6_000_000.0 * 100 / 9_000_000, report.savingRatePointChange!!, 1e-9)
    }

    @Test
    fun `granularity switch keeps totals and daily is coerced to weekly beyond 62 days`() = runBlocking {
        val vm = viewModel()
        vm.onGranularityChange(Granularity.DAILY)
        val coerced = vm.state.awaitValue { (it.content as? TabContent.Trend)?.report?.granularity == Granularity.WEEKLY }
        val weekly = (coerced.content as TabContent.Trend).report
        assertEquals(3_500_000L, weekly.buckets.sumOf { it.expense })
        assertTrue("1 Sep 2026 is a Tuesday, so the first ISO week is clipped", weekly.buckets.first().isPartial)

        vm.onPeriodChange(AnalysisPeriod.ThisMonth)
        val daily = (vm.state.awaitValue { s -> s.period == AnalysisPeriod.ThisMonth && (s.content as? TabContent.Trend)?.report?.granularity == Granularity.DAILY }.content as TabContent.Trend).report
        assertEquals(25, daily.buckets.size)
        assertEquals(500_000L, daily.buckets.single { it.first == d("2026-11-20") }.expense)
        assertEquals(DateSpan(d("2026-10-01"), d("2026-10-25")), daily.previous)
    }

    @Test
    fun `unavailable periods explain why`() = runBlocking {
        val vm = viewModel()
        vm.onPeriodChange(AnalysisPeriod.Custom(d("2026-12-01"), d("2026-12-31")))
        assertEquals(TabContent.NotStarted, vm.state.awaitValue { it.period is AnalysisPeriod.Custom }.content)
        vm.onPeriodChange(AnalysisPeriod.Custom(d("2024-01-01"), d("2025-06-30")))
        assertEquals(TabContent.TooLong, vm.state.awaitValue { it.content == TabContent.TooLong }.content)
        // Crosses the 1 Jan 1964 Asia/Jakarta offset change (+07:30 -> +07:00): days are not 24 h.
        vm.onPeriodChange(AnalysisPeriod.Custom(d("1963-12-20"), d("1964-01-10")))
        assertEquals(TabContent.UnsupportedRange, vm.state.awaitValue { it.content == TabContent.UnsupportedRange }.content)
        // A 1950s range is internally consistent (+07:30 every day) and is analysed.
        vm.onPeriodChange(AnalysisPeriod.Custom(d("1955-03-01"), d("1955-03-31")))
        val old = (vm.state.awaitValue { s -> (s.content as? TabContent.Trend)?.report?.reported?.first == d("1955-03-01") }.content as TabContent.Trend).report
        assertEquals(0L, old.totals.expense)
        assertNull(old.totals.savingRatePercent)
    }

    @Test
    fun `this month rolls over at Jakarta midnight`() = runBlocking {
        val vm = viewModel()
        vm.onPeriodChange(AnalysisPeriod.ThisMonth)
        vm.state.awaitValue { s -> (s.content as? TabContent.Trend)?.report?.reported?.first == d("2026-11-01") }
        clock.now = Instant.parse("2026-11-30T17:00:01Z")
        changes.tryEmit(Unit)
        val december = vm.state.awaitValue { s -> (s.content as? TabContent.Trend)?.report?.reported?.first == d("2026-12-01") }
        assertEquals(d("2026-12-01"), (december.content as TabContent.Trend).report.reported.last)
    }
}
