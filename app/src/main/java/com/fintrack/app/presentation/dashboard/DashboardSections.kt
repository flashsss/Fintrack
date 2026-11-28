package com.fintrack.app.presentation.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.fintrack.app.R
import com.fintrack.app.analytics.metrics.MetricChange
import com.fintrack.app.analytics.metrics.PercentChange
import com.fintrack.app.analytics.metrics.PeriodComparison
import com.fintrack.app.analytics.metrics.PeriodTotals
import com.fintrack.app.core.ui.theme.LocalFinColors
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.core.util.formatPercent
import com.fintrack.app.core.util.formatRupiah
import com.fintrack.app.core.util.formatRupiahSigned
import com.fintrack.app.presentation.common.AppCard
import com.fintrack.app.presentation.common.CardHeader
import com.fintrack.app.presentation.common.ComparisonRow
import com.fintrack.app.presentation.common.Corners
import com.fintrack.app.presentation.common.LetterChip
import com.fintrack.app.presentation.common.StatTile
import com.fintrack.app.presentation.common.TileTone
import com.fintrack.app.presentation.common.TransferChip
import com.fintrack.app.presentation.common.MoneyText
import com.fintrack.app.presentation.common.NoteText
import com.fintrack.app.presentation.common.Spacing
import com.fintrack.app.presentation.common.labelRes
import com.fintrack.app.presentation.transactions.AmountDirection

/** Hero card: total balance of active wallets. */
@Composable
internal fun BalanceSection(overview: WalletOverview) {
    val fin = LocalFinColors.current
    val total = overview.total.total
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.hero))
            .background(fin.hero)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.dashboard_balance_title), style = MaterialTheme.typography.labelLarge, color = fin.onHero.copy(alpha = 0.9f))
        MoneyText(formatRupiah(total), style = MaterialTheme.typography.displaySmall, color = fin.onHero, maxLines = 1)
        Text(
            pluralStringResource(R.plurals.dashboard_balance_note, overview.total.activeCount, overview.total.activeCount),
            style = MaterialTheme.typography.bodySmall,
            color = fin.onHero.copy(alpha = 0.9f),
        )
        if (total < 0) {
            Text(
                stringResource(R.string.dashboard_balance_negative),
                style = MaterialTheme.typography.labelMedium,
                color = fin.onHero,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).background(fin.heroInner).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
internal fun SummarySection(totals: PeriodTotals) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(stringResource(R.string.dashboard_income), formatRupiah(totals.income), TileTone.INCOME, Modifier.weight(1f))
            StatTile(stringResource(R.string.dashboard_expense), formatRupiah(totals.expense), TileTone.EXPENSE, Modifier.weight(1f))
        }
        AppCard {
            Metric(
                label = stringResource(R.string.dashboard_net_cash_flow),
                value = formatRupiahSigned(totals.netCashFlow),
                color = if (totals.netCashFlow < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                valueStyle = MaterialTheme.typography.headlineSmall,
            )
            NoteText(stringResource(R.string.dashboard_net_cash_flow_note))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
    AppCard {
        CardHeader(stringResource(R.string.dashboard_comparison_title))
        if (comparison == null) {
            NoteText(stringResource(R.string.dashboard_comparison_custom))
            return@AppCard
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
internal fun WalletsSection(overview: WalletOverview, onOpenWallet: (String) -> Unit) {
    AppCard(contentPadding = 6.dp) {
        CardHeader(stringResource(R.string.dashboard_wallets_title), Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp))
        if (overview.wallets.isEmpty()) {
            NoteText(stringResource(R.string.dashboard_wallets_empty), Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
            return@AppCard
        }
        Column {
            overview.wallets.forEachIndexed { index, (wallet, balance) ->
                ListItem(
                    onClick = { onOpenWallet(wallet.id) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    // Position, not a hash: the first five wallets always get different colors.
                    leadingContent = { LetterChip(wallet.name, colorIndex = index) },
                    supportingContent = { Text(stringResource(wallet.type.labelRes)) },
                    trailingContent = {
                        MoneyText(
                            formatRupiah(balance),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (balance < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                ) { Text(wallet.name, style = MaterialTheme.typography.titleSmall) }
            }
        }
    }
}

@Composable
internal fun RecentSection(
    recent: List<RecentTransaction>,
    onOpenTransaction: (String) -> Unit,
    onSeeAllTransactions: () -> Unit,
) {
    val fin = LocalFinColors.current
    AppCard(contentPadding = 6.dp) {
        CardHeader(stringResource(R.string.dashboard_recent_title), Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp)) {
            TextButton(onClick = onSeeAllTransactions) { Text(stringResource(R.string.dashboard_see_all_transactions)) }
        }
        if (recent.isEmpty()) {
            NoteText(stringResource(R.string.dashboard_recent_empty), Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
        }
        Column {
            recent.forEach { item ->
                val row = item.row
                val title = row.categoryLabel ?: stringResource(R.string.transaction_type_transfer)
                ListItem(
                    onClick = { onOpenTransaction(row.id) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { if (row.categoryLabel == null) TransferChip() else LetterChip(title) },
                    overlineContent = { Text("${stringResource(row.type.labelRes)} · ${item.dateText}") },
                    supportingContent = {
                        Text(listOfNotNull(row.walletLabel, row.note).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    trailingContent = {
                        MoneyText(
                            row.amountText,
                            style = MaterialTheme.typography.titleSmall,
                            color = when (row.direction) {
                                AmountDirection.IN -> fin.income
                                AmountDirection.OUT -> MaterialTheme.colorScheme.onSurface
                                AmountDirection.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
