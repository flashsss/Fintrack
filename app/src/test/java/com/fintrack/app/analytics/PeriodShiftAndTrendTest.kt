package com.fintrack.app.analytics

import com.fintrack.app.analytics.metrics.DateSpan
import com.fintrack.app.analytics.period.PeriodShift
import com.fintrack.app.analytics.trends.DailyPoint
import com.fintrack.app.analytics.trends.DailySeries
import com.fintrack.app.analytics.trends.Granularity
import com.fintrack.app.analytics.trends.TrendBuckets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PeriodShiftAndTrendTest {

    private fun d(text: String) = LocalDate.parse(text)
    private fun span(a: String, b: String) = DateSpan(d(a), d(b))

    @Test
    fun `month shift keeps elapsed days and clamps to shorter months`() {
        assertEquals(span("2026-10-01", "2026-10-25"), PeriodShift.previousMonth(span("2026-11-01", "2026-11-25")))
        assertEquals(span("2026-02-01", "2026-02-28"), PeriodShift.previousMonth(span("2026-03-01", "2026-03-31")))
        assertEquals(span("2028-02-01", "2028-02-29"), PeriodShift.previousMonth(span("2028-03-01", "2028-03-30"))) // leap
        assertEquals(span("2025-12-01", "2025-12-15"), PeriodShift.previousMonth(span("2026-01-01", "2026-01-15")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `month shift rejects spans that do not start on day 1`() {
        PeriodShift.previousMonth(span("2026-11-02", "2026-11-25"))
    }

    @Test
    fun `week shift moves Monday-based partial weeks back seven days`() {
        // 23 Nov 2026 is a Monday.
        assertEquals(span("2026-11-16", "2026-11-18"), PeriodShift.previousWeek(span("2026-11-23", "2026-11-25")))
        // ISO week across the year boundary: Mon 29 Dec 2025 .. Sun 4 Jan 2026.
        assertEquals(span("2025-12-22", "2025-12-28"), PeriodShift.previousWeek(span("2025-12-29", "2026-01-04")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `week shift rejects spans not starting on Monday`() {
        PeriodShift.previousWeek(span("2026-11-24", "2026-11-25"))
    }

    @Test
    fun `custom shift uses the immediately preceding span of equal length`() {
        assertEquals(span("2026-10-27", "2026-11-09"), PeriodShift.previousOfSameLength(span("2026-11-10", "2026-11-23")))
        assertEquals(span("2026-02-28", "2026-02-28"), PeriodShift.previousOfSameLength(span("2026-03-01", "2026-03-01")))
    }

    private fun series(first: String, last: String, values: Map<String, Pair<Long, Long>>): List<DailyPoint> =
        DailySeries.fill(d(first), d(last), values.mapKeys { d(it.key) }.mapValues { (k, v) -> DailyPoint(k, v.first, v.second) })

    @Test
    fun `daily, weekly and monthly totals all equal the period total`() {
        val reported = span("2026-09-15", "2026-11-25")
        val daily = series(
            "2026-09-15", "2026-11-25",
            mapOf("2026-09-15" to (100L to 10L), "2026-09-30" to (0L to 7L), "2026-10-05" to (50L to 0L), "2026-11-25" to (0L to 3L)),
        )
        for (g in Granularity.entries) {
            val buckets = TrendBuckets.rollUp(daily, reported, g)
            assertEquals(g.name, 150L, buckets.sumOf { it.income })
            assertEquals(g.name, 20L, buckets.sumOf { it.expense })
            assertEquals(g.name, 72, buckets.sumOf { it.daysReported })
        }
    }

    @Test
    fun `monthly buckets mark the clipped first month and the running month as partial`() {
        val reported = span("2026-09-15", "2026-11-25")
        val buckets = TrendBuckets.rollUp(series("2026-09-15", "2026-11-25", emptyMap()), reported, Granularity.MONTHLY)
        assertEquals(listOf(d("2026-09-01"), d("2026-10-01"), d("2026-11-01")), buckets.map { it.first })
        assertEquals(listOf(16, 31, 25), buckets.map { it.daysReported })
        assertEquals(listOf(true, false, true), buckets.map { it.isPartial })
    }

    @Test
    fun `weekly buckets are ISO weeks and cross month and year boundaries`() {
        val reported = span("2025-12-29", "2026-01-11")
        val buckets = TrendBuckets.rollUp(series("2025-12-29", "2026-01-11", mapOf("2026-01-01" to (0L to 9L))), reported, Granularity.WEEKLY)
        assertEquals(listOf(d("2025-12-29"), d("2026-01-05")), buckets.map { it.first })
        assertEquals(listOf(d("2026-01-04"), d("2026-01-11")), buckets.map { it.last })
        assertEquals(listOf(9L, 0L), buckets.map { it.expense })
        assertTrue(buckets.none { it.isPartial })
    }

    @Test
    fun `days without transactions are zero days, not missing days`() {
        val buckets = TrendBuckets.rollUp(series("2026-11-01", "2026-11-07", emptyMap()), span("2026-11-01", "2026-11-07"), Granularity.DAILY)
        assertEquals(7, buckets.size)
        assertTrue(buckets.all { it.expense == 0L && !it.isPartial })
    }

    @Test
    fun `average over buckets excludes partial buckets`() {
        val reported = span("2026-09-15", "2026-11-25")
        val daily = series("2026-09-15", "2026-11-25", mapOf("2026-09-20" to (0L to 1_000L), "2026-10-10" to (0L to 300L), "2026-11-02" to (0L to 5_000L)))
        val buckets = TrendBuckets.rollUp(daily, reported, Granularity.MONTHLY)
        assertEquals(300.0, TrendBuckets.averageFullBucketExpense(buckets)!!, 1e-9) // only October is complete
        assertNull(TrendBuckets.averageFullBucketExpense(buckets.filter { it.isPartial }))
        assertFalse(buckets[1].isPartial)
    }
}
