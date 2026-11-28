package com.fintrack.app.presentation.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fintrack.app.R
import com.fintrack.app.analytics.metrics.BreakdownSlice
import com.fintrack.app.core.ui.theme.LocalChartColors
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.core.util.formatPercent
import com.fintrack.app.core.util.formatRupiah
import com.fintrack.app.presentation.common.AppCard
import com.fintrack.app.presentation.common.CardHeader
import com.fintrack.app.presentation.common.ColorDot
import com.fintrack.app.presentation.common.ProgressBar
import com.fintrack.app.presentation.common.IncomeExpenseColumnChart
import com.fintrack.app.presentation.common.LegendItem
import com.fintrack.app.presentation.common.MoneyText
import com.fintrack.app.presentation.common.NoteText
import com.fintrack.app.presentation.common.Spacing
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.pie.PieChart
import com.patrykandpatrick.vico.compose.pie.PieChartHost
import com.patrykandpatrick.vico.compose.pie.PieSize
import com.patrykandpatrick.vico.compose.pie.data.PieChartModel
import com.patrykandpatrick.vico.compose.pie.rememberPieChart

@Composable
internal fun DailyChartSection(report: PeriodReport) {
    val totals = report.totals ?: return
    val colors = LocalChartColors.current
    AppCard {
        CardHeader(stringResource(R.string.dashboard_daily_title))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.field)) {
            LegendItem(colors.income, stringResource(R.string.dashboard_income), formatRupiah(totals.income))
            LegendItem(colors.expense, stringResource(R.string.dashboard_expense), formatRupiah(totals.expense))
        }
        when {
            report.daily.isEmpty() && report.period.reportedDays > DashboardViewModel.MAX_DAILY_CHART_DAYS ->
                NoteText(
                    pluralStringResource(
                        R.plurals.dashboard_daily_too_long,
                        DashboardViewModel.MAX_DAILY_CHART_DAYS,
                        DashboardViewModel.MAX_DAILY_CHART_DAYS,
                    ),
                    Modifier.padding(top = Spacing.small),
                )
            totals.income == 0L && totals.expense == 0L ->
                NoteText(stringResource(R.string.dashboard_daily_empty), Modifier.padding(top = Spacing.small))
            else -> {
                val description = stringResource(
                    R.string.dashboard_daily_chart_description,
                    JakartaTime.formatSpan(report.daily.first().date, report.daily.last().date),
                    formatRupiah(totals.income),
                    formatRupiah(totals.expense),
                )
                val points = report.daily
                IncomeExpenseColumnChart(
                    labels = points.map { it.date.dayOfMonth.toString() },
                    income = points.map { it.income },
                    expense = points.map { it.expense },
                    labelSpacing = if (points.size > 16) 5 else 1,
                    modifier = Modifier.semantics { contentDescription = description },
                )
                NoteText(stringResource(R.string.dashboard_daily_axis_note))
            }
        }
    }
}

@Composable
internal fun BreakdownSection(entries: List<BreakdownEntry>) {
    AppCard {
        CardHeader(stringResource(R.string.dashboard_breakdown_title))
        if (entries.isEmpty()) {
            NoteText(stringResource(R.string.dashboard_breakdown_empty))
            return@AppCard
        }
        val colors = LocalChartColors.current
        val sliceColors = entries.mapIndexed { index, entry ->
            if (entry.slice.isOthers) colors.others else colors.categories[index % colors.categories.size]
        }
        val total = entries.sumOf { it.slice.total }
        val description = stringResource(R.string.dashboard_breakdown_chart_description, formatRupiah(total))
        DonutChart(entries.map { it.slice }, sliceColors, Modifier.semantics { contentDescription = description })
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            entries.forEachIndexed { index, entry -> BreakdownRow(entry, sliceColors[index]) }
        }
    }
}

@Composable
private fun DonutChart(slices: List<BreakdownSlice>, sliceColors: List<Color>, modifier: Modifier) {
    val model = remember(slices) { PieChartModel.build(*slices.map { it.total }.toTypedArray()) }
    PieChartHost(
        chart = rememberPieChart(
            sliceProvider = PieChart.SliceProvider.series(sliceColors.map { PieChart.Slice(fill = Fill(it)) }),
            spacing = 2.dp,
            outerSize = PieSize.Outer.fixed(176.dp),
            innerSize = PieSize.Inner.fixed(104.dp),
        ),
        model = model,
        chartAreaHeight = 176.dp,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun BreakdownRow(entry: BreakdownEntry, color: Color) {
    val slice = entry.slice
    val label = when {
        slice.isOthers -> pluralStringResource(R.plurals.dashboard_breakdown_others, slice.mergedCount, slice.mergedCount)
        slice.categoryId == null -> stringResource(R.string.dashboard_breakdown_uncategorized)
        else -> entry.label ?: stringResource(R.string.transaction_unknown_reference)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        ColorDot(color)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    if (entry.isArchived) {
                        Text(stringResource(R.string.state_archived), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    MoneyText(formatRupiah(slice.total), style = MaterialTheme.typography.titleSmall)
                    Text(formatPercent(slice.percent), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ProgressBar((slice.percent / 100).toFloat(), color, height = 6.dp)
        }
    }
}


