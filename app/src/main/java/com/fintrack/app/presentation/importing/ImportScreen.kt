package com.fintrack.app.presentation.importing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintrack.app.R
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.core.util.formatRupiah
import com.fintrack.app.domain.importing.CsvTable
import com.fintrack.app.domain.importing.DuplicateKind
import com.fintrack.app.domain.importing.ImportDateFormat
import com.fintrack.app.domain.importing.ImportPlan
import com.fintrack.app.domain.importing.RowProblem
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.presentation.common.ConfirmDialog
import com.fintrack.app.presentation.common.FinTopBar
import com.fintrack.app.presentation.common.FormColumn
import com.fintrack.app.presentation.common.NoteText
import com.fintrack.app.presentation.common.SelectOption
import com.fintrack.app.presentation.common.SelectionField
import com.fintrack.app.presentation.common.Spacing

/** Types offered by the system file picker; some apps label CSV files as plain text or Excel. */
private val CSV_MIME_TYPES = arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel")

/** Rows listed per review section; the rest are counted. */
private const val MAX_LISTED_ROWS = 50

@Composable
fun ImportScreen(onBack: () -> Unit, viewModel: ImportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val choices by viewModel.choices.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onFilePicked(uri.toString())
    }
    var confirm by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = { FinTopBar(stringResource(R.string.import_title), onBack = onBack) }) { padding ->
        FormColumn(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            when (state.stage) {
                ImportStage.PICK -> PickStage(state.isWorking) { picker.launch(CSV_MIME_TYPES) }
                ImportStage.MAP -> MapStage(state, choices, viewModel)
                ImportStage.REVIEW -> ReviewStage(state, viewModel, onImport = { confirm = true })
                ImportStage.DONE -> DoneStage(state.importedCount, onBack)
            }
            state.rowLimitExceeded?.let { limit ->
                Text(pluralStringResource(R.plurals.import_error_too_many_rows, limit, limit), color = MaterialTheme.colorScheme.error)
            }
            state.error?.let { error ->
                val text = state.errorArg?.let { stringResource(error, it) } ?: stringResource(error)
                Text(text, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirm) {
        val count = state.rowsToImport.size
        val wallet = choices.wallets.firstOrNull { it.id == state.mapping.walletId }?.label.orEmpty()
        ConfirmDialog(
            title = stringResource(R.string.import_confirm_title),
            message = pluralStringResource(R.plurals.import_confirm_message, count, count, wallet),
            confirmLabel = stringResource(R.string.import_action_import),
            onConfirm = {
                confirm = false
                viewModel.confirmImport()
            },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun PickStage(isWorking: Boolean, onPick: () -> Unit) {
    Text(stringResource(R.string.import_intro), style = MaterialTheme.typography.bodyLarge)
    NoteText(stringResource(R.string.import_format_note))
    Button(onClick = onPick, enabled = !isWorking, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.import_action_pick))
    }
}

@Composable
private fun MapStage(state: ImportUiState, choices: ImportChoices, viewModel: ImportViewModel) {
    val table = state.table ?: return
    val m = state.mapping
    val errors = state.mappingErrors
    Text(pluralStringResource(R.plurals.import_rows_found, table.rows.size, table.rows.size), style = MaterialTheme.typography.titleMedium)
    Preview(table)
    HorizontalDivider()

    val columns = table.header.mapIndexed { index, name ->
        SelectOption(index.toString(), name.ifBlank { stringResource(R.string.import_column_unnamed, index + 1) })
    }
    val none = SelectOption("", stringResource(R.string.import_column_none))
    SelectionField(stringResource(R.string.import_field_date), m.date?.toString(), columns,
        { id -> viewModel.onMappingChange { it.copy(date = id.toInt()) } }, errorRes = errors.date)
    SelectionField(
        stringResource(R.string.import_field_date_format), m.dateFormat.name,
        ImportDateFormat.entries.map { SelectOption(it.name, dateFormatLabel(it)) },
        { id -> viewModel.onMappingChange { it.copy(dateFormat = ImportDateFormat.valueOf(id)) } },
    )
    SelectionField(stringResource(R.string.import_field_amount), m.amount?.toString(), columns,
        { id -> viewModel.onMappingChange { it.copy(amount = id.toInt()) } }, errorRes = errors.amount,
        supportingText = stringResource(R.string.import_amount_sign_note))
    SelectionField(stringResource(R.string.import_field_description), m.description?.toString().orEmpty(), listOf(none) + columns,
        { id -> viewModel.onMappingChange { it.copy(description = id.toIntOrNull()) } })
    SelectionField(stringResource(R.string.import_field_merchant), m.merchant?.toString().orEmpty(), listOf(none) + columns,
        { id -> viewModel.onMappingChange { it.copy(merchant = id.toIntOrNull()) } })
    SelectionField(stringResource(R.string.import_field_category), m.category?.toString().orEmpty(), listOf(none) + columns,
        { id -> viewModel.onMappingChange { it.copy(category = id.toIntOrNull()) } },
        supportingText = stringResource(R.string.import_category_note))
    HorizontalDivider()
    SelectionField(stringResource(R.string.import_field_wallet), m.walletId, choices.wallets,
        { id -> viewModel.onMappingChange { it.copy(walletId = id) } }, errorRes = errors.wallet)
    SelectionField(stringResource(R.string.import_field_fallback_expense), m.fallbackExpenseCategoryId, choices.expenseCategories,
        { id -> viewModel.onMappingChange { it.copy(fallbackExpenseCategoryId = id) } }, errorRes = errors.fallbackExpense)
    SelectionField(stringResource(R.string.import_field_fallback_income), m.fallbackIncomeCategoryId, choices.incomeCategories,
        { id -> viewModel.onMappingChange { it.copy(fallbackIncomeCategoryId = id) } }, errorRes = errors.fallbackIncome)
    NoteText(stringResource(R.string.import_review_note))
    Button(onClick = viewModel::review, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.import_action_review))
    }
    OutlinedButton(onClick = viewModel::restart, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.import_action_other_file))
    }
}

@Composable
private fun Preview(table: CsvTable) {
    Text(stringResource(R.string.import_preview_title), style = MaterialTheme.typography.labelLarge)
    table.rows.take(3).forEach { row ->
        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.small)) {
            Text(stringResource(R.string.import_row_line, row.line), style = MaterialTheme.typography.labelMedium)
            table.header.forEachIndexed { index, name ->
                NoteText("$name: ${row.cells.getOrNull(index).orEmpty()}")
            }
        }
    }
}

@Composable
private fun dateFormatLabel(format: ImportDateFormat): String = when (format) {
    ImportDateFormat.DAY_MONTH_YEAR_SLASH -> stringResource(R.string.import_date_format_dmy_slash)
    ImportDateFormat.DAY_MONTH_YEAR_DASH -> stringResource(R.string.import_date_format_dmy_dash)
    ImportDateFormat.ISO -> stringResource(R.string.import_date_format_iso)
}

@Composable
private fun ReviewStage(state: ImportUiState, viewModel: ImportViewModel, onImport: () -> Unit) {
    val plan = state.plan ?: return
    val toImport = state.rowsToImport
    Text(stringResource(R.string.import_review_title), style = MaterialTheme.typography.titleMedium)
    Text(pluralStringResource(R.plurals.import_summary_ready, plan.ready.size, plan.ready.size))
    Text(pluralStringResource(R.plurals.import_summary_duplicates, plan.duplicates.size, plan.duplicates.size))
    Text(pluralStringResource(R.plurals.import_summary_failed, plan.failed.size, plan.failed.size))
    val income = toImport.filter { it.draft.type == TransactionType.INCOME }.sumOf { it.draft.amount }
    val expense = toImport.filter { it.draft.type == TransactionType.EXPENSE }.sumOf { it.draft.amount }
    NoteText(stringResource(R.string.import_summary_totals, formatRupiah(income), formatRupiah(expense)))

    if (state.possibleDuplicateCount > 0) {
        Row(
            Modifier.fillMaxWidth().toggleable(state.includePossibleDuplicates, role = Role.Switch) {
                viewModel.onIncludePossibleDuplicatesChange(it)
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.field),
        ) {
            Text(
                pluralStringResource(R.plurals.import_include_possible, state.possibleDuplicateCount, state.possibleDuplicateCount),
                Modifier.weight(1f),
            )
            Switch(checked = state.includePossibleDuplicates, onCheckedChange = null)
        }
    }
    Button(onClick = onImport, enabled = toImport.isNotEmpty() && !state.isWorking, modifier = Modifier.fillMaxWidth()) {
        Text(pluralStringResource(R.plurals.import_action_import_count, toImport.size, toImport.size))
    }
    OutlinedButton(onClick = viewModel::backToMapping, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.import_action_back_to_mapping))
    }
    ProblemRows(plan)
}

@Composable
private fun ProblemRows(plan: ImportPlan) {
    if (plan.failed.isNotEmpty()) {
        HorizontalDivider()
        Text(stringResource(R.string.import_failed_title), style = MaterialTheme.typography.titleSmall)
        plan.failed.take(MAX_LISTED_ROWS).forEach { row ->
            Text(
                stringResource(R.string.import_row_line, row.line) + ": " + row.problems.map { problemLabel(it) }.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        MoreRows(plan.failed.size)
    }
    if (plan.duplicates.isNotEmpty()) {
        HorizontalDivider()
        Text(stringResource(R.string.import_duplicates_title), style = MaterialTheme.typography.titleSmall)
        plan.duplicates.take(MAX_LISTED_ROWS).forEach { row ->
            Column {
                Text(stringResource(R.string.import_row_line, row.line) + ": " + duplicateLabel(row.kind), style = MaterialTheme.typography.bodyMedium)
                NoteText(draftSummary(row.draft))
            }
        }
        MoreRows(plan.duplicates.size)
    }
}

@Composable
private fun MoreRows(total: Int) {
    if (total > MAX_LISTED_ROWS) NoteText(pluralStringResource(R.plurals.import_more_rows, total - MAX_LISTED_ROWS, total - MAX_LISTED_ROWS))
}

@Composable
private fun draftSummary(draft: TransactionDraft): String {
    val sign = if (draft.type == TransactionType.EXPENSE) "-" else "+"
    val text = listOfNotNull(draft.description, draft.merchant).joinToString(" · ")
    return "${JakartaTime.formatDate(JakartaTime.localDate(draft.transactionDate))} · $sign${formatRupiah(draft.amount)}" +
        if (text.isEmpty()) "" else " · $text"
}

@Composable
private fun problemLabel(problem: RowProblem): String = stringResource(
    when (problem) {
        RowProblem.MISSING_DATE -> R.string.import_problem_missing_date
        RowProblem.INVALID_DATE -> R.string.import_problem_invalid_date
        RowProblem.FUTURE_DATE -> R.string.import_problem_future_date
        RowProblem.MISSING_AMOUNT -> R.string.import_problem_missing_amount
        RowProblem.INVALID_AMOUNT -> R.string.import_problem_invalid_amount
        RowProblem.FRACTIONAL_AMOUNT -> R.string.import_problem_fractional_amount
        RowProblem.ZERO_AMOUNT -> R.string.import_problem_zero_amount
        RowProblem.TOO_LARGE -> R.string.import_problem_too_large
    },
)

@Composable
private fun duplicateLabel(kind: DuplicateKind): String = stringResource(
    when (kind) {
        DuplicateKind.ALREADY_IMPORTED -> R.string.import_duplicate_already_imported
        DuplicateKind.REPEATED_IN_FILE -> R.string.import_duplicate_repeated
        DuplicateKind.POSSIBLE_EXISTING -> R.string.import_duplicate_possible
    },
)

@Composable
private fun DoneStage(count: Int, onDone: () -> Unit) {
    Text(pluralStringResource(R.plurals.import_done, count, count), style = MaterialTheme.typography.titleMedium)
    NoteText(stringResource(R.string.import_done_note))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_action_finish)) }
}
