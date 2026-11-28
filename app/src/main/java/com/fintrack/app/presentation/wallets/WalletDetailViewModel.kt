package com.fintrack.app.presentation.wallets

import android.database.SQLException
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintrack.app.R
import com.fintrack.app.core.util.formatRupiah
import com.fintrack.app.data.repository.CategoryRepository
import com.fintrack.app.data.repository.TransactionRepository
import com.fintrack.app.data.repository.WalletRepository
import com.fintrack.app.data.repository.WalletValidationError
import com.fintrack.app.domain.model.Wallet
import com.fintrack.app.domain.model.WriteResult
import com.fintrack.app.presentation.navigation.Routes
import com.fintrack.app.presentation.transactions.TransactionDayGroup
import com.fintrack.app.presentation.transactions.TransactionRows
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WalletDetailState(
    val isLoading: Boolean = true,
    val wallet: Wallet? = null,
    val balanceText: String = "",
    val isNegative: Boolean = false,
    val history: List<TransactionDayGroup> = emptyList(),
    val loadFailed: Boolean = false,
)

@HiltViewModel
class WalletDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val walletRepository: WalletRepository,
    transactionRepository: TransactionRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    private val walletId: String = checkNotNull(savedStateHandle[Routes.ARG_WALLET_ID])

    val state: StateFlow<WalletDetailState> = combine(
        walletRepository.observeAllWallets(),
        walletRepository.observeBalances(),
        transactionRepository.observeWalletTransactions(walletId),
        categoryRepository.observeAllCategories(),
    ) { wallets, balances, transactions, categories ->
        val walletsById = wallets.associateBy { it.id }
        val balance = balances.firstOrNull { it.walletId == walletId }?.balance ?: 0L
        WalletDetailState(
            isLoading = false,
            wallet = walletsById[walletId],
            balanceText = formatRupiah(balance),
            isNegative = balance < 0,
            history = TransactionRows.group(
                transactions,
                walletsById,
                categories.associateBy { it.id },
                perspectiveWalletId = walletId,
            ),
        )
    }
        .catch { error ->
            if (error !is SQLException) throw error
            emit(WalletDetailState(isLoading = false, loadFailed = true))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WalletDetailState())

    /** String resource of the last archive, restore or delete failure, if any. */
    private val _archiveError = MutableStateFlow<Int?>(null)
    val archiveError: StateFlow<Int?> = _archiveError.asStateFlow()

    /** Set when deletion was refused because transactions still use the wallet. */
    private val _deleteBlockedBy = MutableStateFlow<Int?>(null)
    val deleteBlockedBy: StateFlow<Int?> = _deleteBlockedBy.asStateFlow()

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    fun archive() = write { walletRepository.archiveWallet(walletId) }

    fun unarchive() = write { walletRepository.unarchiveWallet(walletId) }

    fun delete() = write { walletRepository.deleteWallet(walletId).also { if (it is WriteResult.Success) _deleted.value = true } }

    fun dismissDeleteBlocked() {
        _deleteBlockedBy.value = null
    }

    private fun write(action: suspend () -> WriteResult<WalletValidationError>) = viewModelScope.launch {
        try {
            when (val result = action()) {
                is WriteResult.Success -> _archiveError.value = null
                WriteResult.NotFound -> _archiveError.value = R.string.error_not_found
                is WriteResult.Invalid -> {
                    val blocked = result.errors.filterIsInstance<WalletValidationError.HasTransactions>().firstOrNull()
                    if (blocked != null) _deleteBlockedBy.value = blocked.count else _archiveError.value = R.string.error_not_found
                }
            }
        } catch (error: SQLException) {
            _archiveError.value = R.string.error_save_failed
        }
    }
}
