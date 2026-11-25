package com.fintrack.app.core.common

import kotlinx.coroutines.test.TestCoroutineScheduler
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Base for test clocks: [withZone] must return a clock in the requested zone that shares the same
 * time source, otherwise `LocalDate.now(clock.withZone(...))` silently keeps UTC.
 */
abstract class TestClock(private val zone: ZoneId) : Clock() {
    abstract fun currentInstant(): Instant
    override fun getZone(): ZoneId = zone
    override fun instant(): Instant = currentInstant()
    override fun withZone(zone: ZoneId): Clock {
        val source = this
        return object : TestClock(zone) {
            override fun currentInstant(): Instant = source.currentInstant()
        }
    }
}

/** A clock the test moves explicitly (e.g. to simulate the user changing the device time). */
class MutableClock(var now: Instant) : TestClock(ZoneOffset.UTC) {
    override fun currentInstant(): Instant = now
}

/** A clock that follows virtual time, so `delay` and the clock advance together. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SchedulerClock(private val start: Instant, private val scheduler: TestCoroutineScheduler) : TestClock(ZoneOffset.UTC) {
    override fun currentInstant(): Instant = start.plusMillis(scheduler.currentTime)
}
