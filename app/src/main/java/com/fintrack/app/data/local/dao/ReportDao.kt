package com.fintrack.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.fintrack.app.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

/** Sums for one reporting range. Transfers are counted in [transactionCount] only. */
data class PeriodTotalsRow(
    val income: Long,
    val expense: Long,
    val transactionCount: Int,
    val transferCount: Int,
)

/** [dayIndex] is the number of whole days between the range start and the transaction. */
data class DailyTotalRow(val dayIndex: Long, val income: Long, val expense: Long)

data class CategoryTotalRow(val categoryId: String?, val total: Long)

/**
 * Read-only aggregations for the dashboard. Every query takes a half-open UTC range
 * [startMillis, endMillis) whose bounds are Asia/Jakarta midnights computed by JakartaTime,
 * excludes soft-deleted rows, and never counts TRANSFER as income or expense.
 *
 * `+deletedAt` (unary plus) stops SQLite from choosing the deletedAt index: almost every row has
 * deletedAt NULL, so that index would scan the whole table. With it, the planner uses the
 * transactionDate / (type, transactionDate) indexes (verified with EXPLAIN QUERY PLAN).
 */
@Dao
interface ReportDao {

    @Query(ReportSql.PERIOD_TOTALS)
    fun observePeriodTotals(startMillis: Long, endMillis: Long): Flow<PeriodTotalsRow>

    /**
     * Groups by day offset from [startMillis]. Correct only when every day in the range lasts exactly
     * [dayMillis] (true for Asia/Jakarta, which has no DST); the repository verifies this.
     * Days without income or expense are absent from the result.
     */
    @Query(ReportSql.DAILY_TOTALS)
    fun observeDailyTotals(startMillis: Long, endMillis: Long, dayMillis: Long): Flow<List<DailyTotalRow>>

    /** Expense per category id (not name). A null id groups expenses without a category. */
    @Query(ReportSql.EXPENSE_BY_CATEGORY)
    fun observeExpenseByCategory(startMillis: Long, endMillis: Long): Flow<List<CategoryTotalRow>>

    @Query(ReportSql.RECENT)
    fun observeRecent(limit: Int): Flow<List<TransactionEntity>>

    @Query(ReportSql.HAS_TRANSACTIONS)
    fun observeHasTransactions(): Flow<Boolean>
}

/**
 * SQL of [ReportDao], kept as constants so tests can run the exact statements against SQLite 3.21
 * (the minSdk 29 device ships 3.22; no window functions, FILTER clauses or NULLS FIRST/LAST).
 */
object ReportSql {
    const val PERIOD_TOTALS = """
        SELECT
            COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amount END), 0) AS income,
            COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amount END), 0) AS expense,
            COUNT(*) AS transactionCount,
            COALESCE(SUM(CASE WHEN type = 'TRANSFER' THEN 1 ELSE 0 END), 0) AS transferCount
        FROM transactions
        WHERE +deletedAt IS NULL
          AND transactionDate >= :startMillis AND transactionDate < :endMillis
        """

    const val DAILY_TOTALS = """
        SELECT
            (transactionDate - :startMillis) / :dayMillis AS dayIndex,
            COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amount END), 0) AS income,
            COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amount END), 0) AS expense
        FROM transactions
        WHERE +deletedAt IS NULL
          AND type IN ('INCOME', 'EXPENSE')
          AND transactionDate >= :startMillis AND transactionDate < :endMillis
        GROUP BY dayIndex
        ORDER BY dayIndex
        """

    const val EXPENSE_BY_CATEGORY = """
        SELECT categoryId, SUM(amount) AS total
        FROM transactions
        WHERE +deletedAt IS NULL
          AND type = 'EXPENSE'
          AND transactionDate >= :startMillis AND transactionDate < :endMillis
        GROUP BY categoryId
        ORDER BY total DESC, categoryId
        """

    const val RECENT = """
        SELECT * FROM transactions
        WHERE +deletedAt IS NULL
        ORDER BY transactionDate DESC, createdAt DESC
        LIMIT :limit
        """

    const val HAS_TRANSACTIONS = "SELECT EXISTS(SELECT 1 FROM transactions WHERE deletedAt IS NULL)"
}
