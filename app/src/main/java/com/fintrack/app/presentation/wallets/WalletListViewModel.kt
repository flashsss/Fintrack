package com.fintrack.app.presentation.wallets

import android.database.SQLException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintrack.app.core.util.formatRupiah
import com.fintrack.app.data.repository.WalletRepository
import com.fintrack.app.domain.model.WalletType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class WalletRow(
    val id: String,
    val name: String,
    val type: WalletType,
    /** From the SQL balance query; never computed in the UI. */
    val balanceText: String,
    val isNegative: Boolean,
)

data class WalletListState(
    val isLoading: Boolean = true,
    val active: List<WalletRow> = emptyList(),
    val archived: List<WalletRow> = emptyList(),
    val loadFailed: Boolean = false,
)

@HiltViewModel
class WalletListViewModel @Inject constructor(walletRepository: WalletRepository) : ViewModel() {

    val state: StateFlow<WalletListState> = combine(
        walletRepository.observeAllWallets(),
        walletRepository.observeBalances(),
    ) { wallets, balances ->
        val balanceById = balances.associate { it.walletId to it.balance }
        val rows = wallets.map { wallet ->
            val balance = balanceById.getValue(wallet.id)
            WalletRow(wallet.id, wallet.name, wallet.type, formatRupiah(balance), balance < 0) to wallet.isActive
        }
        WalletListState(
            isLoading = false,
            active = rows.filter { it.second }.map { it.first },
            archived = rows.filterNot { it.second }.map { it.first },
        )
    }
        .catch { error ->
            if (error !is SQLException) throw error
            emit(WalletListState(isLoading = false, loadFailed = true))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WalletListState())
}
