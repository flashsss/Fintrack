package com.fintrack.app.data.repository

import com.fintrack.app.analytics.metrics.CategoryExpense
import com.fintrack.app.analytics.metrics.PeriodTotals
import com.fintrack.app.analytics.trends.DailyPoint
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.data.local.dao.ReportDao
import com.fintrack.app.data.local.entity.toDomain
import com.fintrack.app.domain.model.DateRange
import com.fintrack.app.domain.model.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Dashboard aggregations. All sums are computed by SQL; see [ReportDao]. */
@Singleton
class ReportRepository @Inject constructor(private val reportDao: ReportDao) {

    fun observePeriodTotals(range: DateRange): Flow<PeriodTotals> =
        reportDao.observePeriodTotals(range.start.toEpochMilli(), range.endExclusive.toEpochMilli())
            .map { PeriodTotals(it.income, it.expense, it.transactionCount, it.transferCount) }

    /**
     * Income and expense per Asia/Jakarta day for [first]..[last]; only days with data are returned.
     * The SQL groups by fixed 24-hour steps from the first midnight, which is exact only if no day in
     * the range has a different length. Asia/Jakarta has had no offset change since 1964; the check
     * below fails loudly instead of returning shifted days if that ever stops being true.
     */
    fun observeDailyTotals(first: LocalDate, last: LocalDate): Flow<Map<LocalDate, DailyPoint>> {
        val range = JakartaTime.range(first, last)
        val days = last.toEpochDay() - first.toEpochDay() + 1
        check(range.endExclusive.toEpochMilli() - range.start.toEpochMilli() == days * DAY_MILLIS) {
            "Daily grouping requires 24-hour days in ${JakartaTime.ZONE} between $first and $last"
        }
        return reportDao.observeDailyTotals(range.start.toEpochMilli(), range.endExclusive.toEpochMilli(), DAY_MILLIS)
            .map { rows ->
                rows.associate { row ->
                    val date = first.plusDays(row.dayIndex)
                    date to DailyPoint(date, row.income, row.expense)
                }
            }
    }

    fun observeExpenseByCategory(range: DateRange): Flow<List<CategoryExpense>> =
        reportDao.observeExpenseByCategory(range.start.toEpochMilli(), range.endExclusive.toEpochMilli())
            .map { rows -> rows.map { CategoryExpense(it.categoryId, it.total) } }

    fun observeRecentTransactions(limit: Int): Flow<List<Transaction>> =
        reportDao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    fun observeHasTransactions(): Flow<Boolean> = reportDao.observeHasTransactions()

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
