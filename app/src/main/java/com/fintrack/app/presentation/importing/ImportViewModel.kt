package com.fintrack.app.presentation.importing

import android.database.SQLException
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintrack.app.R
import com.fintrack.app.core.common.DocumentTextReader
import com.fintrack.app.core.common.FileReadResult
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.data.repository.CategoryRepository
import com.fintrack.app.data.repository.ImportRepository
import com.fintrack.app.data.repository.ImportResult
import com.fintrack.app.data.repository.WalletRepository
import com.fintrack.app.domain.importing.CsvImport
import com.fintrack.app.domain.importing.CsvParseResult
import com.fintrack.app.domain.importing.CsvTable
import com.fintrack.app.domain.importing.DuplicateKind
import com.fintrack.app.domain.importing.ImportDateFormat
import com.fintrack.app.domain.importing.ImportMapping
import com.fintrack.app.domain.importing.ImportPlan
import com.fintrack.app.domain.importing.ImportPlanner
import com.fintrack.app.domain.importing.PlannedRow
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.presentation.common.SelectOption
import com.fintrack.app.presentation.common.categoryLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.Locale
import javax.inject.Inject

enum class ImportStage { PICK, MAP, REVIEW, DONE }

/** Column choices; null = not mapped (only allowed for optional columns). */
data class ColumnMapping(
    val date: Int? = null,
    val amount: Int? = null,
    val description: Int? = null,
    val merchant: Int? = null,
    val category: Int? = null,
    val dateFormat: ImportDateFormat = ImportDateFormat.DAY_MONTH_YEAR_SLASH,
    val walletId: String? = null,
    val fallbackIncomeCategoryId: String? = null,
    val fallbackExpenseCategoryId: String? = null,
)

data class MappingErrors(
    @StringRes val date: Int? = null,
    @StringRes val amount: Int? = null,
    @StringRes val wallet: Int? = null,
    @StringRes val fallbackIncome: Int? = null,
    @StringRes val fallbackExpense: Int? = null,
)

data class ImportUiState(
    val stage: ImportStage = ImportStage.PICK,
    val table: CsvTable? = null,
    val mapping: ColumnMapping = ColumnMapping(),
    val mappingErrors: MappingErrors = MappingErrors(),
    val plan: ImportPlan? = null,
    /** Rows flagged as possible duplicates (not already-imported ones) are imported too. */
    val includePossibleDuplicates: Boolean = false,
    val isWorking: Boolean = false,
    val importedCount: Int = 0,
    @StringRes val error: Int? = null,
    /** Argument for [error] when it names a file line. */
    val errorArg: Int? = null,
    /** Set when the file has more data rows than this limit (shown instead of [error]). */
    val rowLimitExceeded: Int? = null,
) {
    /** Rows that the import button would write. */
    val rowsToImport: List<PlannedRow.Ready>
        get() {
            val plan = plan ?: return emptyList()
            val extra = if (!includePossibleDuplicates) emptyList() else plan.duplicates
                .filter { it.kind != DuplicateKind.ALREADY_IMPORTED }
                .map { PlannedRow.Ready(it.line, it.draft, it.fingerprint) }
            return (plan.ready + extra).sortedBy { it.line }
        }

    val possibleDuplicateCount: Int get() = plan?.duplicates?.count { it.kind != DuplicateKind.ALREADY_IMPORTED } ?: 0
}

data class ImportChoices(
    val wallets: List<SelectOption> = emptyList(),
    val incomeCategories: List<SelectOption> = emptyList(),
    val expenseCategories: List<SelectOption> = emptyList(),
)

/**
 * CSV import: pick file → map columns → review (nothing written) → confirm → atomic write.
 * Every row goes through the same validator as manual entry inside [ImportRepository.import].
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    private val reader: DocumentTextReader,
    private val importRepository: ImportRepository,
    walletRepository: WalletRepository,
    private val categoryRepository: CategoryRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    val choices: StateFlow<ImportChoices> = combine(
        walletRepository.observeActiveWallets(),
        categoryRepository.observeAllCategories(),
    ) { wallets, categories ->
        val byId = categories.associateBy { it.id }
        fun options(type: CategoryType) = categories.filter { it.type == type && !it.isArchived }
            .map { SelectOption(it.id, categoryLabel(it, byId)) }.sortedBy { it.label.lowercase(Locale.ROOT) }
        ImportChoices(
            wallets = wallets.map { SelectOption(it.id, it.name) },
            incomeCategories = options(CategoryType.INCOME),
            expenseCategories = options(CategoryType.EXPENSE),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImportChoices())

    fun onFilePicked(uri: String) {
        _state.value = ImportUiState(isWorking = true)
        viewModelScope.launch {
            val failure: ImportUiState? = when (val read = reader.read(uri)) {
                FileReadResult.TooLarge -> ImportUiState(error = R.string.import_error_too_large)
                FileReadResult.Unreadable -> ImportUiState(error = R.string.import_error_unreadable)
                is FileReadResult.Text -> when (val parsed = CsvImport.parse(read.text)) {
                    CsvParseResult.Empty -> ImportUiState(error = R.string.import_error_empty)
                    is CsvParseResult.Malformed -> ImportUiState(error = R.string.import_error_malformed)
                    is CsvParseResult.TooManyRows -> ImportUiState(rowLimitExceeded = parsed.limit)
                    is CsvParseResult.Success -> {
                        _state.value = ImportUiState(
                            stage = ImportStage.MAP,
                            table = parsed.table,
                            mapping = guessColumns(parsed.table.header),
                        )
                        null
                    }
                }
            }
            if (failure != null) _state.value = failure
        }
    }

    fun onMappingChange(transform: (ColumnMapping) -> ColumnMapping) =
        _state.update { it.copy(mapping = transform(it.mapping), mappingErrors = MappingErrors(), error = null) }

    fun onIncludePossibleDuplicatesChange(include: Boolean) = _state.update { it.copy(includePossibleDuplicates = include) }

    fun backToMapping() = _state.update { it.copy(stage = ImportStage.MAP, plan = null, includePossibleDuplicates = false, error = null) }

    fun restart() {
        _state.value = ImportUiState()
    }

    /** Builds the plan without writing anything. */
    fun review() {
        val current = _state.value
        val table = current.table ?: return
        val m = current.mapping
        val errors = MappingErrors(
            date = R.string.import_error_column_required.takeIf { m.date == null },
            amount = R.string.import_error_column_required.takeIf { m.amount == null },
            wallet = R.string.error_wallet_required.takeIf { m.walletId == null },
            fallbackIncome = R.string.error_category_required.takeIf { m.fallbackIncomeCategoryId == null },
            fallbackExpense = R.string.error_category_required.takeIf { m.fallbackExpenseCategoryId == null },
        )
        if (errors != MappingErrors()) {
            _state.update { it.copy(mappingErrors = errors) }
            return
        }
        val mapping = ImportMapping(
            dateColumn = m.date!!, dateFormat = m.dateFormat, amountColumn = m.amount!!,
            descriptionColumn = m.description, merchantColumn = m.merchant, categoryColumn = m.category,
            walletId = m.walletId!!, fallbackIncomeCategoryId = m.fallbackIncomeCategoryId!!,
            fallbackExpenseCategoryId = m.fallbackExpenseCategoryId!!,
        )
        _state.update { it.copy(isWorking = true, error = null) }
        viewModelScope.launch {
            try {
                val categories = categoryRepository.observeAllCategories().first()
                val today = JakartaTime.today(clock)
                // First pass finds fingerprints and dates; the second compares them with stored data.
                val draft = ImportPlanner.plan(table, mapping, categories, today, emptySet(), emptySet())
                val candidates = draft.ready + draft.duplicates.map { PlannedRow.Ready(it.line, it.draft, it.fingerprint) }
                val days = candidates.map { JakartaTime.localDate(it.draft.transactionDate) }
                val stored = importRepository.storedFingerprints(mapping.walletId, candidates.map { it.fingerprint })
                val existing = if (days.isEmpty()) emptySet() else importRepository.existingKeys(mapping.walletId, days.min(), days.max())
                val plan = ImportPlanner.plan(table, mapping, categories, today, stored, existing)
                _state.update { it.copy(stage = ImportStage.REVIEW, plan = plan, isWorking = false) }
            } catch (error: SQLException) {
                _state.update { it.copy(isWorking = false, error = R.string.error_load_failed) }
            }
        }
    }

    /** Called only after the user confirms. Writes all rows or none. */
    fun confirmImport() {
        val rows = _state.value.rowsToImport
        if (rows.isEmpty() || _state.value.isWorking) return
        _state.update { it.copy(isWorking = true, error = null) }
        viewModelScope.launch {
            val result = try {
                importRepository.import(rows)
            } catch (error: SQLException) {
                null
            }
            _state.update {
                when (result) {
                    is ImportResult.Imported -> it.copy(stage = ImportStage.DONE, importedCount = result.count, isWorking = false)
                    is ImportResult.Rejected -> it.copy(isWorking = false, error = R.string.import_error_row_rejected, errorArg = result.line)
                    is ImportResult.Duplicate -> it.copy(isWorking = false, error = R.string.import_error_row_duplicate, errorArg = result.line)
                    null -> it.copy(isWorking = false, error = R.string.error_save_failed)
                }
            }
        }
    }

    companion object {
        /** Pre-selects columns whose header names are common in Indonesian and English exports. */
        fun guessColumns(header: List<String>): ColumnMapping {
            val names = header.map { it.trim().lowercase(Locale.ROOT) }
            fun find(vararg keys: String) = names.indexOfFirst { name -> keys.any { name.contains(it) } }.takeIf { it >= 0 }
            return ColumnMapping(
                date = find("tanggal", "tgl", "date"),
                amount = find("jumlah", "nominal", "amount", "mutasi"),
                description = find("keterangan", "deskripsi", "description", "catatan"),
                merchant = find("merchant", "toko", "penerima"),
                category = find("kategori", "category"),
            )
        }
    }
}
