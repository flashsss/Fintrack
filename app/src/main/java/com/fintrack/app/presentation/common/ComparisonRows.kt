package com.fintrack.app.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.fintrack.app.R
import com.fintrack.app.analytics.metrics.MetricChange
import com.fintrack.app.analytics.metrics.PercentChange
import com.fintrack.app.core.util.formatPercent
import com.fintrack.app.core.util.formatRupiah
import com.fintrack.app.core.util.formatRupiahSigned

/** One metric compared with a previous span: current, previous, difference and (when defined) %. */
@Composable
fun ComparisonRow(label: String, change: MetricChange) {
    Column(Modifier.fillMaxWidth()) {
        // The percentage describes the whole comparison, so it sits on the metric's title row.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            val percent = change.percent
            if (percent is PercentChange.Value) {
                MoneyText(formatPercent(percent.percent, signed = true), style = MaterialTheme.typography.titleSmall)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            LabeledAmount(stringResource(R.string.dashboard_comparison_current), formatRupiah(change.current), Modifier.weight(1f))
            LabeledAmount(stringResource(R.string.dashboard_comparison_previous), formatRupiah(change.previous), Modifier.weight(1f))
            LabeledAmount(stringResource(R.string.dashboard_comparison_change), formatRupiahSigned(change.absolute), Modifier.weight(1f))
        }
        when (change.percent) {
            PercentChange.ZeroBaseline -> NoteText(stringResource(R.string.dashboard_comparison_zero_baseline))
            PercentChange.NegativeBaseline -> NoteText(stringResource(R.string.dashboard_comparison_negative_baseline))
            is PercentChange.Value -> Unit
        }
    }
}

@Composable
private fun LabeledAmount(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyText(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun NoteText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
