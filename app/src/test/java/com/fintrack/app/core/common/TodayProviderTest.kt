package com.fintrack.app.core.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class TodayProviderTest {

    private fun d(text: String) = LocalDate.parse(text)

    @Test
    fun `first value is the Jakarta date, not the UTC date`() = runTest {
        // 17:30 UTC on 25 Nov is 00:30 WIB on 26 Nov.
        val provider = TodayProvider(MutableClock(Instant.parse("2026-11-25T17:30:00Z")), emptyFlow(), emptyFlow())
        assertEquals(d("2026-11-26"), provider.today.first())
    }

    @Test
    fun `midnight tick moves to the next day exactly after Jakarta midnight`() = runTest {
        // 23:59:00 WIB on 25 Nov.
        val clock = SchedulerClock(Instant.parse("2026-11-25T16:59:00Z"), testScheduler)
        val provider = TodayProvider(clock, timeChanges = emptyFlow(), midnightTicks = jakartaMidnightTicks(clock))
        val seen = mutableListOf<LocalDate>()
        backgroundScope.launch { provider.today.collect { seen += it } }

        runCurrent()
        assertEquals(listOf(d("2026-11-25")), seen)

        testScheduler.advanceTimeBy(59_000) // 23:59:59 WIB
        runCurrent()
        assertEquals(listOf(d("2026-11-25")), seen)

        testScheduler.advanceTimeBy(2_000) // 00:00:01 WIB
        runCurrent()
        assertEquals(listOf(d("2026-11-25"), d("2026-11-26")), seen)

        testScheduler.advanceTimeBy(24 * 3_600_000L)
        runCurrent()
        assertEquals(listOf(d("2026-11-25"), d("2026-11-26"), d("2026-11-27")), seen)
    }

    @Test
    fun `system time change recomputes the date without waiting for a timer`() = runTest {
        val clock = MutableClock(Instant.parse("2026-11-25T03:00:00Z"))
        val changes = MutableSharedFlow<Unit>()
        val provider = TodayProvider(clock, timeChanges = changes, midnightTicks = emptyFlow())
        val seen = mutableListOf<LocalDate>()
        backgroundScope.launch { provider.today.collect { seen += it } }
        runCurrent()

        clock.now = Instant.parse("2026-12-24T03:00:00Z") // user moves the device clock
        assertEquals("no trigger yet, no new value", listOf(d("2026-11-25")), seen)
        changes.emit(Unit)
        runCurrent()
        assertEquals(listOf(d("2026-11-25"), d("2026-12-24")), seen)
    }

    @Test
    fun `triggers on the same date do not emit duplicates`() = runTest {
        val clock = MutableClock(Instant.parse("2026-11-25T03:00:00Z"))
        val changes = MutableSharedFlow<Unit>()
        val provider = TodayProvider(clock, timeChanges = changes, midnightTicks = emptyFlow())
        val seen = mutableListOf<LocalDate>()
        backgroundScope.launch { provider.today.collect { seen += it } }
        runCurrent()

        clock.now = Instant.parse("2026-11-25T15:00:00Z") // 22:00 WIB, same day
        repeat(3) { changes.emit(Unit) }
        runCurrent()
        assertEquals(listOf(d("2026-11-25")), seen)
    }

    @Test
    fun `resubscribing (returning to the foreground) recomputes from the clock`() = runTest {
        val clock = MutableClock(Instant.parse("2026-11-25T03:00:00Z"))
        val provider = TodayProvider(clock, timeChanges = emptyFlow(), midnightTicks = emptyFlow())
        assertEquals(d("2026-11-25"), provider.today.first())

        // The app was in the background over midnight and no trigger was delivered.
        clock.now = Instant.parse("2026-11-26T02:00:00Z")
        assertEquals(d("2026-11-26"), provider.today.first())
    }
}
