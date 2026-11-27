package com.fintrack.app.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Starts sync while signed in: periodic work, one run at start, and a debounced run whenever the
 * number of pending rows changes (any local create, edit, archive or delete). Repositories stay
 * unaware of sync.
 */
class SyncTrigger(
    private val authState: Flow<AuthState>,
    private val pendingCount: Flow<Int>,
    private val scheduler: SyncScheduler,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            var startedFor: String? = null
            combine(authState, pendingCount) { state, pending -> state to pending }
                .distinctUntilChanged()
                .collectLatest { (state, pending) ->
                    if (state !is AuthState.SignedIn) {
                        startedFor = null
                        return@collectLatest
                    }
                    if (startedFor != state.userId) {
                        // App start or fresh sign-in: fetch other devices' changes right away.
                        startedFor = state.userId
                        scheduler.schedulePeriodic()
                        scheduler.syncNow()
                    } else if (pending > 0) {
                        scheduler.requestSoon()
                    }
                }
        }
    }
}
