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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fintrack.app.R
import com.fintrack.app.presentation.common.AppCard
import com.fintrack.app.analytics.insights.InsightRules
import com.fintrack.app.analytics.metrics.MerchantStats
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.core.util.formatPercent
import com.fintrack.app.core.util.formatRupiah
import com.fintrack.app.core.util.formatRupiahSigned
import com.fintrack.app.presentation.common.MoneyText
import com.fintrack.app.presentation.common.NoteText
import com.fintrack.app.presentation.common.ProportionBar
import com.fintrack.app.presentation.common.Spacing
import kotlin.math.roundToLong

private const val RANKING_SIZE = 10

@Composable
internal fun MerchantTab(report: MerchantReport) {
    var byCount by rememberSaveable { mutableStateOf(false) }
    val summary = report.summary
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val coverage = summary.coveragePercent
        if (coverage == null || summary.merchants.isEmpty()) {
            AppCard { NoteText(stringResource(R.string.merchant_empty)) }
            return@Column
        }
        AppCard {
            NoteText(
                stringResource(
                    if (coverage < InsightRules.I3_MIN_COVERAGE) R.string.merchant_coverage_low else R.string.merchant_coverage,
                    formatPercent(coverage),
                ),
            )
            val options = listOf(false, true)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = byCount == option,
                        onClick = { byCount = option },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) { Text(stringResource(if (option) R.string.merchant_rank_by_count else R.string.merchant_rank_by_total)) }
                }
            }
            // Ordering only; every value shown was computed in the analytics layer.
            val ranked = if (byCount) summary.merchants.sortedWith(compareByDescending<MerchantStats> { it.count }.thenBy { it.key.value }) else summary.merchants
            ranked.take(RANKING_SIZE).forEach { stats -> MerchantRow(stats, summary.share(stats) ?: 0.0) }
            if (summary.withoutMerchant > 0) {
                Row(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.merchant_without), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    MoneyText(formatRupiah(summary.withoutMerchant), style = MaterialTheme.typography.bodyMedium)
                }
            }
            NoteText(stringResource(R.string.merchant_normalization_note))
        }
        AppCard {
            Text(stringResource(R.string.merchant_new_title), style = MaterialTheme.typography.titleMedium)
            if (report.newMerchants.isEmpty()) NoteText(stringResource(R.string.merchant_new_empty))
            report.newMerchants.take(RANKING_SIZE).forEach { MerchantRow(it, summary.share(it) ?: 0.0) }
        }
        AppCard {
            Text(
                stringResource(R.string.merchant_changes_title, JakartaTime.formatSpan(report.previous.first, report.previous.last)),
                style = MaterialTheme.typography.titleMedium,
            )
            if (report.changes.isEmpty()) NoteText(stringResource(R.string.merchant_changes_empty))
            report.changes.take(5).forEach { change ->
                Row(Modifier.fillMaxWidth()) {
                    Text(change.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    MoneyText(
                        pluralStringResource(
                            R.plurals.merchant_count_change, kotlin.math.abs(change.countChange),
                            formatRupiahSigned(change.totalChange),
                            if (change.countChange > 0) "+${change.countChange}" else change.countChange.toString(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun MerchantRow(stats: MerchantStats, share: Double) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(stats.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            MoneyText(formatRupiah(stats.total), style = MaterialTheme.typography.bodyLarge)
        }
        ProportionBar(share)
        NoteText(pluralStringResource(R.plurals.merchant_detail, stats.count, stats.count, formatRupiah(stats.mean.roundToLong()), formatPercent(share)))
    }
}
