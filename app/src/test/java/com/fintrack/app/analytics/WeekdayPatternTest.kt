package com.fintrack.app.analytics

import com.fintrack.app.analytics.insights.InsightRules
import com.fintrack.app.analytics.insights.Reason
import com.fintrack.app.analytics.insights.RuleOutcome
import com.fintrack.app.analytics.metrics.WeekdayPattern
import com.fintrack.app.analytics.metrics.WeekdayTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.LocalDate

class WeekdayPatternTest {

    private fun d(text: String) = LocalDate.parse(text)

    @Test
    fun `weekday occurrences for 28, 30 and 31 day months`() {
        // February 2026 starts on Sunday: exactly 4 of each weekday.
        assertTrue(WeekdayPattern.occurrences(d("2026-02-01"), d("2026-02-28")).values.all { it == 4 })
        // November 2026 (30 days) starts on Sunday: Sunday and Monday occur 5 times.
        val november = WeekdayPattern.occurrences(d("2026-11-01"), d("2026-11-30"))
        assertEquals(setOf(SUNDAY, MONDAY), november.filterValues { it == 5 }.keys)
        assertEquals(30, november.values.sum())
        // October 2026 (31 days) starts on Thursday: Thursday, Friday, Saturday occur 5 times.
        assertEquals(setOf(THURSDAY, FRIDAY, SATURDAY), WeekdayPattern.occurrences(d("2026-10-01"), d("2026-10-31")).filterValues { it == 5 }.keys)
        // Brute-force check over many arbitrary spans.
        var first = d("2025-12-27")
        repeat(60) { length ->
            val last = first.plusDays(length.toLong())
            val expected = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.groupingBy { it.dayOfWeek }.eachCount()
            assertEquals(DayOfWeek.entries.associateWith { expected[it] ?: 0 }, WeekdayPattern.occurrences(first, last))
            first = first.plusDays(3)
        }
    }

    @Test
    fun `daily averages are normalized by occurrences`() {
        // November 2026: Monday occurs 5 times, Tuesday 4 times.
        val pattern = WeekdayPattern.of(
            listOf(WeekdayTotal(MONDAY, 500_000, 5), WeekdayTotal(DayOfWeek.TUESDAY, 400_000, 2), WeekdayTotal(SATURDAY, 800_000, 4)),
            d("2026-11-01"), d("2026-11-30"),
        )
        assertEquals(100_000.0, pattern.days.single { it.day == MONDAY }.dailyAverage!!, 1e-9)
        assertEquals(100_000.0, pattern.days.single { it.day == DayOfWeek.TUESDAY }.dailyAverage!!, 1e-9)
        assertEquals(200_000.0, pattern.days.single { it.day == DayOfWeek.TUESDAY }.averageTransaction!!, 1e-9)
        assertEquals(9, pattern.weekendDays) // 4 Saturdays + 5 Sundays in November 2026
        assertEquals(21, pattern.weekdayDays)
        assertEquals(800_000.0 / 9, pattern.weekendDailyAverage!!, 1e-9)
    }

    private fun pattern(weekend: Long, weekday: Long, count: Int, first: String = "2026-11-01", last: String = "2026-11-30") =
        WeekdayPattern.of(
            listOf(WeekdayTotal(SATURDAY, weekend, count / 2), WeekdayTotal(MONDAY, weekday, count - count / 2)),
            d(first), d(last),
        )

    @Test
    fun `I2 fires at a 1_5 ratio in either direction and not inside it`() {
        // Nov 2026: 9 weekend days, 21 weekdays. weekend avg / weekday avg = (w/9) / (d/21).
        val higher = InsightRules.weekendSpending(pattern(weekend = 900_000 * 3 / 2, weekday = 2_100_000, count = 20))
        assertTrue(higher is RuleOutcome.Fired)
        assertEquals(1.5, (higher as RuleOutcome.Fired).value.ratio, 1e-9)
        val lower = InsightRules.weekendSpending(pattern(weekend = 900_000, weekday = 2_100_000 * 3 / 2, count = 20))
        assertEquals(1 / 1.5, (lower as RuleOutcome.Fired).value.ratio, 1e-9)
        assertEquals(RuleOutcome.NoFinding, InsightRules.weekendSpending(pattern(weekend = 1_349_999, weekday = 2_100_000, count = 20)))
    }

    @Test
    fun `I2 minimum data`() {
        assertEquals(RuleOutcome.NotEnoughData(Reason.FEW_EXPENSES), InsightRules.weekendSpending(pattern(1_000_000, 100_000, count = 19)))
        assertEquals(
            RuleOutcome.NotEnoughData(Reason.SHORT_PERIOD),
            InsightRules.weekendSpending(pattern(1_000_000, 100_000, count = 40, first = "2026-11-01", last = "2026-11-10")),
        )
        assertEquals(RuleOutcome.NotEnoughData(Reason.ONE_SIDED_SPENDING), InsightRules.weekendSpending(pattern(1_000_000, 0, count = 20)))
    }
}
