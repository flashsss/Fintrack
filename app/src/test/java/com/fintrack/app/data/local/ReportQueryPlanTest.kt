package com.fintrack.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.local.dao.AnalyticsSql
import com.fintrack.app.data.local.dao.ImportSql
import com.fintrack.app.data.local.dao.ReportSql
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the dashboard queries against regressing into full-table scans, using ReportDao's own
 * SQL (ReportSql). Plans are read with EXPLAIN QUERY PLAN.
 */
@RunWith(AndroidJUnit4::class)
class ReportQueryPlanTest {

    private val t = TestDatabase()

    @After
    fun tearDown() = t.close()

    /** EXPLAIN QUERY PLAN of the DAO's own SQL with sample range arguments. */
    private fun plan(sql: String, vararg args: Any): String =
        t.db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql", args).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString(" | ")
        }

    @Test
    fun `period totals use the transaction date index`() {
        val plan = plan(ReportSql.PERIOD_TOTALS, 0L, 10L)
        assertTrue(plan, plan.contains("index_transactions_transactionDate"))
    }

    @Test
    fun `daily and category totals use the type and date index`() {
        val daily = plan(ReportSql.DAILY_TOTALS, 0L, 86_400_000L, 10L) // named params: start, dayMillis, end
        assertTrue(daily, daily.contains("index_transactions_type_transactionDate"))
        val category = plan(ReportSql.EXPENSE_BY_CATEGORY, 0L, 10L)
        assertTrue(category, category.contains("index_transactions_type_transactionDate"))
    }

    @Test
    fun `recent transactions read the date index instead of sorting the table`() {
        val plan = plan(ReportSql.RECENT, 5)
        assertTrue(plan, plan.contains("index_transactions_transactionDate"))
    }

    @Test
    fun `category by day uses the type and date index`() {
        val plan = plan(AnalyticsSql.EXPENSE_BY_CATEGORY_AND_DAY, 0L, 86_400_000L, 10L) // start, dayMillis, end
        assertTrue(plan, plan.contains("index_transactions_type_transactionDate"))
    }

    @Test
    fun `weekday totals use the type and date index`() {
        val plan = plan(AnalyticsSql.EXPENSE_BY_WEEKDAY, 0L, 86_400_000L, 3, 10L) // start, dayMillis, startWeekday, end
        assertTrue(plan, plan.contains("index_transactions_type_transactionDate"))
    }

    @Test
    fun `distribution stream evaluates the count once and uses the type and date index`() {
        val plan = plan(ExpenseDistributionReader.SQL, 0L, 10L)
        assertTrue(plan, plan.contains("index_transactions_type_transactionDate"))
        assertTrue("count must be an uncorrelated subquery: $plan", !plan.contains("CORRELATED"))
    }

    @Test
    fun `merchant queries use the type and date index`() {
        for (sql in listOf(AnalyticsSql.EXPENSE_BY_MERCHANT, AnalyticsSql.MERCHANTS_SEEN)) {
            val plan = plan(sql, 0L, 10L)
            assertTrue(plan, plan.contains("index_transactions_type_transactionDate"))
        }
    }

    @Test
    fun `import look-ups use an index instead of scanning transactions`() {
        val stored = plan(ImportSql.STORED_FINGERPRINTS.replace("(:externalIds)", "(?, ?)"), "w", "a", "b")
        assertTrue(stored, stored.contains("index_transactions_walletId"))
        val existing = plan(ImportSql.EXISTING_KEYS, "w", 0L, 10L)
        assertTrue(existing, existing.contains("USING INDEX") && !existing.contains("SCAN transactions"))
    }
}
