package com.fintrack.app.presentation.transactions

import android.database.SQLException
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintrack.app.R
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.core.util.formatRupiah
import com.fintrack.app.data.repository.CategoryRepository
import com.fintrack.app.data.repository.TransactionRepository
import com.fintrack.app.data.repository.WalletRepository
import com.fintrack.app.domain.model.TransactionSource
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WriteResult
import com.fintrack.app.presentation.common.categoryLabel
import com.fintrack.app.presentation.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A wallet or category reference as shown in history; archived references stay visible. */
data class ReferenceLabel(val name: String, val isArchived: Boolean)

data class TransactionDetail(
    val type: TransactionType,
    val amountText: String,
    val wallet: ReferenceLabel?,
    val destinationWallet: ReferenceLabel?,
    val category: ReferenceLabel?,
    val dateText: String,
    val description: String?,
    val merchant: String?,
    val source: TransactionSource,
)

data class TransactionDetailState(
    val isLoading: Boolean = true,
    val detail: TransactionDetail? = null,
    val loadFailed: Boolean = false,
)

@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
    walletRepository: WalletRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    private val transactionId: String = checkNotNull(savedStateHandle[Routes.ARG_TRANSACTION_ID])

    val state: StateFlow<TransactionDetailState> = combine(
        transactionRepository.observeTransaction(transactionId),
        walletRepository.observeAllWallets(),
        categoryRepository.observeAllCategories(),
    ) { transaction, wallets, categories ->
        val walletsById = wallets.associateBy { it.id }
        val categoriesById = categories.associateBy { it.id }
        TransactionDetailState(
            isLoading = false,
            detail = transaction?.let {
                TransactionDetail(
                    type = it.type,
                    amountText = formatRupiah(it.amount),
                    wallet = walletsById[it.walletId]?.let { w -> ReferenceLabel(w.name, !w.isActive) },
                    destinationWallet = it.destinationWalletId?.let(walletsById::get)
                        ?.let { w -> ReferenceLabel(w.name, !w.isActive) },
                    category = it.categoryId?.let(categoriesById::get)
                        ?.let { c -> ReferenceLabel(categoryLabel(c, categoriesById), c.isArchived) },
                    dateText = JakartaTime.formatDate(it.transactionDate),
                    description = it.description,
                    merchant = it.merchant,
                    source = it.source,
                )
            },
        )
    }
        .catch { error ->
            if (error !is SQLException) throw error
            emit(TransactionDetailState(isLoading = false, loadFailed = true))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionDetailState())

    data class DeleteState(val isDeleting: Boolean = false, val isDeleted: Boolean = false, @StringRes val error: Int? = null)

    private val _deleteState = MutableStateFlow(DeleteState())
    val deleteState: StateFlow<DeleteState> = _deleteState

    /** Soft delete through the repository; balances update because they are derived from rows. */
    fun delete() {
        if (_deleteState.value.isDeleting || _deleteState.value.isDeleted) return
        _deleteState.value = DeleteState(isDeleting = true)
        viewModelScope.launch {
            try {
                when (transactionRepository.deleteTransaction(transactionId)) {
                    is WriteResult.Success, WriteResult.NotFound -> _deleteState.value = DeleteState(isDeleted = true)
                    is WriteResult.Invalid -> _deleteState.update { DeleteState(error = R.string.error_save_failed) }
                }
            } catch (error: SQLException) {
                _deleteState.value = DeleteState(error = R.string.error_save_failed)
            }
        }
    }
}
