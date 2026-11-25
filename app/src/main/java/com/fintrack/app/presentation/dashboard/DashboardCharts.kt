package com.fintrack.app.presentation.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.fintrack.app.core.util.formatRupiahCompact
import com.fintrack.app.presentation.common.MoneyText
import com.fintrack.app.presentation.common.Spacing
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.ColumnCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.compose.pie.PieChart
import com.patrykandpatrick.vico.compose.pie.PieChartHost
import com.patrykandpatrick.vico.compose.pie.PieSize
import com.patrykandpatrick.vico.compose.pie.data.PieChartModel
import com.patrykandpatrick.vico.compose.pie.rememberPieChart

@Composable
internal fun DailyChartSection(report: PeriodReport) {
    val totals = report.totals ?: return
    val colors = LocalChartColors.current
    SectionDivider()
    SectionTitle(stringResource(R.string.dashboard_daily_title))
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.small)) {
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
                DailyColumnChart(report, colors.income, colors.expense, Modifier.semantics { contentDescription = description })
                NoteText(stringResource(R.string.dashboard_daily_axis_note))
            }
        }
    }
}

/** Grouped columns per day: income then expense. X is the day index; labels show the day of month. */
@Composable
private fun DailyColumnChart(report: PeriodReport, incomeColor: Color, expenseColor: Color, modifier: Modifier) {
    val points = report.daily
    val model = remember(points) {
        CartesianChartModel(
            ColumnCartesianLayerModel.build {
                series(points.map { it.income })
                series(points.map { it.expense })
            },
        )
    }
    val dayFormatter = remember(points) {
        CartesianValueFormatter { _, x, _ -> points.getOrNull(x.toInt())?.date?.dayOfMonth?.toString().orEmpty() }
    }
    val amountFormatter = remember { CartesianValueFormatter { _, y, _ -> formatRupiahCompact(y.toLong()) } }
    val labelSpacing = if (points.size > 16) 5 else 1
    val columnShape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)

    ProvideVicoTheme(rememberM3VicoTheme()) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberColumnCartesianLayer(
                    columnProvider = ColumnCartesianLayer.ColumnProvider.series(
                        rememberLineComponent(Fill(incomeColor), thickness = 4.dp, shape = columnShape),
                        rememberLineComponent(Fill(expenseColor), thickness = 4.dp, shape = columnShape),
                    ),
                    columnCollectionSpacing = 3.dp,
                    mergeMode = { ColumnCartesianLayer.MergeMode.Grouped(columnSpacing = 1.dp) },
                ),
                startAxis = VerticalAxis.rememberStart(valueFormatter = amountFormatter),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = dayFormatter,
                    itemPlacer = remember(labelSpacing) { HorizontalAxis.ItemPlacer.aligned(spacing = { labelSpacing }) },
                ),
            ),
            model = model,
            // The whole period fits the width: no scrolling or zooming hides days.
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
            modifier = modifier.fillMaxWidth().height(220.dp).padding(top = Spacing.small),
        )
    }
}

@Composable
internal fun BreakdownSection(entries: List<BreakdownEntry>) {
    SectionDivider()
    SectionTitle(stringResource(R.string.dashboard_breakdown_title))
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.small)) {
        if (entries.isEmpty()) {
            NoteText(stringResource(R.string.dashboard_breakdown_empty))
            return@Column
        }
        val colors = LocalChartColors.current
        val sliceColors = entries.mapIndexed { index, entry ->
            if (entry.slice.isOthers) colors.others else colors.categories[index % colors.categories.size]
        }
        val total = entries.sumOf { it.slice.total }
        val description = stringResource(R.string.dashboard_breakdown_chart_description, formatRupiah(total))
        DonutChart(entries.map { it.slice }, sliceColors, Modifier.semantics { contentDescription = description })
        Column(Modifier.padding(top = Spacing.small), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
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
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (entry.isArchived) {
                Text(stringResource(R.string.state_archived), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            MoneyText(formatRupiah(slice.total), style = MaterialTheme.typography.bodyLarge)
            Text(formatPercent(slice.percent), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ColorDot(color)
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MoneyText(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ColorDot(color: Color) {
    Canvas(Modifier.size(10.dp)) { drawCircle(color) }
}
