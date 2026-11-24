package com.fintrack.app.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.local.entity.TransactionEntity
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.SyncStatus
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionSource
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType
import com.fintrack.app.domain.model.WriteResult
import com.fintrack.app.domain.validation.TransactionValidationError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Wallet balance scenario approved for Phase 1:
 * BCA Rp10.000.000, Mandiri Rp5.000.000; income BCA Rp5.000.000, expense BCA Rp2.000.000,
 * transfer BCA -> Mandiri Rp3.000.000. Expected: BCA Rp10.000.000, Mandiri Rp8.000.000.
 */
@RunWith(AndroidJUnit4::class)
class TransactionRepositoryTest {

    private lateinit var t: TestDatabase
    private lateinit var bca: String
    private lateinit var mandiri: String
    private lateinit var salary: String
    private lateinit var food: String
    private val date = Instant.parse("2026-01-10T05:00:00Z")

    @Before
    fun setUp() = runTest {
        t = TestDatabase()
        bca = t.wallets.createWallet("BCA", WalletType.BANK, 10_000_000).requireId()
        mandiri = t.wallets.createWallet("Mandiri", WalletType.BANK, 5_000_000).requireId()
        salary = t.categories.createCategory("Gaji", CategoryType.INCOME, null, null, null).requireId()
        food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
    }

    @After
    fun tearDown() = t.close()

    private fun income(amount: Long, wallet: String = bca) =
        TransactionDraft(TransactionType.INCOME, wallet, null, salary, amount, date)

    private fun expense(amount: Long, wallet: String = bca) =
        TransactionDraft(TransactionType.EXPENSE, wallet, null, food, amount, date)

    private fun transfer(amount: Long, from: String = bca, to: String = mandiri) =
        TransactionDraft(TransactionType.TRANSFER, from, to, null, amount, date)

    private suspend fun balances(): Map<String, Long> =
        t.wallets.observeBalances().first().associate { it.walletId to it.balance }

    private suspend fun assertBalances(expectedBca: Long, expectedMandiri: Long) {
        val actual = balances()
        assertEquals("BCA", expectedBca, actual.getValue(bca))
        assertEquals("Mandiri", expectedMandiri, actual.getValue(mandiri))
    }

    private data class Scenario(val income: String, val expense: String, val transfer: String)

    private suspend fun runScenario() = Scenario(
        income = t.transactions.addTransaction(income(5_000_000)).requireId(),
        expense = t.transactions.addTransaction(expense(2_000_000)).requireId(),
        transfer = t.transactions.addTransaction(transfer(3_000_000)).requireId(),
    )

    @Test
    fun `initial balances equal initial wallet balance`() = runTest {
        assertBalances(10_000_000, 5_000_000)
    }

    @Test
    fun `approved scenario produces expected balances`() = runTest {
        runScenario()
        assertBalances(10_000_000, 8_000_000)
    }

    @Test
    fun `income increases and expense decreases only the source wallet`() = runTest {
        t.transactions.addTransaction(income(5_000_000)).requireId()
        assertBalances(15_000_000, 5_000_000)
        t.transactions.addTransaction(expense(2_000_000)).requireId()
        assertBalances(13_000_000, 5_000_000)
    }

    @Test
    fun `transfer is stored as one row and moves money once`() = runTest {
        t.transactions.addTransaction(transfer(3_000_000)).requireId()
        assertBalances(7_000_000, 8_000_000)
        assertEquals(1, t.transactions.observeTransactions().first().size)
        assertEquals(1, t.transactions.observeWalletTransactions(bca).first().size)
        assertEquals(1, t.transactions.observeWalletTransactions(mandiri).first().size)
    }

    @Test
    fun `transfer does not change the combined balance`() = runTest {
        val before = balances().values.sum()
        t.transactions.addTransaction(transfer(3_000_000)).requireId()
        assertEquals(before, balances().values.sum())
    }

    // Editing

    @Test
    fun `editing income amount updates balance`() = runTest {
        val s = runScenario()
        t.transactions.updateTransaction(s.income, income(6_000_000)).requireId()
        assertBalances(11_000_000, 8_000_000)
    }

    @Test
    fun `editing income wallet moves its effect to the new wallet`() = runTest {
        val s = runScenario()
        t.transactions.updateTransaction(s.income, income(5_000_000, wallet = mandiri)).requireId()
        assertBalances(5_000_000, 13_000_000)
    }

    @Test
    fun `editing expense amount updates balance`() = runTest {
        val s = runScenario()
        t.transactions.updateTransaction(s.expense, expense(2_500_000)).requireId()
        assertBalances(9_500_000, 8_000_000)
    }

    @Test
    fun `editing transfer amount updates both wallets`() = runTest {
        val s = runScenario()
        t.transactions.updateTransaction(s.transfer, transfer(1_000_000)).requireId()
        assertBalances(12_000_000, 6_000_000)
    }

    @Test
    fun `editing transfer direction updates both wallets`() = runTest {
        val s = runScenario()
        t.transactions.updateTransaction(s.transfer, transfer(3_000_000, from = mandiri, to = bca)).requireId()
        assertBalances(16_000_000, 2_000_000)
    }

    @Test
    fun `editing keeps the same row and creation time`() = runTest {
        val s = runScenario()
        val before = t.transactions.getTransaction(s.transfer)!!
        t.transactions.updateTransaction(s.transfer, transfer(1_000_000)).requireId()
        val after = t.transactions.getTransaction(s.transfer)!!
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(3, t.transactions.observeTransactions().first().size)
    }

    @Test
    fun `invalid edit leaves transaction and balances unchanged`() = runTest {
        val s = runScenario()
        val result = t.transactions.updateTransaction(s.transfer, transfer(3_000_000, to = bca))
        assertEquals(WriteResult.Invalid(listOf(TransactionValidationError.SameSourceAndDestination)), result)
        assertEquals(3_000_000, t.transactions.getTransaction(s.transfer)!!.amount)
        assertBalances(10_000_000, 8_000_000)
    }

    // Deleting

    @Test
    fun `deleting income reverses its effect`() = runTest {
        val s = runScenario()
        t.transactions.deleteTransaction(s.income).requireId()
        assertBalances(5_000_000, 8_000_000)
    }

    @Test
    fun `deleting expense reverses its effect`() = runTest {
        val s = runScenario()
        t.transactions.deleteTransaction(s.expense).requireId()
        assertBalances(12_000_000, 8_000_000)
    }

    @Test
    fun `deleting transfer reverses its effect on both wallets`() = runTest {
        val s = runScenario()
        t.transactions.deleteTransaction(s.transfer).requireId()
        assertBalances(13_000_000, 5_000_000)
    }

    @Test
    fun `delete is a soft delete that keeps the row`() = runTest {
        val s = runScenario()
        t.transactions.deleteTransaction(s.expense).requireId()

        assertNull(t.transactions.getTransaction(s.expense))
        t.db.openHelper.readableDatabase
            .query("SELECT deletedAt, syncStatus FROM transactions WHERE id = ?", arrayOf(s.expense))
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(t.clock.millis(), cursor.getLong(0))
                assertEquals(SyncStatus.PENDING.name, cursor.getString(1))
            }
    }

    // Duplicate effects

    @Test
    fun `deleting twice has no second effect`() = runTest {
        val s = runScenario()
        t.transactions.deleteTransaction(s.expense).requireId()
        assertEquals(WriteResult.NotFound, t.transactions.deleteTransaction(s.expense))
        assertBalances(12_000_000, 8_000_000)
    }

    @Test
    fun `deleted transaction cannot be edited back into balances`() = runTest {
        val s = runScenario()
        t.transactions.deleteTransaction(s.income).requireId()
        assertEquals(WriteResult.NotFound, t.transactions.updateTransaction(s.income, income(9_000_000)))
        assertBalances(5_000_000, 8_000_000)
    }

    @Test
    fun `repeated edits never duplicate a transfer`() = runTest {
        val s = runScenario()
        repeat(3) { t.transactions.updateTransaction(s.transfer, transfer(3_000_000)).requireId() }
        assertEquals(3, t.transactions.observeTransactions().first().size)
        assertBalances(10_000_000, 8_000_000)
    }

    @Test
    fun `inserting an existing transaction id is rejected`() = runTest {
        val s = runScenario()
        val duplicate = TransactionEntity(
            id = s.income, walletId = bca, destinationWalletId = null, categoryId = salary,
            type = TransactionType.INCOME, amount = 5_000_000, transactionDate = date.toEpochMilli(),
            description = null, merchant = null, source = TransactionSource.MANUAL, importBatchId = null,
            externalId = null, createdAt = 0, updatedAt = 0, deletedAt = null, syncStatus = SyncStatus.PENDING,
        )
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking { t.db.transactionDao().insert(duplicate) }
        }
        assertBalances(10_000_000, 8_000_000)
    }

    // Validation at repository level

    @Test
    fun `invalid transaction is not saved`() = runTest {
        val result = t.transactions.addTransaction(income(-1))
        assertEquals(WriteResult.Invalid(listOf(TransactionValidationError.AmountNotPositive)), result)
        assertTrue(t.transactions.observeTransactions().first().isEmpty())
        assertBalances(10_000_000, 5_000_000)
    }

    @Test
    fun `category type is checked against the database`() = runTest {
        val result = t.transactions.addTransaction(
            TransactionDraft(TransactionType.EXPENSE, bca, null, salary, 10_000, date),
        )
        assertEquals(WriteResult.Invalid(listOf(TransactionValidationError.CategoryTypeMismatch)), result)
    }

    @Test
    fun `unknown wallet is rejected`() = runTest {
        val result = t.transactions.addTransaction(expense(10_000, wallet = "missing"))
        assertEquals(WriteResult.Invalid(listOf(TransactionValidationError.SourceWalletNotFound)), result)
    }

    // Archived references

    @Test
    fun `archived category is kept by history but rejected for new transactions`() = runTest {
        val s = runScenario()
        t.categories.archiveCategory(food).requireId()

        assertNotNull(t.categories.getCategory(food))
        assertEquals(food, t.transactions.getTransaction(s.expense)!!.categoryId)
        assertEquals(
            WriteResult.Invalid(listOf(TransactionValidationError.CategoryArchived)),
            t.transactions.addTransaction(expense(10_000)),
        )
        // Editing the historical transaction keeps its archived category.
        t.transactions.updateTransaction(s.expense, expense(1_500_000)).requireId()
        assertBalances(10_500_000, 8_000_000)
    }

    @Test
    fun `archived wallet keeps its balance and rejects new transactions`() = runTest {
        runScenario()
        t.wallets.archiveWallet(mandiri).requireId()

        assertBalances(10_000_000, 8_000_000)
        assertEquals(
            WriteResult.Invalid(listOf(TransactionValidationError.DestinationWalletArchived)),
            t.transactions.addTransaction(transfer(1_000)),
        )
    }

    @Test
    fun `optional text fields are trimmed and blank becomes null`() = runTest {
        val id = t.transactions.addTransaction(
            expense(10_000).copy(description = "  Makan siang  ", merchant = "   "),
        ).requireId()
        val saved = t.transactions.getTransaction(id)!!
        assertEquals("Makan siang", saved.description)
        assertNull(saved.merchant)
    }
}
