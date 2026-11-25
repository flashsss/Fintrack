package com.fintrack.app.presentation.dashboard

import android.database.SQLException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintrack.app.analytics.metrics.ActiveWalletTotal
import com.fintrack.app.analytics.metrics.BreakdownSlice
import com.fintrack.app.analytics.metrics.ComparisonSpans
import com.fintrack.app.analytics.metrics.DateSpan
import com.fintrack.app.analytics.metrics.ExpenseBreakdown
import com.fintrack.app.analytics.metrics.PeriodComparison
import com.fintrack.app.analytics.metrics.PeriodTotals
import com.fintrack.app.analytics.period.ReportPeriod
import com.fintrack.app.analytics.period.ResolvedPeriod
import com.fintrack.app.analytics.trends.DailyPoint
import com.fintrack.app.analytics.trends.DailySeries
import com.fintrack.app.core.common.TodayProvider
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.data.repository.CategoryRepository
import com.fintrack.app.data.repository.ReportRepository
import com.fintrack.app.data.repository.WalletRepository
import com.fintrack.app.domain.model.Category
import com.fintrack.app.domain.model.Wallet
import com.fintrack.app.domain.model.WalletBalance
import com.fintrack.app.presentation.common.categoryLabel
import com.fintrack.app.presentation.transactions.TransactionRow
import com.fintrack.app.presentation.transactions.TransactionRows
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Current balances; independent of the selected reporting period. */
data class WalletOverview(
    val total: ActiveWalletTotal,
    /** Active wallets with their SQL-derived balances. */
    val wallets: List<Pair<Wallet, Long>>,
)

/** A breakdown slice with its display label resolved. */
data class BreakdownEntry(val slice: BreakdownSlice, val label: String?, val isArchived: Boolean)

/** Everything that depends on the selected period. */
data class PeriodReport(
    /** The selection this report was computed for; keeps the state's period and numbers in sync. */
    val selected: ReportPeriod,
    val period: ResolvedPeriod,
    /** Null when the selected period has not started yet. */
    val totals: PeriodTotals?,
    val daily: List<DailyPoint>,
    val breakdown: List<BreakdownEntry>,
    /** Null for custom ranges or when the period has not started. */
    val comparison: PeriodComparison?,
)

data class DashboardState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val selectedPeriod: ReportPeriod = ReportPeriod.ThisMonth,
    val hasAnyTransactions: Boolean = false,
    val wallets: WalletOverview? = null,
    val report: PeriodReport? = null,
    val recent: List<RecentTransaction> = emptyList(),
)

data class RecentTransaction(val row: TransactionRow, val dateText: String)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val reportRepository: ReportRepository,
    walletRepository: WalletRepository,
    categoryRepository: CategoryRepository,
    todayProvider: TodayProvider,
) : ViewModel() {

    private val period = MutableStateFlow<ReportPeriod>(ReportPeriod.ThisMonth)
    val selectedPeriod: StateFlow<ReportPeriod> = period.asStateFlow()

    private val wallets = walletRepository.observeAllWallets()
    private val balances = walletRepository.observeBalances()
    private val categories = categoryRepository.observeAllCategories()

    private val walletOverview: Flow<WalletOverview> = combine(wallets, balances, ::toWalletOverview)

    private val recent: Flow<List<RecentTransaction>> = combine(
        reportRepository.observeRecentTransactions(RECENT_LIMIT),
        wallets,
        categories,
    ) { transactions, allWallets, allCategories ->
        val walletsById = allWallets.associateBy { it.id }
        val categoriesById = allCategories.associateBy { it.id }
        transactions.map {
            RecentTransaction(TransactionRows.toRow(it, walletsById, categoriesById), JakartaTime.formatDate(it.transactionDate))
        }
    }

    /** Recomputed when the period changes and when the Jakarta date changes (e.g. after midnight). */
    private val report: Flow<PeriodReport> = combine(period, todayProvider.today, ::Pair).flatMapLatest { (selected, today) ->
        observeReport(selected, selected.resolve(today), ComparisonSpans.forPeriod(selected, today))
    }

    /** The selected period in [DashboardState] always comes from the report it belongs to. */
    val state: StateFlow<DashboardState> = combine(
        reportRepository.observeHasTransactions(),
        walletOverview,
        report,
        recent,
    ) { hasTransactions, overview, periodReport, recentRows ->
        DashboardState(
            isLoading = false,
            selectedPeriod = periodReport.selected,
            hasAnyTransactions = hasTransactions,
            wallets = overview,
            report = periodReport,
            recent = recentRows,
        )
    }
        .catch { error ->
            if (error !is SQLException) throw error
            emit(DashboardState(isLoading = false, loadFailed = true, selectedPeriod = period.value))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    fun onPeriodChange(value: ReportPeriod) {
        period.value = value
    }

    private fun observeReport(selected: ReportPeriod, resolved: ResolvedPeriod, spans: ComparisonSpans?): Flow<PeriodReport> {
        val range = resolved.reportedRange
        val reportedLast = resolved.reportedLast
        if (range == null || reportedLast == null) {
            return flowOf(PeriodReport(selected, resolved, totals = null, daily = emptyList(), breakdown = emptyList(), comparison = null))
        }
        val totals = reportRepository.observePeriodTotals(range)
        val daily: Flow<List<DailyPoint>> =
            if (resolved.reportedDays <= MAX_DAILY_CHART_DAYS) {
                reportRepository.observeDailyTotals(resolved.first, reportedLast)
                    .map { DailySeries.fill(resolved.first, reportedLast, it) }
            } else {
                flowOf(emptyList())
            }
        val breakdown = combine(reportRepository.observeExpenseByCategory(range), categories) { expenses, allCategories ->
            val byId = allCategories.associateBy { it.id }
            ExpenseBreakdown.of(expenses).map { slice -> slice.toEntry(byId) }
        }
        val comparison: Flow<PeriodComparison?> = if (spans == null) flowOf(null) else observeComparison(spans)

        return combine(totals, daily, breakdown, comparison) { periodTotals, points, slices, compared ->
            PeriodReport(selected, resolved, periodTotals, points, slices, compared)
        }
    }

    /**
     * For "this month" the current side equals the reported period, but it is queried through the
     * same totals query; both sides always use identical SQL.
     */
    private fun observeComparison(spans: ComparisonSpans): Flow<PeriodComparison> {
        fun totalsOf(span: DateSpan) = reportRepository.observePeriodTotals(JakartaTime.range(span.first, span.last))
        val previousFull: Flow<PeriodTotals?> = spans.previousFullMonth?.let { totalsOf(it) } ?: flowOf(null)
        return combine(totalsOf(spans.current), totalsOf(spans.previous), previousFull) { current, previous, full ->
            PeriodComparison.of(spans, current, previous, full)
        }
    }

    companion object {
        const val RECENT_LIMIT = 5

        /** Beyond about two months, one bar per day stops being readable. */
        const val MAX_DAILY_CHART_DAYS = 62

        fun toWalletOverview(allWallets: List<Wallet>, allBalances: List<WalletBalance>): WalletOverview {
            val balanceById = allBalances.associate { it.walletId to it.balance }
            return WalletOverview(
                total = ActiveWalletTotal.of(allWallets, allBalances),
                wallets = allWallets.filter { it.isActive }.map { it to (balanceById[it.id] ?: 0L) },
            )
        }

        private fun BreakdownSlice.toEntry(categoriesById: Map<String, Category>): BreakdownEntry {
            if (isOthers) return BreakdownEntry(this, label = null, isArchived = false)
            val category = categoryId?.let(categoriesById::get)
            return BreakdownEntry(this, category?.let { categoryLabel(it, categoriesById) }, category?.isArchived == true)
        }
    }
}
