package com.fintrack.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Expense of one category on one day; [dayIndex] counts whole days from the range start. */
data class CategoryDayRow(val categoryId: String?, val dayIndex: Long, val total: Long, val count: Int)

/** Expense on one weekday (0 = Monday .. 6 = Sunday, Asia/Jakarta). */
data class WeekdayRow(val weekday: Int, val total: Long, val count: Int)

/** Expense per raw merchant text (null = no merchant). Normalization happens in Kotlin. */
data class MerchantRow(val merchant: String?, val total: Long, val count: Int)

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

    /**
     * Weekday relative to the range start (addendum B6): `(date - start)` is never negative, so the
     * integer division is safe before 1970, and no absolute UTC offset is assumed. [startWeekday] is
     * the Jakarta weekday of the first day (0 = Monday). Requires 24-hour days in the range.
     */
    @Query(AnalyticsSql.EXPENSE_BY_WEEKDAY)
    fun observeExpenseByWeekday(startMillis: Long, endMillis: Long, dayMillis: Long, startWeekday: Int): Flow<List<WeekdayRow>>

    @Query(AnalyticsSql.EXPENSE_BY_MERCHANT)
    fun observeExpenseByMerchant(startMillis: Long, endMillis: Long): Flow<List<MerchantRow>>

    /** Raw merchant texts seen in [startMillis, endMillis), used as the lookback for "new" merchants. */
    @Query(AnalyticsSql.MERCHANTS_SEEN)
    fun observeMerchantsSeen(startMillis: Long, endMillis: Long): Flow<List<String>>
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

    const val EXPENSE_BY_WEEKDAY = """
        SELECT ((transactionDate - :startMillis) / :dayMillis + :startWeekday) % 7 AS weekday,
            SUM(amount) AS total,
            COUNT(*) AS count
        FROM transactions
        WHERE +deletedAt IS NULL
          AND type = 'EXPENSE'
          AND transactionDate >= :startMillis AND transactionDate < :endMillis
        GROUP BY weekday
        """

    const val EXPENSE_BY_MERCHANT = """
        SELECT merchant, SUM(amount) AS total, COUNT(*) AS count
        FROM transactions
        WHERE +deletedAt IS NULL
          AND type = 'EXPENSE'
          AND transactionDate >= :startMillis AND transactionDate < :endMillis
        GROUP BY merchant
        """

    const val MERCHANTS_SEEN = """
        SELECT DISTINCT merchant
        FROM transactions
        WHERE +deletedAt IS NULL
          AND type = 'EXPENSE'
          AND merchant IS NOT NULL
          AND transactionDate >= :startMillis AND transactionDate < :endMillis
        """
}
