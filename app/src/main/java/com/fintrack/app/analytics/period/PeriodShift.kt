package com.fintrack.app.analytics.period

import com.fintrack.app.analytics.metrics.DateSpan
import java.time.DayOfWeek
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * The previous span that is fair to compare with a given (possibly partial) span. All dates are
 * Asia/Jakarta calendar days, inclusive.
 */
object PeriodShift {

    /**
     * Same elapsed days of the previous calendar month: days 1..D vs 1..min(D, previous length).
     * [span] must start on the first day of a month and stay within that month.
     */
    fun previousMonth(span: DateSpan): DateSpan {
        require(span.first.dayOfMonth == 1 && YearMonth.from(span.first) == YearMonth.from(span.last)) {
            "Month span must start on day 1 and stay within one month: $span"
        }
        val previous = YearMonth.from(span.first).minusMonths(1)
        return DateSpan(previous.atDay(1), previous.atDay(minOf(span.last.dayOfMonth, previous.lengthOfMonth())))
    }

    /** Same weekdays of the previous ISO week (Monday start). [span] must start on a Monday within one week. */
    fun previousWeek(span: DateSpan): DateSpan {
        require(span.first.dayOfWeek == DayOfWeek.MONDAY && ChronoUnit.DAYS.between(span.first, span.last) in 0..6) {
            "Week span must start on Monday and stay within one week: $span"
        }
        return DateSpan(span.first.minusWeeks(1), span.last.minusWeeks(1))
    }

    /** The equally long span that ends the day before [span] starts. */
    fun previousOfSameLength(span: DateSpan): DateSpan {
        val length = ChronoUnit.DAYS.between(span.first, span.last) + 1
        return DateSpan(span.first.minusDays(length), span.first.minusDays(1))
    }
}
