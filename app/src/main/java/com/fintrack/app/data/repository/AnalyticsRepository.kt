package com.fintrack.app.data.repository

import com.fintrack.app.analytics.metrics.CategoryDayTotal
import com.fintrack.app.analytics.metrics.WeekdayTotal
import java.time.DayOfWeek
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.data.local.dao.AnalyticsDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Analisis aggregations. Sums are computed by SQL; see [AnalyticsDao]. */
@Singleton
class AnalyticsRepository @Inject constructor(private val analyticsDao: AnalyticsDao) {

    /** Expense per category id and Jakarta day for [first]..[last]; requires 24-hour days. */
    fun observeExpenseByCategoryAndDay(first: LocalDate, last: LocalDate): Flow<List<CategoryDayTotal>> {
        check(JakartaTime.hasUniformDays(first, last)) { "Daily grouping requires 24-hour days between $first and $last" }
        val range = JakartaTime.range(first, last)
        return analyticsDao.observeExpenseByCategoryAndDay(range.start.toEpochMilli(), range.endExclusive.toEpochMilli(), JakartaTime.DAY_MILLIS)
            .map { rows -> rows.map { CategoryDayTotal(it.categoryId, first.plusDays(it.dayIndex), it.total, it.count) } }
    }

    /** Expense per Jakarta weekday for [first]..[last]; requires 24-hour days. */
    fun observeExpenseByWeekday(first: LocalDate, last: LocalDate): Flow<List<WeekdayTotal>> {
        check(JakartaTime.hasUniformDays(first, last)) { "Weekday grouping requires 24-hour days between $first and $last" }
        val range = JakartaTime.range(first, last)
        return analyticsDao.observeExpenseByWeekday(
            range.start.toEpochMilli(), range.endExclusive.toEpochMilli(), JakartaTime.DAY_MILLIS, first.dayOfWeek.value - 1,
        ).map { rows -> rows.map { WeekdayTotal(DayOfWeek.of(it.weekday + 1), it.total, it.count) } }
    }
}
