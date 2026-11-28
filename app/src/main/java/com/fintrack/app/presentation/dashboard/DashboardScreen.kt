package com.fintrack.app.presentation.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintrack.app.R
import com.fintrack.app.analytics.period.ReportPeriod
import com.fintrack.app.analytics.period.ResolvedPeriod
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.presentation.common.AppCard
import com.fintrack.app.presentation.common.DateRangePickerDialog
import com.fintrack.app.presentation.common.NoteText
import com.fintrack.app.presentation.common.PeriodPills
import com.fintrack.app.presentation.common.PillOption
import com.fintrack.app.presentation.common.ScreenHeader
import com.fintrack.app.presentation.common.LoadingState
import com.fintrack.app.presentation.common.MessageState
import com.fintrack.app.presentation.common.Spacing

@Composable
fun DashboardScreen(
    onOpenTransaction: (String) -> Unit,
    onOpenWallet: (String) -> Unit,
    onSeeAllTransactions: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedPeriod by viewModel.selectedPeriod.collectAsStateWithLifecycle()

    Scaffold(topBar = { ScreenHeader(stringResource(R.string.dashboard_title)) }) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        val report = state.report
        val wallets = state.wallets
        when {
            state.isLoading -> LoadingState(modifier)
            state.loadFailed -> MessageState(stringResource(R.string.error_load_failed), modifier)
            !state.hasAnyTransactions && wallets?.wallets.isNullOrEmpty() ->
                MessageState(stringResource(R.string.dashboard_empty), modifier)
            report == null || wallets == null -> LoadingState(modifier)
            else -> LazyColumn(
                modifier,
                contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.field * 2),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "period") {
                    PeriodSelector(selectedPeriod, report.period, viewModel::onPeriodChange)
                }
                item(key = "balance") { BalanceSection(wallets) }
                periodSections(report)
                item(key = "wallets") { WalletsSection(wallets, onOpenWallet) }
                item(key = "recent") { RecentSection(state.recent, onOpenTransaction, onSeeAllTransactions) }
            }
        }
    }
}

/** Sections that depend on the selected period, in the approved order. */
private fun LazyListScope.periodSections(report: PeriodReport) {
    val totals = report.totals
    if (totals == null) {
        item(key = "not-started") {
            AppCard { NoteText(stringResource(R.string.dashboard_period_not_started)) }
        }
        return
    }
    item(key = "summary") { SummarySection(totals) }
    item(key = "daily") { DailyChartSection(report) }
    item(key = "breakdown") { BreakdownSection(report.breakdown) }
    item(key = "comparison") { ComparisonSection(report.comparison) }
}

private enum class PeriodChoice { THIS_MONTH, LAST_MONTH, CUSTOM }

@Composable
private fun PeriodSelector(selected: ReportPeriod, resolved: ResolvedPeriod, onPeriodChange: (ReportPeriod) -> Unit) {
    var showRangePicker by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        PeriodPills(
            options = listOf(
                PillOption(PeriodChoice.THIS_MONTH, stringResource(R.string.period_this_month)),
                PillOption(PeriodChoice.LAST_MONTH, stringResource(R.string.period_last_month)),
                PillOption(PeriodChoice.CUSTOM, stringResource(R.string.period_custom)),
            ),
            selected = when (selected) {
                ReportPeriod.ThisMonth -> PeriodChoice.THIS_MONTH
                ReportPeriod.LastMonth -> PeriodChoice.LAST_MONTH
                is ReportPeriod.Custom -> PeriodChoice.CUSTOM
            },
            onSelect = { choice ->
                when (choice) {
                    PeriodChoice.THIS_MONTH -> onPeriodChange(ReportPeriod.ThisMonth)
                    PeriodChoice.LAST_MONTH -> onPeriodChange(ReportPeriod.LastMonth)
                    PeriodChoice.CUSTOM -> showRangePicker = true
                }
            },
        )
        Text(
            text = periodLabel(resolved),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    if (showRangePicker) {
        DateRangePickerDialog(
            onDismiss = { showRangePicker = false },
            onConfirm = { first, last ->
                onPeriodChange(ReportPeriod.Custom(first, last))
                showRangePicker = false
            },
        )
    }
}

/** The reported span, marked when the period is still in progress. */
@Composable
private fun periodLabel(resolved: ResolvedPeriod): String {
    val reportedLast = resolved.reportedLast ?: return JakartaTime.formatSpan(resolved.first, resolved.last)
    val span = JakartaTime.formatSpan(resolved.first, reportedLast)
    return if (resolved.isPartial) stringResource(R.string.dashboard_period_in_progress, span) else span
}
