package com.fintrack.app.presentation.analysis

import android.database.SQLException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintrack.app.analytics.metrics.DateSpan
import com.fintrack.app.analytics.period.AnalysisPeriod
import com.fintrack.app.analytics.period.ResolvedPeriod
import com.fintrack.app.analytics.trends.DailySeries
import com.fintrack.app.analytics.trends.Granularity
import com.fintrack.app.analytics.trends.TrendBuckets
import com.fintrack.app.analytics.trends.TrendReport
import com.fintrack.app.core.common.TodayProvider
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.data.repository.AnalyticsRepository
import com.fintrack.app.data.repository.CategoryRepository
import com.fintrack.app.data.repository.ReportRepository
import com.fintrack.app.domain.model.Category
import com.fintrack.app.analytics.metrics.CategoryAnalysis
import com.fintrack.app.analytics.metrics.CategoryChange
import com.fintrack.app.analytics.metrics.CategoryTotal
import com.fintrack.app.analytics.metrics.OutsideHierarchy
import com.fintrack.app.analytics.metrics.ParentNode
import com.fintrack.app.analytics.metrics.WeekdayPattern
import java.time.YearMonth
import com.fintrack.app.analytics.insights.Insight
import com.fintrack.app.analytics.insights.InsightRules
import com.fintrack.app.analytics.insights.RuleOutcome
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

/** Tabs in display order. Entries are added only when their sub-phase is implemented. */
enum class AnalysisTab { INSIGHTS, TREND, CATEGORY, PATTERN }

sealed interface TabContent {
    data object Loading : TabContent
    data object LoadFailed : TabContent
    /** The selected period starts in the future. */
    data object NotStarted : TabContent
    /** The reported span exceeds [AnalysisPeriod.MAX_DAYS]. */
    data object TooLong : TabContent
    /** The span crosses a historical Asia/Jakarta offset change (before 1964); days are not 24 h. */
    data object UnsupportedRange : TabContent
    data class Trend(val report: TrendReport) : TabContent
    data class Categories(val report: CategoryReport) : TabContent
    data class Insights(val report: InsightReport) : TabContent
    data class Pattern(val report: PatternReport) : TabContent
}

data class PatternReport(val reported: DateSpan, val weekdays: WeekdayPattern)

/** Outcomes of every insight rule for the reported span vs its fair previous span. */
data class InsightReport(
    val reported: DateSpan,
    val previous: DateSpan,
    val categoryIncreases: RuleOutcome<List<Insight.CategoryIncrease>>,
    val savingRateDrop: RuleOutcome<Insight.SavingRateDrop>,
    val weekendSpending: RuleOutcome<Insight.WeekendSpending>,
    val categoriesById: Map<String, Category>,
)

/**
 * Category analysis of the reported span. Labels, parents and archive state come from
 * [categoriesById], the live categories table, so renames and moves apply without re-querying sums.
 */
data class CategoryReport(
    val reported: DateSpan,
    val previous: DateSpan,
    val totalExpense: Long,
    val flat: List<CategoryTotal>,
    val parents: List<ParentNode>,
    val outside: OutsideHierarchy,
    val previousById: Map<String, Long>,
    /** Previous totals rolled up to top-level parents with the current hierarchy. */
    val previousByParent: Map<String, Long>,
    val movers: List<CategoryChange>,
    val categoriesById: Map<String, Category>,
    /** Monthly expense of [selectedCategoryId] over the reported months; empty when nothing is selected. */
    val selectedCategoryId: String?,
    val selectedMonthly: List<Pair<YearMonth, Long>>,
    val lastMonthIsPartial: Boolean,
)

data class AnalysisState(
    val period: AnalysisPeriod,
    /** Null only before the first emission. */
    val resolved: ResolvedPeriod?,
    val tab: AnalysisTab,
    val content: TabContent,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnalysisViewModel @Inject constructor(
    private val reportRepository: ReportRepository,
    private val analyticsRepository: AnalyticsRepository,
    private val categoryRepository: CategoryRepository,
    todayProvider: TodayProvider,
) : ViewModel() {

    private val period = MutableStateFlow<AnalysisPeriod>(DEFAULT_PERIOD)
    private val tab = MutableStateFlow(AnalysisTab.INSIGHTS)
    private val granularity = MutableStateFlow(Granularity.MONTHLY)
    private val selectedCategory = MutableStateFlow<String?>(null)

    val selectedPeriod: StateFlow<AnalysisPeriod> = period.asStateFlow()
    val selectedTab: StateFlow<AnalysisTab> = tab.asStateFlow()

    private data class Selection(val period: AnalysisPeriod, val today: java.time.LocalDate, val tab: AnalysisTab, val granularity: Granularity)

    /** Only the selected tab's queries run; the state always carries the selection it was built from. */
    val state: StateFlow<AnalysisState> = combine(period, todayProvider.today, tab, granularity, ::Selection)
        .flatMapLatest { selection -> observe(selection) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisState(DEFAULT_PERIOD, null, AnalysisTab.INSIGHTS, TabContent.Loading))

    fun onPeriodChange(value: AnalysisPeriod) {
        period.value = value
    }

    fun onTabChange(value: AnalysisTab) {
        tab.value = value
    }

    fun onGranularityChange(value: Granularity) {
        granularity.value = value
    }

    /** Shows the monthly trend of [categoryId]; selecting it again clears the selection. */
    fun onCategorySelected(categoryId: String) {
        selectedCategory.value = if (selectedCategory.value == categoryId) null else categoryId
    }

    private fun observe(selection: Selection): Flow<AnalysisState> {
        val resolved = selection.period.resolve(selection.today)
        fun state(content: TabContent) = AnalysisState(selection.period, resolved, selection.tab, content)
        val reportedLast = resolved.reportedLast ?: return flowOf(state(TabContent.NotStarted))
        if (resolved.reportedDays > AnalysisPeriod.MAX_DAYS) return flowOf(state(TabContent.TooLong))
        val reported = DateSpan(resolved.first, reportedLast)
        val previous = selection.period.previousSpan(reported)
        if (!JakartaTime.hasUniformDays(reported.first, reported.last)) return flowOf(state(TabContent.UnsupportedRange))

        val content: Flow<TabContent> = when (selection.tab) {
            AnalysisTab.TREND -> observeTrend(reported, resolved.reportedDays, previous, selection.granularity)
            AnalysisTab.CATEGORY -> observeCategories(reported, previous, resolved.isPartial)
            AnalysisTab.INSIGHTS -> observeInsights(reported, previous)
            AnalysisTab.PATTERN -> analyticsRepository.observeExpenseByWeekday(reported.first, reported.last)
                .map { TabContent.Pattern(PatternReport(reported, WeekdayPattern.of(it, reported.first, reported.last))) }
        }
        return content
            .map(::state)
            .catch { error ->
                if (error !is SQLException) throw error
                emit(state(TabContent.LoadFailed))
            }
    }

    private fun observeTrend(reported: DateSpan, reportedDays: Int, previous: DateSpan, requested: Granularity): Flow<TabContent> {
        val granularity = requested.takeIf { it in TrendReport.availableGranularities(reportedDays) } ?: Granularity.WEEKLY
        return combine(
            reportRepository.observeDailyTotals(reported.first, reported.last),
            reportRepository.observePeriodTotals(JakartaTime.range(reported.first, reported.last)),
            reportRepository.observePeriodTotals(JakartaTime.range(previous.first, previous.last)),
        ) { daily, totals, previousTotals ->
            val points = DailySeries.fill(reported.first, reported.last, daily)
            TabContent.Trend(
                TrendReport(
                    reported = reported,
                    reportedDays = reportedDays,
                    granularity = granularity,
                    buckets = TrendBuckets.rollUp(points, reported, granularity),
                    totals = totals,
                    previous = previous,
                    previousTotals = previousTotals,
                ),
            )
        }
    }

    private fun observeInsights(reported: DateSpan, previous: DateSpan): Flow<TabContent> {
        val currentRange = JakartaTime.range(reported.first, reported.last)
        val previousRange = JakartaTime.range(previous.first, previous.last)
        val categoryOutcome = combine(
            analyticsRepository.observeExpenseByCategoryAndDay(reported.first, reported.last),
            reportRepository.observeExpenseByCategory(previousRange),
        ) { days, previousExpenses ->
            val previousById = previousExpenses.mapNotNull { e -> e.categoryId?.let { it to e.total } }.toMap()
            InsightRules.categoryIncreases(CategoryAnalysis.flat(days), previousById)
        }
        val savingOutcome = combine(
            reportRepository.observePeriodTotals(currentRange),
            reportRepository.observePeriodTotals(previousRange),
            InsightRules::savingRateDrop,
        )
        val weekendOutcome = analyticsRepository.observeExpenseByWeekday(reported.first, reported.last)
            .map { InsightRules.weekendSpending(WeekdayPattern.of(it, reported.first, reported.last)) }
        return combine(categoryOutcome, savingOutcome, weekendOutcome, categoryRepository.observeAllCategories()) { increases, saving, weekend, categories ->
            TabContent.Insights(
                InsightReport(
                    reported = reported,
                    previous = previous,
                    categoryIncreases = increases,
                    savingRateDrop = saving,
                    weekendSpending = weekend,
                    categoriesById = categories.associateBy { it.id },
                ),
            )
        }
    }

    private fun observeCategories(reported: DateSpan, previous: DateSpan, isPartial: Boolean): Flow<TabContent> =
        combine(
            analyticsRepository.observeExpenseByCategoryAndDay(reported.first, reported.last),
            reportRepository.observeExpenseByCategory(JakartaTime.range(previous.first, previous.last)),
            categoryRepository.observeAllCategories(),
            selectedCategory,
        ) { days, previousExpenses, categories, selected ->
            val byId = categories.associateBy { it.id }
            val flat = CategoryAnalysis.flat(days)
            val previousById = previousExpenses.mapNotNull { e -> e.categoryId?.let { it to e.total } }.toMap()
            val months = generateSequence(YearMonth.from(reported.first)) { it.plusMonths(1) }
                .takeWhile { !it.isAfter(YearMonth.from(reported.last)) }.toList()
            TabContent.Categories(
                CategoryReport(
                    reported = reported,
                    previous = previous,
                    totalExpense = days.fold(0L) { sum, d -> Math.addExact(sum, d.total) },
                    flat = flat,
                    parents = CategoryAnalysis.byParent(flat, byId),
                    outside = CategoryAnalysis.outsideHierarchy(days, byId),
                    previousById = previousById,
                    previousByParent = CategoryAnalysis.rollUp(previousById, byId),
                    movers = CategoryAnalysis.movers(flat, previousById),
                    categoriesById = byId,
                    selectedCategoryId = selected,
                    selectedMonthly = if (selected == null) emptyList() else CategoryAnalysis.monthly(days, selected, months),
                    lastMonthIsPartial = isPartial || reported.last != YearMonth.from(reported.last).atEndOfMonth(),
                ),
            )
        }

    companion object {
        /** Design decision D5: three months give enough data for trends without a long query. */
        val DEFAULT_PERIOD: AnalysisPeriod = AnalysisPeriod.LastMonths(3)
    }
}
