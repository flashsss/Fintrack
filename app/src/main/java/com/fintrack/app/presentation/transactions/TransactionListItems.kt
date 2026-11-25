package com.fintrack.app.presentation.transactions

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.fintrack.app.R
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.presentation.common.MoneyText
import com.fintrack.app.presentation.common.Spacing

/** Day-grouped transaction rows, shared by the history and wallet detail screens. */
fun LazyListScope.transactionGroups(groups: List<TransactionDayGroup>, onClick: (String) -> Unit) {
    groups.forEach { group ->
        item(key = "header-${group.date}") {
            Text(
                text = JakartaTime.formatDayHeader(group.date),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.field),
            )
        }
        items(group.rows, key = { it.id }) { row ->
            TransactionRowItem(row, onClick = { onClick(row.id) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun TransactionRowItem(row: TransactionRow, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        supportingContent = {
            val text = listOfNotNull(row.walletLabel, row.note).joinToString(" · ")
            Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            MoneyText(
                text = row.amountText,
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
