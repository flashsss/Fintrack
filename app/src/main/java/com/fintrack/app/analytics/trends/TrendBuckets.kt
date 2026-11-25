package com.fintrack.app.analytics.trends

import com.fintrack.app.analytics.metrics.DateSpan
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

enum class Granularity { DAILY, WEEKLY, MONTHLY }

/**
 * Income and expense of one calendar bucket (WIB). A bucket is partial when some of its calendar
 * days are outside the reported span (period start in mid-bucket, or days that have not happened
 * yet). Partial buckets are shown but must not be averaged with or compared to full buckets.
 */
data class TrendBucket(
    val first: LocalDate,
    val last: LocalDate,
    val daysInBucket: Int,
    val daysReported: Int,
    val income: Long,
    val expense: Long,
) {
    val isPartial: Boolean get() = daysReported < daysInBucket
    val netCashFlow: Long get() = income - expense
}

object TrendBuckets {

    /**
     * Groups the daily points of [reported] (one point per reported day, zero days included, as
     * produced by [DailySeries.fill]) into calendar buckets. Weeks are ISO weeks starting Monday.
     * Sums use exact Long arithmetic.
     */
    fun rollUp(daily: List<DailyPoint>, reported: DateSpan, granularity: Granularity): List<TrendBucket> {
        val points = daily.filter { !it.date.isBefore(reported.first) && !it.date.isAfter(reported.last) }
        return points.groupBy { bucketStart(it.date, granularity) }
            .toSortedMap()
            .map { (start, items) ->
                val end = bucketEnd(start, granularity)
                TrendBucket(
                    first = start,
                    last = end,
                    daysInBucket = (end.toEpochDay() - start.toEpochDay() + 1).toInt(),
                    daysReported = items.size,
                    income = items.fold(0L) { sum, p -> Math.addExact(sum, p.income) },
                    expense = items.fold(0L) { sum, p -> Math.addExact(sum, p.expense) },
                )
            }
    }

    fun bucketStart(date: LocalDate, granularity: Granularity): LocalDate = when (granularity) {
        Granularity.DAILY -> date
        Granularity.WEEKLY -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        Granularity.MONTHLY -> date.withDayOfMonth(1)
    }

    private fun bucketEnd(start: LocalDate, granularity: Granularity): LocalDate = when (granularity) {
        Granularity.DAILY -> start
        Granularity.WEEKLY -> start.plusDays(6)
        Granularity.MONTHLY -> YearMonth.from(start).atEndOfMonth()
    }

    /** Mean expense per full bucket; partial buckets are excluded. Null when there is no full bucket. */
    fun averageFullBucketExpense(buckets: List<TrendBucket>): Double? =
        buckets.filterNot { it.isPartial }.takeIf { it.isNotEmpty() }?.let { full -> full.sumOf { it.expense }.toDouble() / full.size }
}
