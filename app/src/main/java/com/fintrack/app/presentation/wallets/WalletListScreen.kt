package com.fintrack.app.presentation.wallets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintrack.app.R
import com.fintrack.app.presentation.common.AddFab
import com.fintrack.app.presentation.common.AppCard
import com.fintrack.app.presentation.common.LetterChip
import com.fintrack.app.presentation.common.LoadingState
import com.fintrack.app.presentation.common.MessageState
import com.fintrack.app.presentation.common.MoneyText
import com.fintrack.app.presentation.common.ScreenHeader
import com.fintrack.app.presentation.common.Spacing
import com.fintrack.app.presentation.common.labelRes

@Composable
fun WalletListScreen(
    onAddWallet: () -> Unit,
    onOpenWallet: (String) -> Unit,
    viewModel: WalletListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { ScreenHeader(stringResource(R.string.wallets_title)) },
        floatingActionButton = {
            AddFab(stringResource(R.string.wallets_add), onClick = onAddWallet)
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when {
            state.isLoading -> LoadingState(modifier)
            state.loadFailed -> MessageState(stringResource(R.string.error_load_failed), modifier)
            state.active.isEmpty() && state.archived.isEmpty() ->
                MessageState(stringResource(R.string.wallets_empty), modifier)
            else -> LazyColumn(
                modifier,
                contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.active.isNotEmpty()) {
                    item(key = "active") { WalletCard(state.active, onOpenWallet) }
                }
                if (state.archived.isNotEmpty()) {
                    item(key = "archived-header") {
                        Text(
                            stringResource(R.string.wallets_archived_section),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = Spacing.field),
                        )
                    }
                    item(key = "archived") { WalletCard(state.archived, onOpenWallet, colorOffset = state.active.size) }
                }
            }
        }
    }
}

/** Wallets in one card; chips are colored by position so neighbouring wallets differ. */
@Composable
private fun WalletCard(rows: List<WalletRow>, onOpenWallet: (String) -> Unit, colorOffset: Int = 0) {
    AppCard(contentPadding = 4.dp) {
        Column {
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 72.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                WalletRowItem(row, colorOffset + index, onClick = { onOpenWallet(row.id) })
            }
        }
    }
}

@Composable
private fun WalletRowItem(row: WalletRow, colorIndex: Int, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { LetterChip(row.name, colorIndex = colorIndex) },
        supportingContent = { Text(stringResource(row.type.labelRes)) },
        trailingContent = {
            MoneyText(
                row.balanceText,
                style = MaterialTheme.typography.titleSmall,
                color = if (row.isNegative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
    ) { Text(row.name, style = MaterialTheme.typography.titleSmall) }
}
