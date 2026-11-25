package com.fintrack.app.presentation.wallets

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintrack.app.R
import com.fintrack.app.presentation.common.FinTopBar
import com.fintrack.app.presentation.common.LoadingState
import com.fintrack.app.presentation.common.MessageState
import com.fintrack.app.presentation.common.MoneyText
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
        topBar = { FinTopBar(stringResource(R.string.wallets_title)) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddWallet,
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text(stringResource(R.string.wallets_add)) },
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when {
            state.isLoading -> LoadingState(modifier)
            state.loadFailed -> MessageState(stringResource(R.string.error_load_failed), modifier)
            state.active.isEmpty() && state.archived.isEmpty() ->
                MessageState(stringResource(R.string.wallets_empty), modifier)
            else -> LazyColumn(modifier, contentPadding = PaddingValues(bottom = 96.dp)) {
                items(state.active, key = { it.id }) { WalletRowItem(it, onClick = { onOpenWallet(it.id) }) }
                if (state.archived.isNotEmpty()) {
                    item(key = "archived-header") {
                        Text(
                            stringResource(R.string.wallets_archived_section),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = Spacing.screen, top = Spacing.field * 2, bottom = Spacing.small),
                        )
                    }
                    items(state.archived, key = { it.id }) { WalletRowItem(it, onClick = { onOpenWallet(it.id) }) }
                }
            }
        }
    }
}

@Composable
private fun WalletRowItem(row: WalletRow, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        supportingContent = { Text(stringResource(row.type.labelRes)) },
        trailingContent = {
            MoneyText(
                row.balanceText,
                style = MaterialTheme.typography.titleMedium,
                color = if (row.isNegative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
    ) { Text(row.name) }
    HorizontalDivider()
}
