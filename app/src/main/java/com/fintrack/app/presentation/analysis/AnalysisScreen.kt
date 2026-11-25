package com.fintrack.app.presentation.analysis

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintrack.app.R
import com.fintrack.app.analytics.period.AnalysisPeriod
import com.fintrack.app.analytics.period.ResolvedPeriod
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.presentation.common.DateRangePickerDialog
import com.fintrack.app.presentation.common.FinTopBar
import com.fintrack.app.presentation.common.LoadingState
import com.fintrack.app.presentation.common.MessageState
import com.fintrack.app.presentation.common.Spacing

@Composable
fun AnalysisScreen(viewModel: AnalysisViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedPeriod by viewModel.selectedPeriod.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()

    Scaffold(topBar = { FinTopBar(stringResource(R.string.analysis_title)) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            AnalysisPeriodSelector(selectedPeriod, state.resolved, viewModel::onPeriodChange)
            if (AnalysisTab.entries.size > 1) {
                PrimaryScrollableTabRow(selectedTabIndex = selectedTab.ordinal) {
                    AnalysisTab.entries.forEach { tab ->
                        Tab(selected = tab == selectedTab, onClick = { viewModel.onTabChange(tab) }, text = { Text(stringResource(tab.labelRes)) })
                    }
                }
            }
            when (val content = state.content) {
                TabContent.Loading -> LoadingState()
                TabContent.LoadFailed -> MessageState(stringResource(R.string.error_load_failed))
                TabContent.NotStarted -> MessageState(stringResource(R.string.analysis_not_started))
                TabContent.TooLong -> MessageState(
                    pluralStringResource(R.plurals.analysis_too_long, AnalysisPeriod.MAX_DAYS, AnalysisPeriod.MAX_DAYS),
                )
                TabContent.UnsupportedRange -> MessageState(stringResource(R.string.analysis_unsupported_range))
                is TabContent.Trend -> TrendTab(content.report, viewModel::onGranularityChange)
                is TabContent.Categories -> CategoryTab(content.report, viewModel::onCategorySelected)
                is TabContent.Insights -> InsightsTab(content.report)
            }
        }
    }
}

private val AnalysisTab.labelRes: Int
    get() = when (this) {
        AnalysisTab.INSIGHTS -> R.string.analysis_tab_insights
        AnalysisTab.TREND -> R.string.analysis_tab_trend
        AnalysisTab.CATEGORY -> R.string.analysis_tab_category
    }

@Composable
private fun AnalysisPeriodSelector(selected: AnalysisPeriod, resolved: ResolvedPeriod?, onChange: (AnalysisPeriod) -> Unit) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        FilterChip(selected == AnalysisPeriod.ThisMonth, { onChange(AnalysisPeriod.ThisMonth) }, { Text(stringResource(R.string.period_this_month)) })
        FilterChip(selected == AnalysisPeriod.LastMonth, { onChange(AnalysisPeriod.LastMonth) }, { Text(stringResource(R.string.period_last_month)) })
        AnalysisPeriod.ALLOWED_MONTHS.sorted().forEach { months ->
            FilterChip(
                selected = selected == AnalysisPeriod.LastMonths(months),
                onClick = { onChange(AnalysisPeriod.LastMonths(months)) },
                label = { Text(pluralStringResource(R.plurals.analysis_period_months, months, months)) },
            )
        }
        FilterChip(
            selected = selected is AnalysisPeriod.Custom,
            onClick = { showPicker = true },
            leadingIcon = { Icon(painterResource(R.drawable.ic_calendar_month), contentDescription = null) },
            label = { Text(stringResource(R.string.period_custom)) },
        )
    }
    if (resolved != null) {
        val last = resolved.reportedLast
        val label = if (last == null) {
            JakartaTime.formatSpan(resolved.first, resolved.last)
        } else {
            val span = JakartaTime.formatSpan(resolved.first, last)
            if (resolved.isPartial) stringResource(R.string.dashboard_period_in_progress, span) else span
        }
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.small))
    }
    if (showPicker) {
        DateRangePickerDialog(
            onDismiss = { showPicker = false },
            onConfirm = { first, last ->
                onChange(AnalysisPeriod.Custom(first, last))
                showPicker = false
            },
        )
    }
}
