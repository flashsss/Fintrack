package com.fintrack.app.analytics.metrics

import com.fintrack.app.analytics.period.PeriodShift
import com.fintrack.app.analytics.period.ReportPeriod
import java.time.LocalDate
import java.time.YearMonth

/** Inclusive Asia/Jakarta date span. */
data class DateSpan(val first: LocalDate, val last: LocalDate)

/**
 * Ranges for "this period vs previous calendar month".
 *
 * - This month (in progress): days 1..D of this month vs days 1..D of last month (clamped to its
 *   length), so both sides cover the same elapsed days. The full previous month is provided as
 *   [previousFullMonth] for reference only; it is never used for a percentage.
 * - Last month (complete): full month vs the full month before it.
 * - Custom ranges have no calendar-month counterpart, so no comparison is produced.
 */
data class ComparisonSpans(
    val current: DateSpan,
    val previous: DateSpan,
    val previousFullMonth: DateSpan?,
) {
    companion object {
        fun forPeriod(period: ReportPeriod, today: LocalDate): ComparisonSpans? = when (period) {
            ReportPeriod.ThisMonth -> {
                val month = YearMonth.from(today)
                val current = DateSpan(month.atDay(1), today)
                val previousSpan = PeriodShift.previousMonth(current)
                val previousMonth = month.minusMonths(1)
                val fullPrevious = DateSpan(previousMonth.atDay(1), previousMonth.atEndOfMonth())
                ComparisonSpans(
                    current = current,
                    previous = previousSpan,
                    previousFullMonth = fullPrevious.takeIf { it != previousSpan },
                )
            }
            ReportPeriod.LastMonth -> {
                val month = YearMonth.from(today).minusMonths(1)
                val before = month.minusMonths(1)
                ComparisonSpans(
                    current = DateSpan(month.atDay(1), month.atEndOfMonth()),
                    previous = DateSpan(before.atDay(1), before.atEndOfMonth()),
                    previousFullMonth = null,
                )
            }
            is ReportPeriod.Custom -> null
        }
    }
}

/** Percentage change. Only defined against a positive baseline. */
sealed interface PercentChange {
    data class Value(val percent: Double) : PercentChange
    /** Previous value was 0: any growth would be infinite, so no percentage is shown. */
    data object ZeroBaseline : PercentChange
    /** Previous value was negative (possible for net cash flow): a percentage would be misleading. */
    data object NegativeBaseline : PercentChange
}

data class MetricChange(val current: Long, val previous: Long) {
    val absolute: Long get() = current - previous

    val percent: PercentChange
        get() = when {
            previous == 0L -> PercentChange.ZeroBaseline
            previous < 0L -> PercentChange.NegativeBaseline
            else -> PercentChange.Value((current - previous) * 100.0 / previous)
        }
}

data class PeriodComparison(
    val spans: ComparisonSpans,
    val income: MetricChange,
    val expense: MetricChange,
    val netCashFlow: MetricChange,
    val previousFullMonth: PeriodTotals?,
) {
    companion object {
        fun of(spans: ComparisonSpans, current: PeriodTotals, previous: PeriodTotals, previousFull: PeriodTotals?) =
            PeriodComparison(
                spans = spans,
                income = MetricChange(current.income, previous.income),
                expense = MetricChange(current.expense, previous.expense),
                netCashFlow = MetricChange(current.netCashFlow, previous.netCashFlow),
                previousFullMonth = previousFull,
            )
    }
}
