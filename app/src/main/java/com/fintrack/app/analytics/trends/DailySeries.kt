package com.fintrack.app.analytics.trends

import java.time.LocalDate

/** Income and expense of one Asia/Jakarta day. Transfers are excluded. */
data class DailyPoint(val date: LocalDate, val income: Long, val expense: Long)

object DailySeries {

    /**
     * One point per day from [first] to [last] inclusive. Days missing from [totals] had no income
     * or expense and become zero points. Callers pass the reported cut-off as [last], so future days
     * are never produced.
     */
    fun fill(first: LocalDate, last: LocalDate, totals: Map<LocalDate, DailyPoint>): List<DailyPoint> {
        if (last.isBefore(first)) return emptyList()
        return generateSequence(first) { it.plusDays(1) }
            .takeWhile { !it.isAfter(last) }
            .map { date -> totals[date] ?: DailyPoint(date, 0, 0) }
            .toList()
    }
}
