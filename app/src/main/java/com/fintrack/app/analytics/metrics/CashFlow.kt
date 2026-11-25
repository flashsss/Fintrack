package com.fintrack.app.analytics.metrics

/**
 * Income and expense of one period. Transfers are never part of [income] or [expense].
 *
 * Formulas (docs/ENGINEERING_SPEC.md, Analytics rules):
 * - net cash flow = income − expense
 * - saving rate = (income − expense) / income × 100, unavailable when income is 0
 */
data class PeriodTotals(
    val income: Long,
    val expense: Long,
    val transactionCount: Int,
    val transferCount: Int,
) {
    val netCashFlow: Long get() = income - expense

    /** Percentage, or null when income is zero (the ratio is undefined). May be negative. */
    val savingRatePercent: Double?
        get() = if (income == 0L) null else (income - expense) * 100.0 / income

    companion object {
        val EMPTY = PeriodTotals(0, 0, 0, 0)
    }
}
