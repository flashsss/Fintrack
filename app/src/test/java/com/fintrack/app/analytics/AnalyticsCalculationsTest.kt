package com.fintrack.app.analytics

import com.fintrack.app.analytics.metrics.BreakdownSlice
import com.fintrack.app.analytics.metrics.CategoryExpense
import com.fintrack.app.analytics.metrics.ComparisonSpans
import com.fintrack.app.analytics.metrics.DateSpan
import com.fintrack.app.analytics.metrics.ExpenseBreakdown
import com.fintrack.app.analytics.metrics.MetricChange
import com.fintrack.app.analytics.metrics.PercentChange
import com.fintrack.app.analytics.metrics.PeriodComparison
import com.fintrack.app.analytics.metrics.PeriodTotals
import com.fintrack.app.analytics.period.ReportPeriod
import com.fintrack.app.analytics.trends.DailyPoint
import com.fintrack.app.analytics.trends.DailySeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class AnalyticsCalculationsTest {

    private fun d(text: String) = LocalDate.parse(text)

    // Cash flow

    @Test
    fun `net cash flow and saving rate`() {
        val totals = PeriodTotals(income = 10_000_000, expense = 7_500_000, transactionCount = 5, transferCount = 1)
        assertEquals(2_500_000L, totals.netCashFlow)
        assertEquals(25.0, totals.savingRatePercent!!, 1e-9)
    }

    @Test
    fun `saving rate is unavailable without income`() {
        val totals = PeriodTotals(income = 0, expense = 500_000, transactionCount = 1, transferCount = 0)
        assertNull(totals.savingRatePercent)
        assertEquals(-500_000L, totals.netCashFlow)
        assertNull(PeriodTotals.EMPTY.savingRatePercent)
    }

    @Test
    fun `negative net cash flow gives negative saving rate`() {
        val totals = PeriodTotals(income = 4_000_000, expense = 5_000_000, transactionCount = 2, transferCount = 0)
        assertEquals(-1_000_000L, totals.netCashFlow)
        assertEquals(-25.0, totals.savingRatePercent!!, 1e-9)
    }

    // Periods

    @Test
    fun `this month is reported up to today only`() {
        val resolved = ReportPeriod.ThisMonth.resolve(d("2026-11-25"))
        assertEquals(d("2026-11-01"), resolved.first)
        assertEquals(d("2026-11-30"), resolved.last)
        assertEquals(d("2026-11-25"), resolved.reportedLast)
        assertTrue(resolved.isPartial)
        assertEquals(25, resolved.reportedDays)
        assertEquals(Instant.parse("2026-10-31T17:00:00Z"), resolved.reportedRange!!.start)
        assertEquals(Instant.parse("2026-11-25T17:00:00Z"), resolved.reportedRange!!.endExclusive)
    }

    @Test
    fun `last month is complete`() {
        val resolved = ReportPeriod.LastMonth.resolve(d("2026-03-10"))
        assertEquals(d("2026-02-01"), resolved.first)
        assertEquals(d("2026-02-28"), resolved.reportedLast)
        assertFalse(resolved.isPartial)
    }

    @Test
    fun `custom range is clipped at today and future range reports nothing`() {
        val today = d("2026-11-25")
        assertEquals(d("2026-11-25"), ReportPeriod.Custom(d("2026-11-20"), d("2026-12-10")).resolve(today).reportedLast)
        val future = ReportPeriod.Custom(d("2026-12-01"), d("2026-12-31")).resolve(today)
        assertNull(future.reportedLast)
        assertNull(future.reportedRange)
        assertEquals(0, future.reportedDays)
    }

    // Daily series

    @Test
    fun `daily series fills missing days with zero and stops at the last day`() {
        val points = DailySeries.fill(
            d("2026-11-01"), d("2026-11-04"),
            mapOf(d("2026-11-02") to DailyPoint(d("2026-11-02"), 100, 50)),
        )
        assertEquals(listOf(d("2026-11-01"), d("2026-11-02"), d("2026-11-03"), d("2026-11-04")), points.map { it.date })
        assertEquals(listOf(0L, 100L, 0L, 0L), points.map { it.income })
        assertEquals(listOf(0L, 50L, 0L, 0L), points.map { it.expense })
        assertTrue(DailySeries.fill(d("2026-11-05"), d("2026-11-04"), emptyMap()).isEmpty())
    }

    // Breakdown

    @Test
    fun `breakdown keeps top five and merges the rest into others`() {
        val expenses = listOf(
            CategoryExpense("a", 400), CategoryExpense("b", 300), CategoryExpense("c", 100),
            CategoryExpense("d", 80), CategoryExpense("e", 60), CategoryExpense("f", 40), CategoryExpense("g", 20),
        )
        val slices = ExpenseBreakdown.of(expenses)
        assertEquals(listOf("a", "b", "c", "d", "e", null), slices.map { it.categoryId })
        assertEquals(BreakdownSlice(null, 60, 6.0, isOthers = true, mergedCount = 2), slices.last())
        assertEquals(40.0, slices.first().percent, 1e-9)
        assertEquals(100.0, slices.sumOf { it.percent }, 1e-9)
        assertEquals(1_000L, slices.sumOf { it.total })
    }

    @Test
    fun `breakdown with five or fewer categories has no others slice`() {
        val slices = ExpenseBreakdown.of(listOf(CategoryExpense("a", 1), CategoryExpense("b", 3)))
        assertEquals(listOf("b", "a"), slices.map { it.categoryId })
        assertEquals(75.0, slices.first().percent, 1e-9)
        assertTrue(slices.none { it.isOthers })
    }

    @Test
    fun `uncategorized expense is kept as its own slice`() {
        val slices = ExpenseBreakdown.of(listOf(CategoryExpense(null, 50), CategoryExpense("a", 150)))
        assertEquals(listOf("a", null), slices.map { it.categoryId })
        assertFalse(slices.last().isOthers)
        assertEquals(25.0, slices.last().percent, 1e-9)
    }

    @Test
    fun `empty or zero expense gives no slices and no division by zero`() {
        assertTrue(ExpenseBreakdown.of(emptyList()).isEmpty())
        assertTrue(ExpenseBreakdown.of(listOf(CategoryExpense("a", 0))).isEmpty())
    }

    // Comparison

    @Test
    fun `partial current month is compared with the same elapsed days of last month`() {
        val spans = ComparisonSpans.forPeriod(ReportPeriod.ThisMonth, d("2026-11-25"))!!
        assertEquals(DateSpan(d("2026-11-01"), d("2026-11-25")), spans.current)
        assertEquals(DateSpan(d("2026-10-01"), d("2026-10-25")), spans.previous)
        assertEquals(DateSpan(d("2026-10-01"), d("2026-10-31")), spans.previousFullMonth)
    }

    @Test
    fun `elapsed days are clamped to a shorter previous month`() {
        val spans = ComparisonSpans.forPeriod(ReportPeriod.ThisMonth, d("2026-03-31"))!!
        assertEquals(DateSpan(d("2026-02-01"), d("2026-02-28")), spans.previous)
        assertNull("previous span already is the full month", spans.previousFullMonth)
    }

    @Test
    fun `last month compares full calendar months`() {
        val spans = ComparisonSpans.forPeriod(ReportPeriod.LastMonth, d("2026-01-15"))!!
        assertEquals(DateSpan(d("2025-12-01"), d("2025-12-31")), spans.current)
        assertEquals(DateSpan(d("2025-11-01"), d("2025-11-30")), spans.previous)
        assertNull(spans.previousFullMonth)
    }

    @Test
    fun `custom ranges have no monthly comparison`() {
        assertNull(ComparisonSpans.forPeriod(ReportPeriod.Custom(d("2026-11-01"), d("2026-11-10")), d("2026-11-25")))
    }

    @Test
    fun `percent change against positive, zero and negative baselines`() {
        assertEquals(PercentChange.Value(25.0), MetricChange(current = 1_250, previous = 1_000).percent)
        assertEquals(PercentChange.Value(-50.0), MetricChange(current = 500, previous = 1_000).percent)
        assertEquals(PercentChange.ZeroBaseline, MetricChange(current = 500, previous = 0).percent)
        assertEquals(PercentChange.ZeroBaseline, MetricChange(current = 0, previous = 0).percent)
        assertEquals(PercentChange.NegativeBaseline, MetricChange(current = 200, previous = -100).percent)
        assertEquals(300L, MetricChange(current = 200, previous = -100).absolute)
    }

    @Test
    fun `comparison derives net cash flow from both sides`() {
        val spans = ComparisonSpans.forPeriod(ReportPeriod.ThisMonth, d("2026-11-25"))!!
        val comparison = PeriodComparison.of(
            spans,
            current = PeriodTotals(5_000, 7_000, 3, 0),
            previous = PeriodTotals(5_000, 4_000, 2, 0),
            previousFull = PeriodTotals(6_000, 4_500, 4, 0),
        )
        assertEquals(MetricChange(-2_000, 1_000), comparison.netCashFlow)
        assertEquals(PercentChange.Value(-300.0), comparison.netCashFlow.percent)
        assertEquals(PercentChange.Value(75.0), comparison.expense.percent)
        assertEquals(0L, comparison.income.absolute)
    }
}
