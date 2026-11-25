package com.fintrack.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/** Weekday SQL (addendum B6) compared with java.time before, across and after the Unix epoch. */
@RunWith(AndroidJUnit4::class)
class WeekdayQueryTest {

    private val t = TestDatabase()
    private lateinit var wallet: String
    private lateinit var food: String

    @Before
    fun setUp() = runBlocking {
        wallet = t.wallets.createWallet("Tunai", WalletType.CASH, 0).requireId()
        food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
    }

    @After
    fun tearDown() = t.close()

    private fun add(epochMillis: Long, amount: Long) = runBlocking {
        t.transactions.addTransaction(TransactionDraft(TransactionType.EXPENSE, wallet, null, food, amount, Instant.ofEpochMilli(epochMillis))).requireId()
    }

    private fun jakartaWeekday(epochMillis: Long): DayOfWeek = JakartaTime.localDate(Instant.ofEpochMilli(epochMillis)).dayOfWeek

    private fun query(first: LocalDate, last: LocalDate) = runBlocking { t.analytics.observeExpenseByWeekday(first, last).first() }

    @Test
    fun `boundary timestamps around the epoch map to the java_time weekday`() {
        // -1 ms and 0 are 1 Jan 1970 06:59:59.999 / 07:00 WIB (Thursday); -25 200 001 ms is 31 Dec 1969 23:59:59.999 WIB (Wednesday).
        for (millis in listOf(-1L, -25_200_000L, -25_200_001L, 0L, 1L)) {
            val day = JakartaTime.localDate(Instant.ofEpochMilli(millis))
            val before = query(day, day).sumOf { it.total }
            add(millis, 1_000)
            val rows = query(day, day)
            assertEquals("timestamp $millis", 1, rows.size)
            assertEquals("timestamp $millis", jakartaWeekday(millis), rows.single().day)
            assertEquals(before + 1_000, rows.sumOf { it.total })
        }
    }

    @Test
    fun `ranges before, across and after the epoch match java_time for every transaction`() {
        val spans = listOf(
            LocalDate.of(1969, 6, 1) to LocalDate.of(1969, 6, 30),
            LocalDate.of(1969, 12, 25) to LocalDate.of(1970, 1, 5),
            LocalDate.of(2026, 11, 1) to LocalDate.of(2026, 11, 25),
        )
        for ((first, last) in spans) {
            val start = JakartaTime.range(first, last).start.toEpochMilli()
            val end = JakartaTime.range(first, last).endExclusive.toEpochMilli()
            // Deterministic spread of timestamps through the span, including the first and last millisecond.
            val stamps = listOf(start, end - 1) + (1..40).map { start + (end - start) * it / 41 + it * 997L }
            stamps.forEachIndexed { i, ms -> add(ms, 1_000L + i) }
            val expected = stamps.mapIndexed { i, ms -> jakartaWeekday(ms) to 1_000L + i }
                .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
            assertEquals("$first..$last", expected, query(first, last).associate { it.day to it.total })
        }
    }

    @Test
    fun `a 1950s range with consistent 24-hour days is supported`() {
        val day = LocalDate.of(1955, 3, 15) // Tuesday; Jakarta was UTC+07:30 then
        add(JakartaTime.range(day, day).start.toEpochMilli() + 60_000, 5_000)
        assertEquals(listOf(DayOfWeek.TUESDAY), query(LocalDate.of(1955, 3, 1), LocalDate.of(1955, 3, 31)).map { it.day })
    }

    @Test
    fun `a range across the 1964 offset change is rejected instead of miscounted`() {
        assertThrows(IllegalStateException::class.java) { query(LocalDate.of(1963, 12, 20), LocalDate.of(1964, 1, 10)) }
    }
}
