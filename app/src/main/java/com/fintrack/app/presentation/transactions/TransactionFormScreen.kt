package com.fintrack.app.presentation.transactions

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintrack.app.R
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.presentation.common.AmountField
import com.fintrack.app.presentation.common.DateField
import com.fintrack.app.presentation.common.FinTopBar
import com.fintrack.app.presentation.common.FormColumn
import com.fintrack.app.presentation.common.FormTextField
import com.fintrack.app.presentation.common.LoadingState
import com.fintrack.app.presentation.common.MessageState
import com.fintrack.app.presentation.common.SelectionField
import com.fintrack.app.presentation.common.labelRes

@Composable
fun TransactionFormScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: TransactionFormViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val choices by viewModel.choices.collectAsStateWithLifecycle()

    LaunchedEffect(state.isSaved) { if (state.isSaved) onSaved() }

    Scaffold(
        topBar = {
            FinTopBar(
                title = stringResource(if (state.isEdit) R.string.transaction_edit_title else R.string.transaction_new_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when {
            state.isLoading -> LoadingState(modifier)
            state.notFound -> MessageState(stringResource(R.string.error_not_found), modifier)
            else -> FormColumn(modifier.verticalScroll(rememberScrollState()).imePadding()) {
                TransactionFormFields(state, choices, viewModel)
            }
        }
    }
}

@Composable
private fun TransactionFormFields(
    state: TransactionFormState,
    choices: TransactionFormChoices,
    viewModel: TransactionFormViewModel,
) {
    val isTransfer = state.type == TransactionType.TRANSFER
    val types = TransactionType.entries

    Text(stringResource(R.string.transaction_field_type), style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        types.forEachIndexed { index, type ->
            SegmentedButton(
                selected = state.type == type,
                onClick = { viewModel.onTypeChange(type) },
                shape = SegmentedButtonDefaults.itemShape(index, types.size),
            ) { Text(stringResource(type.labelRes)) }
        }
    }

    AmountField(
        value = state.amountText,
        onValueChange = viewModel::onAmountChange,
        label = stringResource(R.string.transaction_field_amount),
        errorRes = state.errors.amount,
    )

    SelectionField(
        label = stringResource(if (isTransfer) R.string.transaction_field_source_wallet else R.string.transaction_field_wallet),
        selectedId = state.walletId,
        options = choices.sourceWallets,
        onSelect = viewModel::onWalletChange,
        errorRes = state.errors.wallet,
        supportingText = if (choices.sourceWallets.isEmpty()) stringResource(R.string.transaction_no_wallets) else null,
    )

    if (isTransfer) {
        SelectionField(
            label = stringResource(R.string.transaction_field_destination_wallet),
            selectedId = state.destinationWalletId,
            options = choices.destinationWallets,
            onSelect = viewModel::onDestinationChange,
            errorRes = state.errors.destination,
        )
    } else {
        SelectionField(
            label = stringResource(R.string.transaction_field_category),
            selectedId = state.categoryId,
            options = choices.categories,
            onSelect = viewModel::onCategoryChange,
            errorRes = state.errors.category,
            supportingText = if (choices.categories.isEmpty()) stringResource(R.string.transaction_no_categories) else null,
        )
    }

    DateField(
        label = stringResource(R.string.transaction_field_date),
        date = state.date,
        onDateSelected = viewModel::onDateChange,
    )

    FormTextField(
        value = state.description,
        onValueChange = viewModel::onDescriptionChange,
        label = stringResource(R.string.field_optional, stringResource(R.string.transaction_field_description)),
    )

    if (!isTransfer) {
        FormTextField(
            value = state.merchant,
            onValueChange = viewModel::onMerchantChange,
            label = stringResource(R.string.field_optional, stringResource(R.string.transaction_field_merchant)),
        )
    }

    state.generalError?.let {
        Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }

    Button(
        onClick = viewModel::save,
        enabled = !state.isSaving,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.action_save)) }
}
