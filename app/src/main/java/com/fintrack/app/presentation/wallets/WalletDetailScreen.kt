package com.fintrack.app.presentation.wallets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintrack.app.R
import com.fintrack.app.core.ui.theme.LocalFinColors
import com.fintrack.app.presentation.common.ConfirmDialog
import com.fintrack.app.presentation.common.Corners
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
    val deleteBlockedBy by viewModel.deleteBlockedBy.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    var confirmArchive by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val wallet = state.wallet
    LaunchedEffect(deleted) { if (deleted) onBack() }

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
                        } else {
                            IconButton(onClick = viewModel::unarchive) {
                                Icon(painterResource(R.drawable.ic_unarchive), stringResource(R.string.action_unarchive))
                            }
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.action_delete))
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
                    val fin = LocalFinColors.current
                    Column(
                        Modifier
                            .padding(horizontal = Spacing.screen)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Corners.hero))
                            .background(fin.hero)
                            .padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(stringResource(wallet.type.labelRes), style = MaterialTheme.typography.labelLarge, color = fin.onHero.copy(alpha = 0.9f))
                        Text(stringResource(R.string.wallet_balance), style = MaterialTheme.typography.bodyMedium, color = fin.onHero.copy(alpha = 0.9f))
                        MoneyText(state.balanceText, style = MaterialTheme.typography.displaySmall, color = fin.onHero, maxLines = 1)
                        if (state.isNegative) {
                            Text(
                                stringResource(R.string.wallet_balance_negative),
                                style = MaterialTheme.typography.labelMedium,
                                color = fin.onHero,
                                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).background(fin.heroInner).padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    Column(Modifier.padding(horizontal = Spacing.screen + 4.dp)) {
                        if (!wallet.isActive) {
                            Text(
                                stringResource(R.string.wallet_archived_notice),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Spacing.field),
                            )
                        }
                        archiveError?.let {
                            Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.small))
                        }
                        Text(
                            stringResource(R.string.wallet_history),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = Spacing.field),
                        )
                    }
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
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.wallet_delete_title),
            message = stringResource(R.string.wallet_delete_message),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                confirmDelete = false
                viewModel.delete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
    deleteBlockedBy?.let { count ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteBlocked,
            title = { Text(stringResource(R.string.wallet_delete_blocked_title)) },
            text = { Text(pluralStringResource(R.plurals.wallet_delete_blocked_message, count, count)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissDeleteBlocked()
                    if (wallet?.isActive == true) confirmArchive = true
                }) { Text(stringResource(if (wallet?.isActive == true) R.string.action_archive else R.string.action_close)) }
            },
            dismissButton = if (wallet?.isActive == true) {
                { TextButton(onClick = viewModel::dismissDeleteBlocked) { Text(stringResource(R.string.action_cancel)) } }
            } else null,
        )
    }
}
