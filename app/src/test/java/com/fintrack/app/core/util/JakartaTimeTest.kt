package com.fintrack.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class JakartaTimeTest {

    @Test
    fun `local date uses Jakarta, not UTC`() {
        // 2026-01-31T18:30Z is 2026-02-01 01:30 in Jakarta (UTC+7).
        assertEquals(LocalDate.of(2026, 2, 1), JakartaTime.localDate(Instant.parse("2026-01-31T18:30:00Z")))
        assertEquals(LocalDate.of(2026, 1, 31), JakartaTime.localDate(Instant.parse("2026-01-31T16:59:59Z")))
    }

    @Test
    fun `today follows Jakarta date`() {
        val clock = Clock.fixed(Instant.parse("2026-03-31T17:00:00Z"), ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 4, 1), JakartaTime.today(clock))
    }

    @Test
    fun `month range starts and ends at Jakarta midnight`() {
        val range = JakartaTime.month(YearMonth.of(2026, 2))
        assertEquals(Instant.parse("2026-01-31T17:00:00Z"), range.start)
        assertEquals(Instant.parse("2026-02-28T17:00:00Z"), range.endExclusive)
    }

    @Test
    fun `custom range includes the whole last day`() {
        val range = JakartaTime.range(LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 10))
        assertEquals(Instant.parse("2026-05-09T17:00:00Z"), range.start)
        assertEquals(Instant.parse("2026-05-10T17:00:00Z"), range.endExclusive)
    }

    @Test
    fun `withDate keeps Jakarta time of day`() {
        val original = Instant.parse("2026-01-10T02:15:00Z") // 09:15 WIB
        assertEquals(
            Instant.parse("2026-02-20T02:15:00Z"),
            JakartaTime.withDate(original, LocalDate.of(2026, 2, 20)),
        )
    }

    @Test
    fun `picker millis round-trip to the same calendar date`() {
        val date = LocalDate.of(2026, 12, 31)
        assertEquals(date, JakartaTime.pickerMillisToDate(JakartaTime.dateToPickerMillis(date)))
    }

    @Test
    fun `dates are formatted in Indonesian`() {
        assertEquals("5 Mei 2026", JakartaTime.formatDate(LocalDate.of(2026, 5, 5)))
        assertEquals("Selasa, 5 Mei 2026", JakartaTime.formatDayHeader(LocalDate.of(2026, 5, 5)))
    }
}
