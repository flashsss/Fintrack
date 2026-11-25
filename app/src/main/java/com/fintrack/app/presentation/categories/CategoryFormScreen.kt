package com.fintrack.app.presentation.categories

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.presentation.common.ConfirmDialog
import com.fintrack.app.presentation.common.FinTopBar
import com.fintrack.app.presentation.common.FormColumn
import com.fintrack.app.presentation.common.FormTextField
import com.fintrack.app.presentation.common.LoadingState
import com.fintrack.app.presentation.common.MessageState
import com.fintrack.app.presentation.common.SelectOption
import com.fintrack.app.presentation.common.SelectionField
import com.fintrack.app.presentation.common.labelRes

/** Id used in the parent selector for "no parent"; never stored. */
private const val NO_PARENT_ID = ""

@Composable
fun CategoryFormScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: CategoryFormViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val parentOptions by viewModel.parentOptions.collectAsStateWithLifecycle()
    var confirmArchive by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.isSaved, state.isArchivedNow) {
        if (state.isSaved || state.isArchivedNow) onDone()
    }

    Scaffold(
        topBar = {
            FinTopBar(
                title = stringResource(if (state.isEdit) R.string.category_edit_title else R.string.category_new_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when {
            state.isLoading -> LoadingState(modifier)
            state.notFound -> MessageState(stringResource(R.string.error_not_found), modifier)
            else -> FormColumn(modifier.verticalScroll(rememberScrollState()).imePadding()) {
                if (state.isArchived) {
                    Text(
                        stringResource(R.string.category_archived_notice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FormTextField(
                    value = state.name,
                    onValueChange = viewModel::onNameChange,
                    label = stringResource(R.string.category_field_name),
                    errorRes = state.nameError,
                )

                Text(stringResource(R.string.category_field_type), style = MaterialTheme.typography.labelLarge)
                val types = listOf(CategoryType.EXPENSE, CategoryType.INCOME)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    types.forEachIndexed { index, type ->
                        SegmentedButton(
                            selected = state.type == type,
                            onClick = { viewModel.onTypeChange(type) },
                            enabled = !state.isEdit,
                            shape = SegmentedButtonDefaults.itemShape(index, types.size),
                        ) { Text(stringResource(type.labelRes)) }
                    }
                }
                if (state.isEdit) {
                    Text(
                        stringResource(R.string.category_type_locked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SelectionField(
                    label = stringResource(R.string.category_field_parent),
                    selectedId = state.parentId ?: NO_PARENT_ID,
                    options = listOf(SelectOption(NO_PARENT_ID, stringResource(R.string.category_no_parent))) + parentOptions,
                    onSelect = { id -> viewModel.onParentChange(id.takeIf { it != NO_PARENT_ID }) },
                    errorRes = state.parentError,
                )

                state.generalError?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = viewModel::save,
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_save)) }

                if (state.isEdit && !state.isArchived) {
                    OutlinedButton(onClick = { confirmArchive = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(painterResource(R.drawable.ic_archive), contentDescription = null)
                        Text(stringResource(R.string.action_archive))
                    }
                }
            }
        }
    }

    if (confirmArchive) {
        ConfirmDialog(
            title = stringResource(R.string.category_archive_title),
            message = stringResource(R.string.category_archive_message),
            confirmLabel = stringResource(R.string.action_archive),
            onConfirm = {
                confirmArchive = false
                viewModel.archive()
            },
            onDismiss = { confirmArchive = false },
        )
    }
}
