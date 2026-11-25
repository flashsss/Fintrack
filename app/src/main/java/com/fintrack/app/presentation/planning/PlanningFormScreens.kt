package com.fintrack.app.presentation.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintrack.app.R
import com.fintrack.app.presentation.common.AmountField
import com.fintrack.app.presentation.common.ConfirmDialog
import com.fintrack.app.presentation.common.DateField
import com.fintrack.app.presentation.common.FinTopBar
import com.fintrack.app.presentation.common.FormColumn
import com.fintrack.app.presentation.common.FormTextField
import com.fintrack.app.presentation.common.LoadingState
import com.fintrack.app.presentation.common.MessageState
import com.fintrack.app.presentation.common.NoteText
import com.fintrack.app.presentation.common.SelectOption
import com.fintrack.app.presentation.common.SelectionField
import com.fintrack.app.presentation.common.Spacing
import java.time.LocalDate

/** Shared scaffold: title, back, optional delete with confirmation, loading/not-found states. */
@Composable
private fun PlanningFormScaffold(
    title: String,
    status: FormStatus,
    onBack: () -> Unit,
    onDone: () -> Unit,
    deleteTitle: String,
    deleteMessage: String,
    onDelete: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(status.isDone) { if (status.isDone) onDone() }
    Scaffold(
        topBar = {
            FinTopBar(title, onBack = onBack, actions = {
                if (onDelete != null) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.action_delete))
                    }
                }
            })
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when {
            status.isLoading -> LoadingState(modifier)
            status.notFound -> MessageState(stringResource(R.string.error_not_found), modifier)
            else -> FormColumn(modifier.verticalScroll(rememberScrollState()).imePadding()) {
                content()
                status.generalError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmDelete && onDelete != null) {
        ConfirmDialog(deleteTitle, deleteMessage, stringResource(R.string.action_delete), onConfirm = {
            confirmDelete = false
            onDelete()
        }, onDismiss = { confirmDelete = false })
    }
}

@Composable
fun BudgetFormScreen(onBack: () -> Unit, viewModel: BudgetFormViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val categories by viewModel.categoryOptions.collectAsStateWithLifecycle()
    PlanningFormScaffold(
        title = stringResource(if (state.isEdit) R.string.budget_edit_title else R.string.budget_new_title),
        status = state.status,
        onBack = onBack,
        onDone = onBack,
        deleteTitle = stringResource(R.string.budget_delete_title),
        deleteMessage = stringResource(R.string.budget_delete_message),
        onDelete = if (state.isEdit) viewModel::delete else null,
    ) {
        SelectionField(
            label = stringResource(R.string.budget_field_scope),
            selectedId = state.categoryId,
            options = listOf(SelectOption("", stringResource(R.string.budget_all_expenses))) + categories,
            onSelect = viewModel::onCategoryChange,
            enabled = !state.isEdit,
            errorRes = state.categoryError,
            supportingText = if (state.isEdit) stringResource(R.string.budget_scope_locked) else null,
        )
        AmountField(state.amountText, viewModel::onAmountChange, stringResource(R.string.budget_field_amount), state.amountError)
        Button(onClick = viewModel::save, enabled = !state.status.isSaving, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_save))
        }
    }
}

@Composable
fun GoalFormScreen(onBack: () -> Unit, today: LocalDate, viewModel: GoalFormViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PlanningFormScaffold(
        title = stringResource(if (state.isEdit) R.string.goal_edit_title else R.string.goal_new_title),
        status = state.status,
        onBack = onBack,
        onDone = onBack,
        deleteTitle = stringResource(R.string.goal_delete_title),
        deleteMessage = stringResource(R.string.goal_delete_message),
        onDelete = if (state.isEdit) viewModel::delete else null,
    ) {
        FormTextField(state.name, viewModel::onNameChange, stringResource(R.string.goal_field_name), errorRes = state.nameError)
        AmountField(state.targetText, viewModel::onTargetChange, stringResource(R.string.goal_field_target), state.targetError)
        AmountField(state.currentText, viewModel::onCurrentChange, stringResource(R.string.goal_field_current), state.currentError)
        val hasDeadline = state.targetDate != null
        Row(
            Modifier.fillMaxWidth().toggleable(hasDeadline, role = Role.Switch) { on ->
                viewModel.onTargetDateChange(if (on) today.plusMonths(6) else null)
            }.padding(vertical = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.field),
        ) {
            Text(stringResource(R.string.goal_set_deadline), Modifier.weight(1f))
            Switch(checked = hasDeadline, onCheckedChange = null)
        }
        state.targetDate?.let { DateField(stringResource(R.string.goal_field_deadline), it, viewModel::onTargetDateChange) }
        NoteText(stringResource(R.string.goal_note))
        Button(onClick = viewModel::save, enabled = !state.status.isSaving, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_save))
        }
    }
}
