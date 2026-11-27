package com.fintrack.app.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SyncOutcome {
    data class Done(val report: SyncReport) : SyncOutcome
    data object NotConfigured : SyncOutcome
    /** Not signed in or the session could not be refreshed. */
    data object SignedOut : SyncOutcome
    /** Signed in with a different account than the one this device's data belongs to. */
    data object AccountMismatch : SyncOutcome
    /** No connection or server unavailable; retry later. */
    data object Offline : SyncOutcome
    data class Rejected(val message: String) : SyncOutcome
}

/**
 * Runs [SyncEngine] for the signed-in user and records the result. The first account that syncs
 * is bound to this device; another account is refused so two people's data never mix (design §5).
 */
@Singleton
class SyncRunner @Inject constructor(
    private val config: SupabaseConfig,
    private val session: SessionUser,
    private val engine: SyncEngine,
    private val state: SyncStateStore,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    suspend fun run(): SyncOutcome = mutex.withLock {
        if (!config.isConfigured) return@withLock SyncOutcome.NotConfigured
        val userId = session.currentUserId() ?: return@withLock SyncOutcome.SignedOut.also { state.recordProblem(SyncProblem.SIGNED_OUT) }
        val bound = state.boundUserId()
        if (bound != null && bound != userId) {
            state.recordProblem(SyncProblem.ACCOUNT_MISMATCH)
            return@withLock SyncOutcome.AccountMismatch
        }
        if (bound == null) state.bindUser(userId)
        try {
            val report = engine.run()
            state.recordSuccess(clock.instant(), report.pushRejected + report.pullRejected)
            SyncOutcome.Done(report)
        } catch (error: RemoteSyncException.Unavailable) {
            state.recordProblem(SyncProblem.OFFLINE)
            SyncOutcome.Offline
        } catch (error: RemoteSyncException.Unauthorized) {
            state.recordProblem(SyncProblem.SIGNED_OUT)
            SyncOutcome.SignedOut
        } catch (error: RemoteSyncException.Rejected) {
            state.recordProblem(SyncProblem.REJECTED)
            SyncOutcome.Rejected(error.message.orEmpty())
        }
    }
}
