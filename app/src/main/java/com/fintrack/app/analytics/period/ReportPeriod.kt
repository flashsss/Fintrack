package com.fintrack.app.analytics.period

import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.domain.model.DateRange
import java.time.LocalDate
import java.time.YearMonth

/** Reporting period chosen on the dashboard. Dates are Asia/Jakarta calendar days. */
sealed interface ReportPeriod {
    data object ThisMonth : ReportPeriod
    data object LastMonth : ReportPeriod
    data class Custom(val first: LocalDate, val last: LocalDate) : ReportPeriod {
        init {
            require(!last.isBefore(first)) { "last must not be before first" }
        }
    }

    fun resolve(today: LocalDate): ResolvedPeriod = when (this) {
        ThisMonth -> YearMonth.from(today).let { ResolvedPeriod(it.atDay(1), it.atEndOfMonth(), today) }
        LastMonth -> YearMonth.from(today).minusMonths(1).let { ResolvedPeriod(it.atDay(1), it.atEndOfMonth(), today) }
        is Custom -> ResolvedPeriod(first, last, today)
    }
}

/**
 * A period with its reporting cut-off. Days after [today] have not happened yet, so they are never
 * reported (not even as zero days): [reportedLast] is the earlier of [last] and [today].
 */
data class ResolvedPeriod(val first: LocalDate, val last: LocalDate, val today: LocalDate) {

    /** Null when the whole period is in the future. */
    val reportedLast: LocalDate? = minOf(last, today).takeUnless { it.isBefore(first) }

    val isPartial: Boolean get() = reportedLast != null && reportedLast.isBefore(last)

    /** UTC range covering [first]..[reportedLast]; null when nothing has happened yet. */
    val reportedRange: DateRange? get() = reportedLast?.let { JakartaTime.range(first, it) }

    /** Number of reported days. */
    val reportedDays: Int get() = reportedLast?.let { (it.toEpochDay() - first.toEpochDay() + 1).toInt() } ?: 0
}
