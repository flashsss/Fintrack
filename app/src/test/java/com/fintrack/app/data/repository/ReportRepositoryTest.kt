package com.fintrack.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.analytics.metrics.ActiveWalletTotal
import com.fintrack.app.analytics.metrics.CategoryExpense
import com.fintrack.app.analytics.metrics.PeriodTotals
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.local.entity.TransactionEntity
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.SyncStatus
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionSource
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** SQL aggregation tests against a real Room database. */
@RunWith(AndroidJUnit4::class)
class ReportRepositoryTest {

    private val t = TestDatabase()
    private lateinit var bca: String
    private lateinit var mandiri: String
    private lateinit var salary: String
    private lateinit var food: String
    private lateinit var transport: String
    private val november = JakartaTime.month(YearMonth.of(2026, 11))

    @Before
    fun setUp() = runTest {
        bca = t.wallets.createWallet("BCA", WalletType.BANK, 10_000_000).requireId()
        mandiri = t.wallets.createWallet("Mandiri", WalletType.BANK, 5_000_000).requireId()
        salary = t.categories.createCategory("Gaji", CategoryType.INCOME, null, null, null).requireId()
        food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        transport = t.categories.createCategory("Transportasi", CategoryType.EXPENSE, null, null, null).requireId()
    }

    @After
    fun tearDown() = t.close()

    private suspend fun add(type: TransactionType, amount: Long, at: String, category: String? = null, wallet: String = bca, dest: String? = null) =
        t.transactions.addTransaction(TransactionDraft(type, wallet, dest, category, amount, Instant.parse(at))).requireId()

    private suspend fun income(amount: Long, at: String) = add(TransactionType.INCOME, amount, at, salary)
    private suspend fun expense(amount: Long, at: String, category: String = food) = add(TransactionType.EXPENSE, amount, at, category)
    private suspend fun transfer(amount: Long, at: String) = add(TransactionType.TRANSFER, amount, at, dest = mandiri, wallet = bca)

    // Period totals

    @Test
    fun `totals aggregate income and expense and exclude transfers and deleted rows`() = runTest {
        income(5_000_000, "2026-11-02T03:00:00Z")
        income(1_000_000, "2026-11-15T03:00:00Z")
        expense(2_000_000, "2026-11-03T03:00:00Z")
        expense(250_000, "2026-11-04T03:00:00Z", transport)
        transfer(3_000_000, "2026-11-05T03:00:00Z")
        val deleted = expense(999_999, "2026-11-06T03:00:00Z")
        t.transactions.deleteTransaction(deleted).requireId()

        val totals = t.reports.observePeriodTotals(november).first()
        assertEquals(PeriodTotals(income = 6_000_000, expense = 2_250_000, transactionCount = 5, transferCount = 1), totals)
        assertEquals(3_750_000L, totals.netCashFlow)
    }

    @Test
    fun `empty period totals are zero`() = runTest {
        assertEquals(PeriodTotals.EMPTY, t.reports.observePeriodTotals(november).first())
    }

    @Test
    fun `totals respect Jakarta month boundaries`() = runTest {
        expense(100, "2026-10-31T16:59:59Z") // 31 Oct 23:59:59 WIB
        expense(200, "2026-10-31T17:00:00Z") // 1 Nov 00:00 WIB
        expense(400, "2026-11-30T16:59:59Z") // 30 Nov 23:59:59 WIB
        expense(800, "2026-11-30T17:00:00Z") // 1 Dec 00:00 WIB
        assertEquals(600L, t.reports.observePeriodTotals(november).first().expense)
    }

    // Daily totals

    @Test
    fun `daily totals sum multiple transactions per day and skip empty days`() = runTest {
        income(1_000, "2026-11-02T01:00:00Z")
        expense(300, "2026-11-02T05:00:00Z")
        expense(200, "2026-11-02T10:00:00Z")
        expense(50, "2026-11-04T03:00:00Z")

        val daily = t.reports.observeDailyTotals(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)).first()
        assertEquals(setOf(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 4)), daily.keys)
        assertEquals(1_000L, daily.getValue(LocalDate.of(2026, 11, 2)).income)
        assertEquals(500L, daily.getValue(LocalDate.of(2026, 11, 2)).expense)
        assertEquals(50L, daily.getValue(LocalDate.of(2026, 11, 4)).expense)
    }

    @Test
    fun `daily totals use WIB days`() = runTest {
        expense(1, "2026-11-01T16:59:59Z") // 1 Nov 23:59:59 WIB
        expense(10, "2026-11-01T17:00:00Z") // 2 Nov 00:00:00 WIB
        expense(100, "2026-10-31T16:59:59Z") // 31 Oct WIB, outside the range

        val daily = t.reports.observeDailyTotals(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2)).first()
        assertEquals(1L, daily.getValue(LocalDate.of(2026, 11, 1)).expense)
        assertEquals(10L, daily.getValue(LocalDate.of(2026, 11, 2)).expense)
        assertEquals(2, daily.size)
    }

    @Test
    fun `daily totals stay within month boundaries and exclude transfers`() = runTest {
        expense(5, "2026-11-30T16:00:00Z") // 30 Nov 23:00 WIB
        expense(7, "2026-11-30T17:00:00Z") // 1 Dec WIB
        transfer(1_000, "2026-11-10T03:00:00Z")

        val daily = t.reports.observeDailyTotals(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)).first()
        assertEquals(mapOf(LocalDate.of(2026, 11, 30) to 5L), daily.mapValues { it.value.expense })
    }

    // Expense by category

    @Test
    fun `expense is grouped by category id including archived categories`() = runTest {
        expense(300, "2026-11-02T03:00:00Z", food)
        expense(200, "2026-11-03T03:00:00Z", food)
        expense(700, "2026-11-04T03:00:00Z", transport)
        income(10_000, "2026-11-05T03:00:00Z")
        t.categories.archiveCategory(food).requireId()

        assertEquals(
            listOf(CategoryExpense(transport, 700), CategoryExpense(food, 500)),
            t.reports.observeExpenseByCategory(november).first(),
        )
    }

    @Test
    fun `categories with the same name stay separate`() = runTest {
        val otherFood = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        expense(100, "2026-11-02T03:00:00Z", food)
        expense(100, "2026-11-02T04:00:00Z", otherFood)
        assertEquals(2, t.reports.observeExpenseByCategory(november).first().size)
    }

    @Test
    fun `expense without category is grouped under null`() = runTest {
        // The validator always requires a category; this row simulates legacy or imported data.
        t.db.transactionDao().insert(
            TransactionEntity(
                id = "legacy", walletId = bca, destinationWalletId = null, categoryId = null,
                type = TransactionType.EXPENSE, amount = 400, transactionDate = Instant.parse("2026-11-02T03:00:00Z").toEpochMilli(),
                description = null, merchant = null, source = TransactionSource.CSV_IMPORT, importBatchId = null,
                externalId = null, createdAt = 0, updatedAt = 0, deletedAt = null, syncStatus = SyncStatus.PENDING,
            ),
        )
        expense(100, "2026-11-02T03:00:00Z")
        assertEquals(
            listOf(CategoryExpense(null, 400), CategoryExpense(food, 100)),
            t.reports.observeExpenseByCategory(november).first(),
        )
    }

    @Test
    fun `empty category data`() = runTest {
        transfer(100, "2026-11-02T03:00:00Z")
        assertTrue(t.reports.observeExpenseByCategory(november).first().isEmpty())
    }

    // Recent

    @Test
    fun `recent transactions are newest first, limited and exclude deleted`() = runTest {
        val ids = (1..7).map { expense(it.toLong(), "2026-11-0${it}T03:00:00Z") }
        t.transactions.deleteTransaction(ids.last()).requireId()

        val recent = t.reports.observeRecentTransactions(5).first()
        assertEquals(ids.subList(1, 6).reversed(), recent.map { it.id })
        assertTrue(t.reports.observeHasTransactions().first())
    }

    @Test
    fun `has transactions is false when everything is deleted`() = runTest {
        assertFalse(t.reports.observeHasTransactions().first())
        t.transactions.deleteTransaction(expense(1, "2026-11-02T03:00:00Z")).requireId()
        assertFalse(t.reports.observeHasTransactions().first())
    }

    // Wallet overview (balances from the existing SQL query)

    @Test
    fun `active wallet total includes negative balances and transfers but not archived wallets`() = runTest {
        val card = t.wallets.createWallet("Kartu", WalletType.CREDIT_CARD, -2_000_000).requireId()
        val old = t.wallets.createWallet("Lama", WalletType.CASH, 750_000).requireId()
        t.wallets.archiveWallet(old).requireId()
        transfer(3_000_000, "2026-11-05T03:00:00Z")
        add(TransactionType.EXPENSE, 500_000, "2026-11-06T03:00:00Z", food, wallet = card)

        val total = ActiveWalletTotal.of(t.wallets.observeAllWallets().first(), t.wallets.observeBalances().first())
        // BCA 7.000.000 + Mandiri 8.000.000 + Kartu -2.500.000; Lama (archived) excluded.
        assertEquals(ActiveWalletTotal(total = 12_500_000, activeCount = 3, archivedCount = 1), total)
    }
}
