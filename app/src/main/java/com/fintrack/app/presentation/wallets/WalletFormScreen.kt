package com.fintrack.app.presentation.wallets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintrack.app.R
import com.fintrack.app.domain.model.WalletType
import com.fintrack.app.presentation.common.AmountField
import com.fintrack.app.presentation.common.FinTopBar
import com.fintrack.app.presentation.common.FormColumn
import com.fintrack.app.presentation.common.FormTextField
import com.fintrack.app.presentation.common.LoadingState
import com.fintrack.app.presentation.common.MessageState
import com.fintrack.app.presentation.common.SelectOption
import com.fintrack.app.presentation.common.SelectionField
import com.fintrack.app.presentation.common.Spacing
import com.fintrack.app.presentation.common.labelRes

@Composable
fun WalletFormScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: WalletFormViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isSaved) { if (state.isSaved) onSaved() }

    Scaffold(
        topBar = {
            FinTopBar(
                title = stringResource(if (state.isEdit) R.string.wallet_edit_title else R.string.wallet_new_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when {
            state.isLoading -> LoadingState(modifier)
            state.notFound -> MessageState(stringResource(R.string.error_not_found), modifier)
            else -> FormColumn(modifier.verticalScroll(rememberScrollState()).imePadding()) {
                FormTextField(
                    value = state.name,
                    onValueChange = viewModel::onNameChange,
                    label = stringResource(R.string.wallet_field_name),
                    errorRes = state.nameError,
                )
                SelectionField(
                    label = stringResource(R.string.wallet_field_type),
                    selectedId = state.type.name,
                    options = WalletType.entries.map { SelectOption(it.name, stringResource(it.labelRes)) },
                    onSelect = { id -> viewModel.onTypeChange(WalletType.valueOf(id)) },
                )
                AmountField(
                    value = state.initialBalanceText,
                    onValueChange = viewModel::onInitialBalanceChange,
                    label = stringResource(R.string.wallet_field_initial_balance),
                    errorRes = state.balanceError,
                )
                NegativeBalanceSwitch(state.isNegative, viewModel::onNegativeChange)

                state.generalError?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = viewModel::save,
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}

@Composable
private fun NegativeBalanceSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
            .padding(vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.field),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.wallet_initial_balance_negative), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.wallet_initial_balance_negative_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The whole row is toggleable; the switch itself only mirrors the state.
        Switch(checked = checked, onCheckedChange = null)
    }
}
