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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class TransactionFilterTest {

    private val t = TestDatabase()
    private lateinit var wallet: String
    private lateinit var food: String
    private lateinit var transport: String

    @Before
    fun setUp() = runTest {
        wallet = t.wallets.createWallet("BCA", WalletType.BANK, 0).requireId()
        food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        transport = t.categories.createCategory("Transportasi", CategoryType.EXPENSE, null, null, null).requireId()
    }

    @After
    fun tearDown() = t.close()

    private suspend fun add(at: String, category: String, description: String?, merchant: String? = null) =
        t.transactions.addTransaction(
            TransactionDraft(TransactionType.EXPENSE, wallet, null, category, 10_000, Instant.parse(at), description, merchant),
        ).requireId()

    private suspend fun descriptions(query: String, range: com.fintrack.app.domain.model.DateRange? = null) =
        t.transactions.observeTransactions(query, range).first().map { it.description }

    @Test
    fun `blank query without range returns all, newest first`() = runTest {
        add("2026-01-01T03:00:00Z", food, "pertama")
        add("2026-01-02T03:00:00Z", food, "kedua")
        assertEquals(listOf("kedua", "pertama"), descriptions("  "))
    }

    @Test
    fun `query matches description, merchant and category name case-insensitively`() = runTest {
        add("2026-01-01T03:00:00Z", food, "Nasi goreng")
        add("2026-01-02T03:00:00Z", food, "Makan siang", merchant = "Warung Bu Tini")
        add("2026-01-03T03:00:00Z", transport, "Ojek")

        assertEquals(listOf("Nasi goreng"), descriptions("NASI"))
        assertEquals(listOf("Makan siang"), descriptions("tini"))
        assertEquals(listOf("Ojek"), descriptions("transport"))
    }

    @Test
    fun `LIKE wildcards in the query are matched literally`() = runTest {
        add("2026-01-01T03:00:00Z", food, "Diskon 50%")
        add("2026-01-02T03:00:00Z", food, "Diskon 500")
        add("2026-01-03T03:00:00Z", food, "kode_a")
        add("2026-01-04T03:00:00Z", food, "kodeXa")

        assertEquals(listOf("Diskon 50%"), descriptions("50%"))
        assertEquals(listOf("kode_a"), descriptions("e_a"))
    }

    @Test
    fun `month filter respects Jakarta boundaries`() = runTest {
        add("2026-01-31T16:59:59Z", food, "31 Jan WIB") // 23:59:59 WIB on 31 Jan
        add("2026-01-31T17:00:00Z", food, "1 Feb WIB") // 00:00 WIB on 1 Feb
        add("2026-02-28T16:59:59Z", food, "28 Feb WIB")
        add("2026-02-28T17:00:00Z", food, "1 Mar WIB")

        assertEquals(listOf("28 Feb WIB", "1 Feb WIB"), descriptions("", JakartaTime.month(YearMonth.of(2026, 2))))
    }

    @Test
    fun `query and range combine`() = runTest {
        add("2026-03-05T03:00:00Z", food, "Kopi Maret")
        add("2026-04-05T03:00:00Z", food, "Kopi April")
        val march = JakartaTime.range(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))
        assertEquals(listOf("Kopi Maret"), descriptions("kopi", march))
    }

    @Test
    fun `deleted transactions are not listed`() = runTest {
        val id = add("2026-01-01T03:00:00Z", food, "hapus")
        t.transactions.deleteTransaction(id).requireId()
        assertEquals(emptyList<String?>(), descriptions(""))
    }
}
