package com.fintrack.app.presentation.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintrack.app.R
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.presentation.common.ArchivedLabel
import com.fintrack.app.presentation.common.ConfirmDialog
import com.fintrack.app.presentation.common.FinTopBar
import com.fintrack.app.presentation.common.LoadingState
import com.fintrack.app.presentation.common.MessageState
import com.fintrack.app.presentation.common.MoneyText
import com.fintrack.app.presentation.common.Spacing
import com.fintrack.app.presentation.common.labelRes

@Composable
fun TransactionDetailScreen(
    onBack: () -> Unit,
    onEdit: () -> Unit,
    viewModel: TransactionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val deleteState by viewModel.deleteState.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(deleteState.isDeleted) { if (deleteState.isDeleted) onBack() }

    Scaffold(
        topBar = {
            FinTopBar(
                title = stringResource(R.string.transaction_detail_title),
                onBack = onBack,
                actions = {
                    if (state.detail != null) {
                        IconButton(onClick = onEdit) {
                            Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.action_edit))
                        }
                        IconButton(onClick = { confirmDelete = true }, enabled = !deleteState.isDeleting) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.action_delete))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        val detail = state.detail
        when {
            state.isLoading -> LoadingState(modifier)
            state.loadFailed -> MessageState(stringResource(R.string.error_load_failed), modifier)
            // After a successful delete the row disappears; navigation back is already under way.
            detail == null -> if (deleteState.isDeleted) LoadingState(modifier) else {
                MessageState(stringResource(R.string.error_not_found), modifier)
            }
            else -> Column(modifier.verticalScroll(rememberScrollState()).padding(Spacing.screen)) {
                Text(stringResource(detail.type.labelRes), style = MaterialTheme.typography.labelLarge)
                MoneyText(detail.amountText, style = MaterialTheme.typography.headlineMedium)
                deleteState.error?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider(Modifier.padding(vertical = Spacing.field))

                val isTransfer = detail.type == TransactionType.TRANSFER
                DetailRow(
                    label = stringResource(if (isTransfer) R.string.transaction_field_source_wallet else R.string.transaction_field_wallet),
                    reference = detail.wallet,
                )
                if (isTransfer) {
                    DetailRow(stringResource(R.string.transaction_field_destination_wallet), detail.destinationWallet)
                } else {
                    DetailRow(stringResource(R.string.transaction_field_category), detail.category)
                }
                DetailRow(stringResource(R.string.transaction_field_date), detail.dateText)
                detail.description?.let { DetailRow(stringResource(R.string.transaction_field_description), it) }
                detail.merchant?.let { DetailRow(stringResource(R.string.transaction_field_merchant), it) }
                DetailRow(stringResource(R.string.transaction_field_source), stringResource(detail.source.labelRes))
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.transaction_delete_title),
            message = stringResource(R.string.transaction_delete_message),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                confirmDelete = false
                viewModel.delete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun DetailRow(label: String, reference: ReferenceLabel?) {
    DetailRow(label, reference?.name ?: stringResource(R.string.transaction_unknown_reference), reference?.isArchived == true)
}

@Composable
private fun DetailRow(label: String, value: String, isArchived: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.small)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.bodyLarge)
            if (isArchived) ArchivedLabel()
        }
    }
}
