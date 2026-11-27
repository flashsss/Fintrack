package com.fintrack.app.presentation.sync

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintrack.app.R
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.presentation.common.ConfirmDialog
import com.fintrack.app.presentation.common.FinTopBar
import com.fintrack.app.presentation.common.FormColumn
import com.fintrack.app.presentation.common.LoadingState
import com.fintrack.app.presentation.common.MessageState
import com.fintrack.app.presentation.common.NoteText
import com.fintrack.app.sync.AuthState
import com.fintrack.app.sync.SyncProblem

@Composable
fun SyncScreen(onBack: () -> Unit, viewModel: SyncViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    Scaffold(topBar = { FinTopBar(stringResource(R.string.sync_title), onBack = onBack) }) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when (val auth = state.auth) {
            AuthState.NotConfigured -> MessageState(stringResource(R.string.sync_not_configured), modifier)
            AuthState.Loading -> LoadingState(modifier)
            AuthState.SignedOut -> FormColumn(modifier.verticalScroll(rememberScrollState()).imePadding()) {
                SignInContent(form, viewModel)
            }
            is AuthState.SignedIn -> FormColumn(modifier.verticalScroll(rememberScrollState())) {
                SignedInContent(auth, state, viewModel)
            }
        }
    }
}

@Composable
private fun SignInContent(form: SignInForm, viewModel: SyncViewModel) {
    Text(stringResource(R.string.sync_intro), style = MaterialTheme.typography.bodyLarge)
    NoteText(stringResource(R.string.sync_sign_in_note))
    OutlinedTextField(
        value = form.email,
        onValueChange = viewModel::onEmailChange,
        label = { Text(stringResource(R.string.sync_field_email)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        isError = form.emailError != null,
        supportingText = form.emailError?.let { { Text(stringResource(it)) } },
        modifier = Modifier.fillMaxWidth(),
    )
    // The password lives only in this field until submitted; it is never part of ViewModel state.
    val password = rememberTextFieldState()
    OutlinedSecureTextField(
        state = password,
        label = { Text(stringResource(R.string.sync_field_password)) },
        isError = form.passwordError != null,
        supportingText = form.passwordError?.let { { Text(stringResource(it)) } },
        modifier = Modifier.fillMaxWidth(),
    )
    form.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    Button(onClick = { viewModel.signIn(password.text.toString()) }, enabled = !form.isSigningIn, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.sync_action_sign_in))
    }
}

@Composable
private fun SignedInContent(auth: AuthState.SignedIn, state: SyncUiState, viewModel: SyncViewModel) {
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.sync_signed_in_as, auth.email.orEmpty()), style = MaterialTheme.typography.titleMedium)
    val status = state.status
    Text(
        when {
            state.isSyncing -> stringResource(R.string.sync_status_running)
            status.lastSuccessAt != null -> stringResource(R.string.sync_status_last, JakartaTime.formatDateTime(status.lastSuccessAt))
            else -> stringResource(R.string.sync_status_never)
        },
    )
    Text(
        if (state.pendingChanges == 0) stringResource(R.string.sync_pending_none)
        else pluralStringResource(R.plurals.sync_pending, state.pendingChanges, state.pendingChanges),
    )
    status.problem?.let { problem ->
        Text(
            stringResource(
                when (problem) {
                    SyncProblem.OFFLINE -> R.string.sync_problem_offline
                    SyncProblem.SIGNED_OUT -> R.string.sync_problem_signed_out
                    SyncProblem.ACCOUNT_MISMATCH -> R.string.sync_problem_account_mismatch
                    SyncProblem.REJECTED -> R.string.sync_problem_rejected
                },
            ),
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (status.rejectedRows > 0) {
        Text(pluralStringResource(R.plurals.sync_rejected_rows, status.rejectedRows, status.rejectedRows), color = MaterialTheme.colorScheme.error)
    }
    NoteText(stringResource(R.string.sync_auto_note))
    Button(onClick = viewModel::syncNow, enabled = !state.isSyncing, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.sync_action_now))
    }
    OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.sync_action_sign_out))
    }
    if (confirmSignOut) {
        ConfirmDialog(
            title = stringResource(R.string.sync_sign_out_title),
            message = stringResource(R.string.sync_sign_out_message),
            confirmLabel = stringResource(R.string.sync_action_sign_out),
            onConfirm = {
                confirmSignOut = false
                viewModel.signOut()
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}
