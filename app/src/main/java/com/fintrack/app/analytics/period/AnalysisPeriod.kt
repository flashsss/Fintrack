package com.fintrack.app.analytics.period

import com.fintrack.app.analytics.metrics.DateSpan
import java.time.LocalDate
import java.time.YearMonth

/** Period selected on the Analisis tab. Dates are Asia/Jakarta calendar days. */
sealed interface AnalysisPeriod {
    data object ThisMonth : AnalysisPeriod
    data object LastMonth : AnalysisPeriod

    /** The last [months] calendar months including the running one (e.g. 3 → Sep 1 .. today in November). */
    data class LastMonths(val months: Int) : AnalysisPeriod {
        init {
            require(months in ALLOWED_MONTHS) { "Unsupported month count $months" }
        }
    }

    data class Custom(val first: LocalDate, val last: LocalDate) : AnalysisPeriod {
        init {
            require(!last.isBefore(first)) { "last must not be before first" }
        }
    }

    fun resolve(today: LocalDate): ResolvedPeriod = when (this) {
        ThisMonth -> YearMonth.from(today).let { ResolvedPeriod(it.atDay(1), it.atEndOfMonth(), today) }
        LastMonth -> YearMonth.from(today).minusMonths(1).let { ResolvedPeriod(it.atDay(1), it.atEndOfMonth(), today) }
        is LastMonths -> YearMonth.from(today).let { ResolvedPeriod(it.minusMonths(months - 1L).atDay(1), it.atEndOfMonth(), today) }
        is Custom -> ResolvedPeriod(first, last, today)
    }

    /**
     * The fair previous span for the reported part of this period: the same elapsed days of the
     * previous month for month periods, otherwise the equally long span just before.
     */
    fun previousSpan(reported: DateSpan): DateSpan = when (this) {
        ThisMonth, LastMonth -> PeriodShift.previousMonth(reported)
        is LastMonths, is Custom -> PeriodShift.previousOfSameLength(reported)
    }

    companion object {
        val ALLOWED_MONTHS = setOf(3, 6, 12)

        /** Longest reported span analysed at once (also bounds query cost and memory). */
        const val MAX_DAYS = 366
    }
}
