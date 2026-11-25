package com.fintrack.app.core.common

import com.fintrack.app.core.util.JakartaTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * The current Asia/Jakarta calendar date, re-emitted whenever it may have changed.
 *
 * The date is always recomputed from [clock]; the triggers only decide *when* to recompute:
 * 1. collection start, which also covers returning to the foreground because screens collect
 *    through `WhileSubscribed` state flows and resubscribe;
 * 2. [timeChanges], the system time/date/time-zone change broadcasts;
 * 3. a best-effort delay until the next Jakarta midnight while the app stays open.
 *
 * Correctness never depends on (3): a delay postponed by Doze is corrected by (1) or (2).
 */
class TodayProvider(
    private val clock: Clock,
    timeChanges: Flow<Unit>,
    midnightTicks: Flow<Unit> = jakartaMidnightTicks(clock),
) {
    val today: Flow<LocalDate> =
        merge(flowOf(Unit), timeChanges, midnightTicks)
            .map { JakartaTime.today(clock) }
            .distinctUntilChanged()
}

/** Emits shortly after each Asia/Jakarta midnight while collected (best effort; may be delayed by Doze). */
fun jakartaMidnightTicks(clock: Clock): Flow<Unit> = flow {
    while (true) {
        val now = clock.instant()
        val nextMidnight = JakartaTime.today(clock).plusDays(1).atStartOfDay(JakartaTime.ZONE).toInstant()
        // +1 ms so the recomputed date is already the new day. The 1 s floor keeps a clock that
        // misreports time from turning this into a busy loop.
        delay((Duration.between(now, nextMidnight).toMillis() + 1).coerceAtLeast(MIN_TICK_DELAY_MS))
        emit(Unit)
    }
}

private const val MIN_TICK_DELAY_MS = 1_000L
