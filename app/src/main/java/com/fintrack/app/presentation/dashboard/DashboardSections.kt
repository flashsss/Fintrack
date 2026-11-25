package com.fintrack.app.presentation.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.fintrack.app.R
import com.fintrack.app.analytics.metrics.MetricChange
import com.fintrack.app.analytics.metrics.PercentChange
import com.fintrack.app.analytics.metrics.PeriodComparison
import com.fintrack.app.analytics.metrics.PeriodTotals
import com.fintrack.app.core.ui.theme.LocalChartColors
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.core.util.formatPercent
import com.fintrack.app.core.util.formatRupiah
import com.fintrack.app.core.util.formatRupiahSigned
import com.fintrack.app.presentation.common.MoneyText
import com.fintrack.app.presentation.common.Spacing
import com.fintrack.app.presentation.common.labelRes
import com.fintrack.app.presentation.transactions.AmountDirection

@Composable
internal fun BalanceSection(overview: WalletOverview) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.small)) {
        Text(
            stringResource(R.string.dashboard_balance_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MoneyText(
            formatRupiah(overview.total.total),
            style = MaterialTheme.typography.displaySmall,
            color = if (overview.total.total < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            pluralStringResource(R.plurals.dashboard_balance_note, overview.total.activeCount, overview.total.activeCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SummarySection(totals: PeriodTotals) {
    val colors = LocalChartColors.current
    SectionDivider()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.small),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.field)) {
            Metric(stringResource(R.string.dashboard_income), formatRupiah(totals.income), Modifier.weight(1f), colors.income)
            Metric(stringResource(R.string.dashboard_expense), formatRupiah(totals.expense), Modifier.weight(1f), colors.expense)
        }
        Metric(
            label = stringResource(R.string.dashboard_net_cash_flow),
            value = formatRupiahSigned(totals.netCashFlow),
            color = if (totals.netCashFlow < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.dashboard_net_cash_flow_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.field)) {
            Metric(
                label = stringResource(R.string.dashboard_saving_rate),
                value = totals.savingRatePercent?.let { formatPercent(it) }
                    ?: stringResource(R.string.dashboard_saving_rate_unavailable),
                modifier = Modifier.weight(1f),
                valueStyle = if (totals.savingRatePercent == null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleLarge,
            )
            Metric(
                label = stringResource(R.string.nav_transactions),
                value = transactionCountText(totals),
                modifier = Modifier.weight(1f),
                valueStyle = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun transactionCountText(totals: PeriodTotals): String {
    val count = pluralStringResource(R.plurals.dashboard_transaction_count, totals.transactionCount, totals.transactionCount)
    return if (totals.transferCount > 0) {
        pluralStringResource(R.plurals.dashboard_transaction_count_with_transfers, totals.transferCount, count, totals.transferCount)
    } else {
        count
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    valueStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleLarge,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyText(value, style = valueStyle, color = color)
    }
}

@Composable
internal fun ComparisonSection(comparison: PeriodComparison?) {
    SectionDivider()
    SectionTitle(stringResource(R.string.dashboard_comparison_title))
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.small),
        verticalArrangement = Arrangement.spacedBy(Spacing.field),
    ) {
        if (comparison == null) {
            NoteText(stringResource(R.string.dashboard_comparison_custom))
            return@Column
        }
        val spans = comparison.spans
        Text(
            stringResource(
                R.string.dashboard_comparison_spans,
                JakartaTime.formatSpan(spans.current.first, spans.current.last),
                JakartaTime.formatSpan(spans.previous.first, spans.previous.last),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        ComparisonRow(stringResource(R.string.dashboard_income), comparison.income)
        ComparisonRow(stringResource(R.string.dashboard_expense), comparison.expense)
        ComparisonRow(stringResource(R.string.dashboard_net_cash_flow), comparison.netCashFlow)
        comparison.previousFullMonth?.let { full ->
            NoteText(stringResource(R.string.dashboard_comparison_fair_note))
            NoteText(
                stringResource(
                    R.string.dashboard_comparison_full_month,
                    JakartaTime.formatMonth(spans.previous.first),
                    formatRupiah(full.income),
                    formatRupiah(full.expense),
                ),
            )
        }
    }
}

@Composable
private fun ComparisonRow(label: String, change: MetricChange) {
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
internal fun WalletsSection(overview: WalletOverview, onOpenWallet: (String) -> Unit) {
    SectionDivider()
    SectionTitle(stringResource(R.string.dashboard_wallets_title))
    if (overview.wallets.isEmpty()) {
        NoteText(stringResource(R.string.dashboard_wallets_empty), Modifier.padding(horizontal = Spacing.screen))
        return
    }
    overview.wallets.forEach { (wallet, balance) ->
        ListItem(
            onClick = { onOpenWallet(wallet.id) },
            supportingContent = { Text(stringResource(wallet.type.labelRes)) },
            trailingContent = {
                MoneyText(
                    formatRupiah(balance),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (balance < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            },
        ) { Text(wallet.name) }
    }
}

@Composable
internal fun RecentSection(
    recent: List<RecentTransaction>,
    onOpenTransaction: (String) -> Unit,
    onSeeAllTransactions: () -> Unit,
) {
    SectionDivider()
    SectionTitle(stringResource(R.string.dashboard_recent_title))
    if (recent.isEmpty()) {
        NoteText(stringResource(R.string.dashboard_recent_empty), Modifier.padding(horizontal = Spacing.screen))
    }
    recent.forEach { item ->
        val row = item.row
        ListItem(
            onClick = { onOpenTransaction(row.id) },
            overlineContent = { Text("${stringResource(row.type.labelRes)} · ${item.dateText}") },
            supportingContent = {
                Text(listOfNotNull(row.walletLabel, row.note).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            trailingContent = {
                MoneyText(
                    row.amountText,
                    style = MaterialTheme.typography.titleMedium,
                    color = when (row.direction) {
                        AmountDirection.IN -> MaterialTheme.colorScheme.primary
                        AmountDirection.OUT -> MaterialTheme.colorScheme.onSurface
                        AmountDirection.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
        ) {
            Text(row.categoryLabel ?: stringResource(R.string.transaction_type_transfer), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.small), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onSeeAllTransactions) { Text(stringResource(R.string.dashboard_see_all_transactions)) }
    }
}

@Composable
internal fun NoteText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
