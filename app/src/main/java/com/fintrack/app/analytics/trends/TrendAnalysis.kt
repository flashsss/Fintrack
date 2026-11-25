package com.fintrack.app.analytics.trends

import com.fintrack.app.analytics.metrics.DateSpan
import com.fintrack.app.analytics.metrics.MetricChange
import com.fintrack.app.analytics.metrics.PeriodTotals

/**
 * Cash-flow trend of one reported span (docs/PHASE_4_TECHNICAL_DESIGN.md §5.1).
 * Transfers are never part of income or expense; buckets come from Jakarta daily totals.
 */
data class TrendReport(
    val reported: DateSpan,
    val reportedDays: Int,
    val granularity: Granularity,
    val buckets: List<TrendBucket>,
    val totals: PeriodTotals,
    val previous: DateSpan,
    val previousTotals: PeriodTotals,
) {
    /** Ē = expense / reported calendar days (zero days included). */
    val averageDailyExpense: Double? get() = if (reportedDays == 0) null else totals.expense.toDouble() / reportedDays

    val income: MetricChange get() = MetricChange(totals.income, previousTotals.income)
    val expense: MetricChange get() = MetricChange(totals.expense, previousTotals.expense)
    val netCashFlow: MetricChange get() = MetricChange(totals.netCashFlow, previousTotals.netCashFlow)

    /** Saving-rate change in percentage points; null unless both periods have income. */
    val savingRatePointChange: Double?
        get() {
            val current = totals.savingRatePercent ?: return null
            val before = previousTotals.savingRatePercent ?: return null
            return current - before
        }

    /** Mean expense per complete bucket; partial buckets are excluded. */
    val averageFullBucketExpense: Double? get() = TrendBuckets.averageFullBucketExpense(buckets)

    companion object {
        /** Daily bars stop being readable beyond about two months. */
        const val MAX_DAILY_DAYS = 62

        fun availableGranularities(reportedDays: Int): List<Granularity> =
            if (reportedDays <= MAX_DAILY_DAYS) Granularity.entries else listOf(Granularity.WEEKLY, Granularity.MONTHLY)
    }
}
