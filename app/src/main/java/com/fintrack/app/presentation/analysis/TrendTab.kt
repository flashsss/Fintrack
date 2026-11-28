package com.fintrack.app.presentation.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fintrack.app.R
import com.fintrack.app.presentation.common.AppCard
import com.fintrack.app.analytics.trends.Granularity
import com.fintrack.app.analytics.trends.TrendBucket
import com.fintrack.app.analytics.trends.TrendReport
import com.fintrack.app.core.ui.theme.LocalChartColors
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.core.util.formatPercent
import com.fintrack.app.core.util.formatRupiah
import com.fintrack.app.core.util.formatRupiahSigned
import com.fintrack.app.presentation.common.ComparisonRow
import com.fintrack.app.presentation.common.IncomeExpenseColumnChart
import com.fintrack.app.presentation.common.LegendItem
import com.fintrack.app.presentation.common.MoneyText
import com.fintrack.app.presentation.common.NoteText
import com.fintrack.app.presentation.common.Spacing
import kotlin.math.roundToLong

@Composable
internal fun TrendTab(report: TrendReport, onGranularityChange: (Granularity) -> Unit) {
    val colors = LocalChartColors.current
    val totals = report.totals
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppCard {
            GranularitySelector(report, onGranularityChange)

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.field)) {
                LegendItem(colors.income, stringResource(R.string.dashboard_income), formatRupiah(totals.income))
                LegendItem(colors.expense, stringResource(R.string.dashboard_expense), formatRupiah(totals.expense))
            }
            if (totals.income == 0L && totals.expense == 0L) {
                NoteText(stringResource(R.string.analysis_trend_empty))
            } else {
                val buckets = report.buckets
                val description = stringResource(
                    R.string.analysis_trend_chart_description,
                    stringResource(report.granularity.labelRes).lowercase(),
                    JakartaTime.formatSpan(report.reported.first, report.reported.last),
                )
                IncomeExpenseColumnChart(
                    labels = buckets.map { it.label(report.granularity) },
                    income = buckets.map { it.income },
                    expense = buckets.map { it.expense },
                    labelSpacing = if (buckets.size > 16) 5 else 1,
                    modifier = Modifier.semantics { contentDescription = description },
                )
                if (buckets.any { it.isPartial }) NoteText(stringResource(R.string.analysis_partial_note))
            }
        }
        AppCard {
            Metric(stringResource(R.string.dashboard_net_cash_flow), formatRupiahSigned(totals.netCashFlow))
            Metric(
                stringResource(R.string.dashboard_saving_rate),
                totals.savingRatePercent?.let { formatPercent(it) } ?: stringResource(R.string.dashboard_saving_rate_unavailable),
            )
            Metric(
                stringResource(R.string.analysis_average_daily_expense),
                report.averageDailyExpense?.let { formatRupiah(it.roundToLong()) } ?: "—",
            )
            NoteText(stringResource(R.string.analysis_average_daily_note))
            if (report.granularity != Granularity.DAILY) {
                Metric(
                    stringResource(if (report.granularity == Granularity.WEEKLY) R.string.analysis_average_full_week else R.string.analysis_average_full_month),
                    report.averageFullBucketExpense?.let { formatRupiah(it.roundToLong()) }
                        ?: stringResource(R.string.analysis_average_full_unavailable),
                )
            }
        }
        AppCard {
            Text(stringResource(R.string.analysis_comparison_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    R.string.dashboard_comparison_spans,
                    JakartaTime.formatSpan(report.reported.first, report.reported.last),
                    JakartaTime.formatSpan(report.previous.first, report.previous.last),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            ComparisonRow(stringResource(R.string.dashboard_income), report.income)
            ComparisonRow(stringResource(R.string.dashboard_expense), report.expense)
            ComparisonRow(stringResource(R.string.dashboard_net_cash_flow), report.netCashFlow)
            Metric(
                stringResource(R.string.analysis_saving_rate_change),
                report.savingRatePointChange?.let { stringResource(R.string.analysis_points, formatPercent(it, signed = true).removeSuffix("%")) }
                    ?: stringResource(R.string.analysis_saving_rate_change_unavailable),
            )
        }
    }
}

@Composable
private fun GranularitySelector(report: TrendReport, onChange: (Granularity) -> Unit) {
    val options = Granularity.entries
    val available = TrendReport.availableGranularities(report.reportedDays)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = report.granularity == option,
                onClick = { onChange(option) },
                enabled = option in available,
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(stringResource(option.labelRes)) }
        }
    }
    if (Granularity.DAILY !in available) {
        NoteText(pluralStringResource(R.plurals.analysis_daily_unavailable, TrendReport.MAX_DAILY_DAYS, TrendReport.MAX_DAILY_DAYS))
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyText(value, style = MaterialTheme.typography.titleMedium)
    }
}

private val Granularity.labelRes: Int
    get() = when (this) {
        Granularity.DAILY -> R.string.analysis_granularity_daily
        Granularity.WEEKLY -> R.string.analysis_granularity_weekly
        Granularity.MONTHLY -> R.string.analysis_granularity_monthly
    }

/** Axis label; partial buckets get "*" (explained by analysis_partial_note). */
private fun TrendBucket.label(granularity: Granularity): String {
    val text = when (granularity) {
        Granularity.DAILY -> first.dayOfMonth.toString()
        Granularity.WEEKLY -> JakartaTime.formatDayMonth(first)
        Granularity.MONTHLY -> JakartaTime.formatMonthShort(first)
    }
    return if (isPartial) "$text*" else text
}
