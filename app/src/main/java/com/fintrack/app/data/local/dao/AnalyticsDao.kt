package com.fintrack.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Expense of one category on one day; [dayIndex] counts whole days from the range start. */
data class CategoryDayRow(val categoryId: String?, val dayIndex: Long, val total: Long, val count: Int)

/**
 * Aggregations for the Analisis tab. Same rules as [ReportDao]: half-open UTC range from Jakarta
 * midnights, `+deletedAt` to keep the date indexes, transfers never counted as expense, and only
 * SQL that SQLite 3.22 supports (verified by SqliteCompatibilityTest).
 */
@Dao
interface AnalyticsDao {

    /** Category id only; labels are joined in Kotlin from the categories flow so renames apply live. */
    @Query(AnalyticsSql.EXPENSE_BY_CATEGORY_AND_DAY)
    fun observeExpenseByCategoryAndDay(startMillis: Long, endMillis: Long, dayMillis: Long): Flow<List<CategoryDayRow>>
}

object AnalyticsSql {
    const val EXPENSE_BY_CATEGORY_AND_DAY = """
        SELECT categoryId,
            (transactionDate - :startMillis) / :dayMillis AS dayIndex,
            SUM(amount) AS total,
            COUNT(*) AS count
        FROM transactions
        WHERE +deletedAt IS NULL
          AND type = 'EXPENSE'
          AND transactionDate >= :startMillis AND transactionDate < :endMillis
        GROUP BY categoryId, dayIndex
        """
}
