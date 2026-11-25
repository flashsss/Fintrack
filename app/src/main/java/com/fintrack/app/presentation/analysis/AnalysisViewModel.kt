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
import com.fintrack.app.data.repository.ReportRepository
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
enum class AnalysisTab { TREND }

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
}

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
    todayProvider: TodayProvider,
) : ViewModel() {

    private val period = MutableStateFlow<AnalysisPeriod>(DEFAULT_PERIOD)
    private val tab = MutableStateFlow(AnalysisTab.TREND)
    private val granularity = MutableStateFlow(Granularity.MONTHLY)

    val selectedPeriod: StateFlow<AnalysisPeriod> = period.asStateFlow()
    val selectedTab: StateFlow<AnalysisTab> = tab.asStateFlow()

    private data class Selection(val period: AnalysisPeriod, val today: java.time.LocalDate, val tab: AnalysisTab, val granularity: Granularity)

    /** Only the selected tab's queries run; the state always carries the selection it was built from. */
    val state: StateFlow<AnalysisState> = combine(period, todayProvider.today, tab, granularity, ::Selection)
        .flatMapLatest { selection -> observe(selection) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisState(DEFAULT_PERIOD, null, AnalysisTab.TREND, TabContent.Loading))

    fun onPeriodChange(value: AnalysisPeriod) {
        period.value = value
    }

    fun onTabChange(value: AnalysisTab) {
        tab.value = value
    }

    fun onGranularityChange(value: Granularity) {
        granularity.value = value
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

    companion object {
        /** Design decision D5: three months give enough data for trends without a long query. */
        val DEFAULT_PERIOD: AnalysisPeriod = AnalysisPeriod.LastMonths(3)
    }
}
