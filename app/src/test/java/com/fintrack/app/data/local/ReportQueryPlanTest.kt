package com.fintrack.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.data.TestDatabase
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the dashboard queries against regressing into full-table scans. The SQL mirrors
 * ReportDao's WHERE clauses; plans are read with EXPLAIN QUERY PLAN.
 */
@RunWith(AndroidJUnit4::class)
class ReportQueryPlanTest {

    private val t = TestDatabase()

    @After
    fun tearDown() = t.close()

    private fun plan(sql: String): String =
        t.db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString(" | ")
        }

    @Test
    fun `period totals use the transaction date index`() {
        val plan = plan(
            "SELECT COUNT(*) FROM transactions WHERE +deletedAt IS NULL AND transactionDate >= 0 AND transactionDate < 10",
        )
        assertTrue(plan, plan.contains("index_transactions_transactionDate"))
    }

    @Test
    fun `daily and category totals use the type and date index`() {
        val daily = plan(
            "SELECT (transactionDate - 0) / 86400000 AS d, SUM(amount) FROM transactions WHERE +deletedAt IS NULL " +
                "AND type IN ('INCOME','EXPENSE') AND transactionDate >= 0 AND transactionDate < 10 GROUP BY d",
        )
        assertTrue(daily, daily.contains("index_transactions_type_transactionDate"))
        val category = plan(
            "SELECT categoryId, SUM(amount) AS total FROM transactions WHERE +deletedAt IS NULL AND type = 'EXPENSE' " +
                "AND transactionDate >= 0 AND transactionDate < 10 GROUP BY categoryId ORDER BY total DESC",
        )
        assertTrue(category, category.contains("index_transactions_type_transactionDate"))
    }

    @Test
    fun `recent transactions read the date index instead of sorting the table`() {
        val plan = plan("SELECT * FROM transactions WHERE +deletedAt IS NULL ORDER BY transactionDate DESC, createdAt DESC LIMIT 5")
        assertTrue(plan, plan.contains("index_transactions_transactionDate"))
    }
}
