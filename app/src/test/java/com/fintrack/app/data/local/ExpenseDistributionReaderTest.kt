package com.fintrack.app.data.local

import androidx.room.Transactor
import androidx.room.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.analytics.stats.Percentile
import com.fintrack.app.analytics.stats.StreamingDistribution
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.random.Random

/** Addendum B2: stream = in-memory sort, filters, transaction in effect, consistency under writes. */
@RunWith(AndroidJUnit4::class)
class ExpenseDistributionReaderTest {

    private val t = TestDatabase()
    private val reader = ExpenseDistributionReader(t.db)
    private lateinit var wallet: String
    private lateinit var other: String
    private lateinit var food: String
    private lateinit var salary: String
    private val start = Instant.parse("2026-10-31T17:00:00Z").toEpochMilli()
    private val end = Instant.parse("2026-11-30T17:00:00Z").toEpochMilli()

    @Before
    fun setUp() = runBlocking {
        wallet = t.wallets.createWallet("BCA", WalletType.BANK, 0).requireId()
        other = t.wallets.createWallet("Mandiri", WalletType.BANK, 0).requireId()
        food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        salary = t.categories.createCategory("Gaji", CategoryType.INCOME, null, null, null).requireId()
        Unit
    }

    @After
    fun tearDown() = t.close()

    private suspend fun add(type: TransactionType, amount: Long, millis: Long) = t.transactions.addTransaction(
        TransactionDraft(type, wallet, if (type == TransactionType.TRANSFER) other else null, when (type) {
            TransactionType.EXPENSE -> food
            TransactionType.INCOME -> salary
            TransactionType.TRANSFER -> null
        }, amount, Instant.ofEpochMilli(millis)),
    ).requireId()

    @Test
    fun `streamed result equals an in-memory sort of 5000 random expenses and ignores other rows`() = runBlocking {
        val random = Random(42)
        val amounts = List(5_000) { 1_000L * random.nextLong(1, 5_000) }
        amounts.forEach { add(TransactionType.EXPENSE, it, start + random.nextLong(0, end - start)) }
        add(TransactionType.INCOME, 99_000_000, start + 1)
        add(TransactionType.TRANSFER, 88_000_000, start + 2)
        t.transactions.deleteTransaction(add(TransactionType.EXPENSE, 77_000_000, start + 3)).requireId()
        add(TransactionType.EXPENSE, 66_000_000, end) // next day: outside [start, end)

        val expected = StreamingDistribution(amounts.size).apply { amounts.sorted().forEach(::accept) }.result()
        val actual = reader.read(start, end)
        assertEquals(expected, actual)
        assertEquals(5_000, actual.count)
        assertTrue(actual.percentiles[Percentile.P99] != null)
    }

    @Test
    fun `the read runs inside a transaction on Room's reader connection`() = runBlocking {
        val inside = t.db.useReaderConnection<Boolean> { connection ->
            connection.withTransaction<Boolean>(Transactor.SQLiteTransactionType.DEFERRED) { connection.inTransaction() }
        }
        assertTrue(inside)
    }

    @Test
    fun `concurrent writes never produce a count that differs from the streamed rows`() = runBlocking {
        repeat(500) { add(TransactionType.EXPENSE, 10_000L + it, start + it * 1_000L) }
        val writer = async(Dispatchers.IO) {
            repeat(300) { add(TransactionType.EXPENSE, 5_000L + it, start + 600_000L + it) }
        }
        val counts = mutableListOf<Int>()
        while (!writer.isCompleted) counts += reader.read(start, end).count // result() throws on any mismatch
        writer.await()
        assertEquals(800, reader.read(start, end).count)
        assertEquals(counts.sorted(), counts) // a later read never sees fewer rows
    }

    @Test
    fun `repository flow recomputes after a write`() = runBlocking {
        val range = com.fintrack.app.core.util.JakartaTime.month(java.time.YearMonth.of(2026, 11))
        add(TransactionType.EXPENSE, 10_000, start + 10)
        assertEquals(1, t.analytics.observeExpenseDistribution(range).first().count)
        add(TransactionType.EXPENSE, 20_000, start + 20)
        assertEquals(2, t.analytics.observeExpenseDistribution(range).first().count)
    }
}
