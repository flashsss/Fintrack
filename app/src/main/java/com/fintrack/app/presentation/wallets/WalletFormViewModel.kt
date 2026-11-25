package com.fintrack.app.presentation.wallets

import android.database.SQLException
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintrack.app.R
import com.fintrack.app.core.util.AmountInput
import com.fintrack.app.data.repository.WalletRepository
import com.fintrack.app.data.repository.WalletValidationError
import com.fintrack.app.domain.model.WalletType
import com.fintrack.app.domain.model.WriteResult
import com.fintrack.app.presentation.common.errorRes
import com.fintrack.app.presentation.common.messageRes
import com.fintrack.app.presentation.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.absoluteValue

data class WalletFormState(
    val isLoading: Boolean = false,
    val isEdit: Boolean = false,
    val notFound: Boolean = false,
    val name: String = "",
    val type: WalletType = WalletType.CASH,
    val initialBalanceText: String = "",
    val isNegative: Boolean = false,
    @StringRes val nameError: Int? = null,
    @StringRes val balanceError: Int? = null,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    @StringRes val generalError: Int? = null,
)

@HiltViewModel
class WalletFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val walletRepository: WalletRepository,
) : ViewModel() {

    private val walletId: String? = savedStateHandle[Routes.ARG_WALLET_ID]

    private val _state = MutableStateFlow(WalletFormState(isEdit = walletId != null, isLoading = walletId != null))
    val state: StateFlow<WalletFormState> = _state.asStateFlow()

    init {
        if (walletId != null) load(walletId)
    }

    private fun load(id: String) = viewModelScope.launch {
        val wallet = try {
            walletRepository.getWallet(id)
        } catch (error: SQLException) {
            _state.update { it.copy(isLoading = false, generalError = R.string.error_load_failed) }
            return@launch
        }
        _state.update {
            if (wallet == null) {
                it.copy(isLoading = false, notFound = true)
            } else {
                it.copy(
                    isLoading = false,
                    name = wallet.name,
                    type = wallet.type,
                    initialBalanceText = AmountInput.textFor(wallet.initialBalance.absoluteValue),
                    isNegative = wallet.initialBalance < 0,
                )
            }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value, nameError = null) }

    fun onTypeChange(value: WalletType) = _state.update { it.copy(type = value) }

    fun onInitialBalanceChange(value: String) =
        _state.update { it.copy(initialBalanceText = value, balanceError = null) }

    fun onNegativeChange(value: Boolean) = _state.update { it.copy(isNegative = value) }

    fun save() {
        val form = _state.value
        if (form.isLoading || form.isSaving || form.isSaved || form.notFound) return

        // An empty initial balance means Rp0.
        val parsed = AmountInput.parse(form.initialBalanceText.ifBlank { "0" })
        if (parsed !is AmountInput.Result.Valid) {
            _state.update { it.copy(balanceError = parsed.errorRes()) }
            return
        }
        val initialBalance = if (form.isNegative) -parsed.amount else parsed.amount

        _state.update { it.copy(isSaving = true, generalError = null) }
        viewModelScope.launch {
            try {
                val result = if (walletId == null) {
                    walletRepository.createWallet(form.name, form.type, initialBalance)
                } else {
                    walletRepository.updateWallet(walletId, form.name, form.type, initialBalance)
                }
                _state.update { current ->
                    when (result) {
                        is WriteResult.Success -> current.copy(isSaving = false, isSaved = true)
                        is WriteResult.Invalid -> current.copy(
                            isSaving = false,
                            nameError = result.errors.firstOrNull { it == WalletValidationError.NameBlank }?.messageRes,
                        )
                        WriteResult.NotFound -> current.copy(isSaving = false, notFound = true)
                    }
                }
            } catch (error: SQLException) {
                _state.update { it.copy(isSaving = false, generalError = R.string.error_save_failed) }
            }
        }
    }
}
