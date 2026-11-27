package com.fintrack.app.sync

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthState {
    /** The build has no Supabase settings; sync is hidden. */
    data object NotConfigured : AuthState
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val userId: String, val email: String?) : AuthState
}

enum class SignInResult { SUCCESS, INVALID_CREDENTIALS, NETWORK, FAILED }

/** The signed-in account as the sync runner needs it. */
fun interface SessionUser {
    /** The user's id once the stored session has been loaded, or null when signed out. */
    suspend fun currentUserId(): String?
}

/** One-time email + password sign-in (design §5). Sessions are refreshed by supabase-kt. */
@Singleton
class AuthRepository @Inject constructor(private val access: SupabaseAccess) : SessionUser {

    val state: Flow<AuthState> = access.client?.auth?.sessionStatus?.map { status ->
        when (status) {
            is SessionStatus.Authenticated -> status.session.user?.let { AuthState.SignedIn(it.id, it.email) } ?: AuthState.Loading
            // A refresh that failed for lack of network keeps the session; sync reports offline.
            is SessionStatus.RefreshFailure -> currentUser() ?: AuthState.SignedOut
            is SessionStatus.Initializing -> AuthState.Loading
            is SessionStatus.NotAuthenticated -> AuthState.SignedOut
        }
    } ?: flowOf(AuthState.NotConfigured)

    private fun currentUser(): AuthState.SignedIn? =
        access.client?.auth?.currentUserOrNull()?.let { AuthState.SignedIn(it.id, it.email) }

    override suspend fun currentUserId(): String? {
        val auth = access.client?.auth ?: return null
        auth.awaitInitialization()
        return auth.currentUserOrNull()?.id
    }

    suspend fun signIn(email: String, password: String): SignInResult {
        val auth = access.client?.auth ?: return SignInResult.FAILED
        return try {
            auth.signInWith(Email) {
                this.email = email.trim()
                this.password = password
            }
            SignInResult.SUCCESS
        } catch (error: AuthRestException) {
            // Wrong email/password is a 400 with error code invalid_credentials.
            if (error.statusCode in 400..499) SignInResult.INVALID_CREDENTIALS else SignInResult.FAILED
        } catch (error: RestException) {
            SignInResult.FAILED
        } catch (error: IOException) {
            // HttpRequestException and Ktor timeouts are IOExceptions.
            SignInResult.NETWORK
        }
    }

    /** Ends the session on this device. Local data is kept. */
    suspend fun signOut() {
        val auth = access.client?.auth ?: return
        try {
            auth.signOut()
        } catch (error: RestException) {
            // The server could not revoke the token; the local session is still cleared.
            auth.clearSession()
        } catch (error: IOException) {
            auth.clearSession()
        }
    }
}
