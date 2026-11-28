package com.fintrack.app.presentation.transactions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fintrack.app.R
import com.fintrack.app.core.ui.theme.LocalFinColors
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.presentation.common.AppCard
import com.fintrack.app.presentation.common.LetterChip
import com.fintrack.app.presentation.common.MoneyText
import com.fintrack.app.presentation.common.Spacing
import com.fintrack.app.presentation.common.TransferChip

/** Day-grouped transaction rows, one card per day; shared by the history and wallet detail screens. */
fun LazyListScope.transactionGroups(groups: List<TransactionDayGroup>, onClick: (String) -> Unit) {
    groups.forEach { group ->
        item(key = "header-${group.date}") {
            Text(
                text = JakartaTime.formatDayHeader(group.date),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.screen + 4.dp, end = Spacing.screen, top = Spacing.field, bottom = Spacing.small),
            )
        }
        item(key = "rows-${group.date}") {
            AppCard(Modifier.padding(horizontal = Spacing.screen), contentPadding = 4.dp) {
                Column {
                    group.rows.forEachIndexed { index, row ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 72.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        TransactionRowItem(row, onClick = { onClick(row.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionRowItem(row: TransactionRow, onClick: () -> Unit) {
    val title = row.categoryLabel ?: stringResource(R.string.transaction_type_transfer)
    val fin = LocalFinColors.current
    ListItem(
        onClick = onClick,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { if (row.categoryLabel == null) TransferChip() else LetterChip(title) },
        supportingContent = {
            val text = listOfNotNull(row.walletLabel, row.note).joinToString(" · ")
            Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            MoneyText(
                text = row.amountText,
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
