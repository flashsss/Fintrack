package com.fintrack.app.presentation.wallets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintrack.app.R
import com.fintrack.app.presentation.common.ConfirmDialog
import com.fintrack.app.presentation.common.FinTopBar
import com.fintrack.app.presentation.common.LoadingState
import com.fintrack.app.presentation.common.MessageState
import com.fintrack.app.presentation.common.MoneyText
import com.fintrack.app.presentation.common.Spacing
import com.fintrack.app.presentation.common.labelRes
import com.fintrack.app.presentation.transactions.transactionGroups

@Composable
fun WalletDetailScreen(
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    viewModel: WalletDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val archiveError by viewModel.archiveError.collectAsStateWithLifecycle()
    var confirmArchive by rememberSaveable { mutableStateOf(false) }
    val wallet = state.wallet

    Scaffold(
        topBar = {
            FinTopBar(
                title = wallet?.name ?: stringResource(R.string.wallets_title),
                onBack = onBack,
                actions = {
                    if (wallet != null) {
                        IconButton(onClick = onEdit) {
                            Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.action_edit))
                        }
                        if (wallet.isActive) {
                            IconButton(onClick = { confirmArchive = true }) {
                                Icon(painterResource(R.drawable.ic_archive), stringResource(R.string.action_archive))
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when {
            state.isLoading -> LoadingState(modifier)
            state.loadFailed -> MessageState(stringResource(R.string.error_load_failed), modifier)
            wallet == null -> MessageState(stringResource(R.string.error_not_found), modifier)
            else -> LazyColumn(modifier) {
                item(key = "summary") {
                    Column(Modifier.padding(Spacing.screen)) {
                        Text(
                            stringResource(wallet.type.labelRes),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(stringResource(R.string.wallet_balance), style = MaterialTheme.typography.bodyMedium)
                        MoneyText(
                            state.balanceText,
                            style = MaterialTheme.typography.headlineMedium,
                            color = if (state.isNegative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        if (!wallet.isActive) {
                            Text(
                                stringResource(R.string.wallet_archived_notice),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Spacing.small),
                            )
                        }
                        archiveError?.let {
                            Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.wallet_history),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.field),
                    )
                }
                if (state.history.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.wallet_history_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Spacing.screen),
                        )
                    }
                } else {
                    transactionGroups(state.history, onOpenTransaction)
                }
            }
        }
    }

    if (confirmArchive) {
        ConfirmDialog(
            title = stringResource(R.string.wallet_archive_title),
            message = stringResource(R.string.wallet_archive_message),
            confirmLabel = stringResource(R.string.action_archive),
            onConfirm = {
                confirmArchive = false
                viewModel.archive()
            },
            onDismiss = { confirmArchive = false },
        )
    }
}
