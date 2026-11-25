package com.fintrack.app.data.local

import com.fintrack.app.data.local.dao.AnalyticsSql
import com.fintrack.app.data.local.dao.ReportSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Runs the app's SQL on SQLite 3.21 (sqlite-jdbc), against the exported Room schema.
 *
 * Robolectric and the JVM use a modern SQLite, so SQL that minSdk 29 devices (SQLite 3.22) cannot
 * run would otherwise pass every test. 3.21 is older than 3.22, so passing here implies 3.22.
 * Plain JUnit (no Robolectric): the legacy driver must not be mixed with Android's SQLite.
 */
class SqliteCompatibilityTest {

    private lateinit var connection: Connection

    @Before
    fun setUp() {
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { statement ->
            schemaStatements(File(SCHEMA).readText()).forEach(statement::execute)
        }
    }

    @After
    fun tearDown() = connection.close()

    /** Prepares and executes [sql] with every parameter bound to 0; returns the row count. */
    private fun run(sql: String): Int = connection.prepareStatement(sql).use { statement ->
        for (index in 1..statement.parameterMetaData.parameterCount) statement.setLong(index, 0)
        statement.executeQuery().use { rows -> var n = 0; while (rows.next()) n++; n }
    }

    @Test
    fun `the exported schema is fully created`() {
        val statements = schemaStatements(File(SCHEMA).readText())
        assertEquals(18, statements.size) // 4 tables + 14 indices in schema version 1
        connection.createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type IN ('table','index') AND name NOT LIKE 'sqlite_%'").use { r ->
                r.next()
                assertEquals(18, r.getInt(1))
            }
        }
    }

    @Test
    fun `engine under test is SQLite 3_21`() {
        connection.createStatement().use { s ->
            s.executeQuery("SELECT sqlite_version()").use { r -> r.next(); assertEquals("3.21.0", r.getString(1)) }
        }
    }

    @Test
    fun `report queries run on SQLite 3_21`() {
        assertEquals(1, run(ReportSql.PERIOD_TOTALS))
        assertEquals(0, run(ReportSql.DAILY_TOTALS.replace(":dayMillis", "86400000")))
        assertEquals(0, run(ReportSql.EXPENSE_BY_CATEGORY))
        assertEquals(0, run(ReportSql.RECENT))
        assertEquals(1, run(ReportSql.HAS_TRANSACTIONS))
    }

    @Test
    fun `analytics queries run on SQLite 3_21`() {
        assertEquals(0, run(AnalyticsSql.EXPENSE_BY_CATEGORY_AND_DAY))
        assertEquals(0, run(AnalyticsSql.EXPENSE_BY_WEEKDAY))
    }

    @Test
    fun `negative control - window functions are rejected by SQLite 3_21`() {
        assertThrows(SQLException::class.java) {
            run("SELECT amount, ROW_NUMBER() OVER (ORDER BY amount) FROM transactions")
        }
    }

    companion object {
        /**
         * CREATE statements from Room's exported schema JSON, in order. `tableName` precedes the
         * entity's `createSql` and its indices, so the current table name replaces `${TABLE_NAME}`.
         * (org.json is an unmocked Android stub in plain JVM tests, hence the small tokenizer.)
         */
        fun schemaStatements(json: String): List<String> {
            val token = Regex(""""(tableName|createSql)"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            var table = ""
            return token.findAll(json).mapNotNull { match ->
                val value = match.groupValues[2].replace("\\\"", "\"").replace("\\\\", "\\")
                if (match.groupValues[1] == "tableName") {
                    table = value
                    null
                } else {
                    value.replace("\${TABLE_NAME}", table)
                }
            }.toList()
        }

        /** Gradle runs unit tests with the module directory as the working directory. */
        const val SCHEMA = "schemas/com.fintrack.app.data.local.database.FinTrackDatabase/1.json"
    }
}
