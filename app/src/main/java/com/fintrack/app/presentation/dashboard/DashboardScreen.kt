package com.fintrack.app.presentation.dashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintrack.app.R
import com.fintrack.app.analytics.period.ReportPeriod
import com.fintrack.app.analytics.period.ResolvedPeriod
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.presentation.common.DateRangePickerDialog
import com.fintrack.app.presentation.common.FinTopBar
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

    Scaffold(topBar = { FinTopBar(stringResource(R.string.dashboard_title)) }) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        val report = state.report
        val wallets = state.wallets
        when {
            state.isLoading -> LoadingState(modifier)
            state.loadFailed -> MessageState(stringResource(R.string.error_load_failed), modifier)
            !state.hasAnyTransactions && wallets?.wallets.isNullOrEmpty() ->
                MessageState(stringResource(R.string.dashboard_empty), modifier)
            report == null || wallets == null -> LoadingState(modifier)
            else -> LazyColumn(modifier, contentPadding = PaddingValues(bottom = Spacing.field * 2)) {
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
            SectionDivider()
            Text(
                stringResource(R.string.dashboard_period_not_started),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Spacing.screen),
            )
        }
        return
    }
    item(key = "summary") { SummarySection(totals) }
    item(key = "daily") { DailyChartSection(report) }
    item(key = "breakdown") { BreakdownSection(report.breakdown) }
    item(key = "comparison") { ComparisonSection(report.comparison) }
}

@Composable
private fun PeriodSelector(selected: ReportPeriod, resolved: ResolvedPeriod, onPeriodChange: (ReportPeriod) -> Unit) {
    var showRangePicker by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            FilterChip(
                selected = selected == ReportPeriod.ThisMonth,
                onClick = { onPeriodChange(ReportPeriod.ThisMonth) },
                label = { Text(stringResource(R.string.period_this_month)) },
            )
            FilterChip(
                selected = selected == ReportPeriod.LastMonth,
                onClick = { onPeriodChange(ReportPeriod.LastMonth) },
                label = { Text(stringResource(R.string.period_last_month)) },
            )
            FilterChip(
                selected = selected is ReportPeriod.Custom,
                onClick = { showRangePicker = true },
                leadingIcon = { Icon(painterResource(R.drawable.ic_calendar_month), contentDescription = null) },
                label = { Text(stringResource(R.string.period_custom)) },
            )
        }
        Text(
            text = periodLabel(resolved),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.small),
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

@Composable
internal fun SectionDivider() {
    HorizontalDivider(Modifier.padding(top = Spacing.small))
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.field, bottom = 4.dp),
    )
}
