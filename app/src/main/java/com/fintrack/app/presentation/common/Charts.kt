package com.fintrack.app.presentation.common

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
import androidx.compose.ui.unit.dp
import com.fintrack.app.core.ui.theme.LocalChartColors
import com.fintrack.app.core.util.formatRupiahCompact
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

/**
 * Grouped columns (income, then expense) for consecutive buckets. [labels] has one entry per bucket;
 * only every [labelSpacing]-th label is drawn. The whole series fits the width (no scroll or zoom),
 * so no bucket is hidden. Used by the dashboard (days) and the trend tab (days, weeks, months).
 */
@Composable
fun IncomeExpenseColumnChart(
    labels: List<String>,
    income: List<Long>,
    expense: List<Long>,
    modifier: Modifier = Modifier,
    labelSpacing: Int = 1,
) {
    require(labels.size == income.size && income.size == expense.size) { "Series sizes differ" }
    val colors = LocalChartColors.current
    val model = remember(income, expense) {
        CartesianChartModel(
            ColumnCartesianLayerModel.build {
                series(income)
                series(expense)
            },
        )
    }
    val labelFormatter = remember(labels) {
        CartesianValueFormatter { _, x, _ -> labels.getOrNull(x.toInt()).orEmpty() }
    }
    val amountFormatter = remember { CartesianValueFormatter { _, y, _ -> formatRupiahCompact(y.toLong()) } }
    val columnShape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)
    // Wider columns when there are few buckets (weeks, months) so they stay readable.
    val thickness = if (labels.size > 16) 4.dp else 10.dp

    ProvideVicoTheme(rememberM3VicoTheme()) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberColumnCartesianLayer(
                    columnProvider = ColumnCartesianLayer.ColumnProvider.series(
                        rememberLineComponent(Fill(colors.income), thickness = thickness, shape = columnShape),
                        rememberLineComponent(Fill(colors.expense), thickness = thickness, shape = columnShape),
                    ),
                    columnCollectionSpacing = 3.dp,
                    mergeMode = { ColumnCartesianLayer.MergeMode.Grouped(columnSpacing = 1.dp) },
                ),
                startAxis = VerticalAxis.rememberStart(valueFormatter = amountFormatter),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = labelFormatter,
                    itemPlacer = remember(labelSpacing) { HorizontalAxis.ItemPlacer.aligned(spacing = { labelSpacing }) },
                ),
            ),
            model = model,
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
            modifier = modifier.fillMaxWidth().height(220.dp).padding(top = Spacing.small),
        )
    }
}

@Composable
fun LegendItem(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ColorDot(color)
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MoneyText(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ColorDot(color: Color) {
    Canvas(Modifier.size(10.dp)) { drawCircle(color) }
}
