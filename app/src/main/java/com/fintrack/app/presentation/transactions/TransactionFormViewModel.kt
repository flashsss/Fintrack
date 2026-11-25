package com.fintrack.app.presentation.transactions

import android.database.SQLException
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintrack.app.R
import com.fintrack.app.core.util.AmountInput
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.data.repository.BudgetAlertChecker
import com.fintrack.app.data.repository.CategoryRepository
import com.fintrack.app.data.repository.TransactionRepository
import com.fintrack.app.data.repository.WalletRepository
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WriteResult
import com.fintrack.app.domain.validation.TransactionValidationError
import com.fintrack.app.presentation.common.SelectOption
import com.fintrack.app.presentation.common.errorRes
import com.fintrack.app.presentation.common.messageRes
import com.fintrack.app.presentation.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

data class TransactionFormErrors(
    @StringRes val amount: Int? = null,
    @StringRes val wallet: Int? = null,
    @StringRes val destination: Int? = null,
    @StringRes val category: Int? = null,
)

data class TransactionFormState(
    val isLoading: Boolean = true,
    val isEdit: Boolean = false,
    val notFound: Boolean = false,
    val type: TransactionType = TransactionType.EXPENSE,
    val amountText: String = "",
    val walletId: String? = null,
    val destinationWalletId: String? = null,
    val categoryId: String? = null,
    val timestamp: Instant = Instant.EPOCH,
    val description: String = "",
    val merchant: String = "",
    val errors: TransactionFormErrors = TransactionFormErrors(),
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    @StringRes val generalError: Int? = null,
) {
    val date: LocalDate get() = JakartaTime.localDate(timestamp)
}

data class TransactionFormChoices(
    val sourceWallets: List<SelectOption> = emptyList(),
    val destinationWallets: List<SelectOption> = emptyList(),
    val categories: List<SelectOption> = emptyList(),
)

@HiltViewModel
class TransactionFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
    walletRepository: WalletRepository,
    categoryRepository: CategoryRepository,
    private val budgetAlertChecker: BudgetAlertChecker,
    clock: Clock,
) : ViewModel() {

    private val transactionId: String? = savedStateHandle[Routes.ARG_TRANSACTION_ID]

    /** References of the transaction being edited; these may stay selected even if archived. */
    private data class KeptReferences(val walletId: String?, val destinationWalletId: String?, val categoryId: String?)

    private val kept = MutableStateFlow(KeptReferences(null, null, null))

    private val _state = MutableStateFlow(
        TransactionFormState(
            isEdit = transactionId != null,
            isLoading = transactionId != null,
            type = savedStateHandle.get<String>(Routes.ARG_TYPE)
                ?.let { name -> TransactionType.entries.firstOrNull { it.name == name } }
                ?: TransactionType.EXPENSE,
            timestamp = clock.instant(),
        ),
    )
    val state: StateFlow<TransactionFormState> = _state.asStateFlow()

    val choices: StateFlow<TransactionFormChoices> = combine(
        walletRepository.observeAllWallets(),
        categoryRepository.observeAllCategories(),
        _state.map { it.type }.distinctUntilChanged(),
        kept,
    ) { wallets, categories, type, keep ->
        TransactionFormChoices(
            sourceWallets = TransactionFormOptions.wallets(wallets, keep.walletId),
            destinationWallets = TransactionFormOptions.wallets(wallets, keep.destinationWalletId),
            categories = TransactionFormOptions.categories(categories, type, keep.categoryId),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionFormChoices())

    init {
        if (transactionId != null) load(transactionId)
    }

    private fun load(id: String) = viewModelScope.launch {
        val transaction = try {
            transactionRepository.getTransaction(id)
        } catch (error: SQLException) {
            _state.update { it.copy(isLoading = false, generalError = R.string.error_load_failed) }
            return@launch
        }
        if (transaction == null) {
            _state.update { it.copy(isLoading = false, notFound = true) }
            return@launch
        }
        kept.value = KeptReferences(transaction.walletId, transaction.destinationWalletId, transaction.categoryId)
        _state.update {
            it.copy(
                isLoading = false,
                type = transaction.type,
                amountText = AmountInput.textFor(transaction.amount),
                walletId = transaction.walletId,
                destinationWalletId = transaction.destinationWalletId,
                categoryId = transaction.categoryId,
                timestamp = transaction.transactionDate,
                description = transaction.description.orEmpty(),
                merchant = transaction.merchant.orEmpty(),
            )
        }
    }

    fun onTypeChange(type: TransactionType) = _state.update {
        if (it.type == type) {
            it
        } else {
            it.copy(
                type = type,
                // Categories are type-specific and transfers have none; a transfer-only field is cleared otherwise.
                categoryId = null,
                destinationWalletId = if (type == TransactionType.TRANSFER) it.destinationWalletId else null,
                errors = TransactionFormErrors(),
                generalError = null,
            )
        }
    }

    fun onAmountChange(text: String) =
        _state.update { it.copy(amountText = text, errors = it.errors.copy(amount = null), generalError = null) }

    fun onWalletChange(id: String) =
        _state.update { it.copy(walletId = id, errors = it.errors.copy(wallet = null, destination = null)) }

    fun onDestinationChange(id: String) =
        _state.update { it.copy(destinationWalletId = id, errors = it.errors.copy(destination = null)) }

    fun onCategoryChange(id: String) =
        _state.update { it.copy(categoryId = id, errors = it.errors.copy(category = null)) }

    fun onDateChange(date: LocalDate) =
        _state.update { it.copy(timestamp = JakartaTime.withDate(it.timestamp, date)) }

    fun onDescriptionChange(text: String) = _state.update { it.copy(description = text) }

    fun onMerchantChange(text: String) = _state.update { it.copy(merchant = text) }

    /**
     * Submits through the repository, which runs the transaction validator against the database.
     * An unparseable amount is submitted as 0, which the validator rejects, so nothing is written
     * and the parse message is shown instead of the generic one.
     */
    fun save() {
        val form = _state.value
        if (form.isLoading || form.isSaving || form.notFound || form.isSaved) return

        val parsed = AmountInput.parse(form.amountText)
        val draft = TransactionDraft(
            type = form.type,
            walletId = form.walletId.orEmpty(),
            destinationWalletId = form.destinationWalletId.takeIf { form.type == TransactionType.TRANSFER },
            categoryId = form.categoryId.takeIf { form.type != TransactionType.TRANSFER },
            amount = (parsed as? AmountInput.Result.Valid)?.amount ?: 0L,
            transactionDate = form.timestamp,
            description = form.description,
            merchant = form.merchant,
        )

        _state.update { it.copy(isSaving = true, generalError = null) }
        viewModelScope.launch {
            try {
                val result = if (transactionId != null) {
                    transactionRepository.updateTransaction(transactionId, draft)
                } else {
                    transactionRepository.addTransaction(draft)
                }
                if (result is WriteResult.Success && transactionId == null) checkBudgets(draft)
                _state.update { current ->
                    when (result) {
                        is WriteResult.Success -> current.copy(isSaving = false, isSaved = true)
                        is WriteResult.Invalid -> current.copy(
                            isSaving = false,
                            errors = result.errors.toFormErrors(amountParseError = parsed.errorRes()),
                        )
                        WriteResult.NotFound -> current.copy(isSaving = false, notFound = true)
                    }
                }
            } catch (error: SQLException) {
                _state.update { it.copy(isSaving = false, generalError = R.string.error_save_failed) }
            }
        }
    }

    /**
     * Budget alerts for a new expense, run before the form reports success so it completes first.
     * The transaction is already committed at this point: a database error while checking budgets
     * must not turn a successful save into "save failed", so it only skips the optional alert.
     */
    private suspend fun checkBudgets(draft: TransactionDraft) {
        try {
            budgetAlertChecker.onExpenseAdded(draft.type, draft.categoryId, draft.amount, draft.transactionDate)
        } catch (error: SQLException) {
            return
        }
    }

    private fun List<TransactionValidationError>.toFormErrors(@StringRes amountParseError: Int?): TransactionFormErrors {
        fun firstOf(vararg kinds: TransactionValidationError) = firstOrNull { it in kinds }?.messageRes
        return TransactionFormErrors(
            amount = amountParseError ?: firstOf(TransactionValidationError.AmountNotPositive),
            wallet = firstOf(
                TransactionValidationError.SourceWalletNotFound,
                TransactionValidationError.SourceWalletArchived,
            ),
            destination = firstOf(
                TransactionValidationError.DestinationWalletRequired,
                TransactionValidationError.DestinationWalletNotFound,
                TransactionValidationError.DestinationWalletArchived,
                TransactionValidationError.DestinationWalletNotAllowed,
                TransactionValidationError.SameSourceAndDestination,
            ),
            category = firstOf(
                TransactionValidationError.CategoryRequired,
                TransactionValidationError.CategoryNotFound,
                TransactionValidationError.CategoryNotAllowedForTransfer,
                TransactionValidationError.CategoryTypeMismatch,
                TransactionValidationError.CategoryArchived,
            ),
        )
    }
}
