package com.fintrack.app.presentation.sync

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintrack.app.R
import com.fintrack.app.data.local.dao.SyncDao
import com.fintrack.app.sync.AuthRepository
import com.fintrack.app.sync.AuthState
import com.fintrack.app.sync.SignInResult
import com.fintrack.app.sync.SyncScheduler
import com.fintrack.app.sync.SyncStateStore
import com.fintrack.app.sync.SyncStatusInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SyncUiState(
    val auth: AuthState = AuthState.Loading,
    val status: SyncStatusInfo = SyncStatusInfo(),
    val pendingChanges: Int = 0,
    val isSyncing: Boolean = false,
)

data class SignInForm(
    val email: String = "",
    val isSigningIn: Boolean = false,
    @StringRes val emailError: Int? = null,
    @StringRes val passwordError: Int? = null,
    @StringRes val error: Int? = null,
)

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val auth: AuthRepository,
    stateStore: SyncStateStore,
    syncDao: SyncDao,
    private val scheduler: SyncScheduler,
) : ViewModel() {

    val state: StateFlow<SyncUiState> = combine(auth.state, stateStore.status, syncDao.observePendingCount(), scheduler.isRunning) { a, s, p, r ->
        SyncUiState(a, s, p, r)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncUiState())

    private val _form = MutableStateFlow(SignInForm())
    val form: StateFlow<SignInForm> = _form.asStateFlow()

    fun onEmailChange(value: String) = _form.update { it.copy(email = value, emailError = null, error = null) }

    /** [password] comes straight from the field and is not stored. */
    fun signIn(password: String) {
        val current = _form.value
        if (current.isSigningIn) return
        val emailError = R.string.sync_error_email_required.takeIf { current.email.isBlank() }
        val passwordError = R.string.sync_error_password_required.takeIf { password.isEmpty() }
        if (emailError != null || passwordError != null) {
            _form.update { it.copy(emailError = emailError, passwordError = passwordError) }
            return
        }
        _form.update { it.copy(isSigningIn = true, emailError = null, passwordError = null, error = null) }
        viewModelScope.launch {
            val result = auth.signIn(current.email, password)
            _form.update {
                when (result) {
                    // Sync starts from SyncTrigger once the auth state is SignedIn.
                    SignInResult.SUCCESS -> SignInForm(email = it.email)
                    SignInResult.INVALID_CREDENTIALS -> it.copy(isSigningIn = false, error = R.string.sync_error_invalid_credentials)
                    SignInResult.NETWORK -> it.copy(isSigningIn = false, error = R.string.sync_error_network)
                    SignInResult.FAILED -> it.copy(isSigningIn = false, error = R.string.sync_error_sign_in_failed)
                }
            }
        }
    }

    fun syncNow() = scheduler.syncNow()

    /** Stops sync on this device. Local data stays. */
    fun signOut() {
        viewModelScope.launch {
            scheduler.cancelAll()
            auth.signOut()
        }
    }
}
