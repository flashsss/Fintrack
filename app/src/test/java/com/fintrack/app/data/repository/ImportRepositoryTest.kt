package com.fintrack.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.importing.ExistingTransactionKey
import com.fintrack.app.domain.importing.PlannedRow
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionSource
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType
import com.fintrack.app.domain.validation.TransactionValidationError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ImportRepositoryTest {

    private val t = TestDatabase()
    private lateinit var bca: String
    private lateinit var mandiri: String
    private lateinit var food: String
    private lateinit var salary: String

    @Before
    fun setUp() = runTest {
        bca = t.wallets.createWallet("BCA", WalletType.BANK, 1_000_000).requireId()
        mandiri = t.wallets.createWallet("Mandiri", WalletType.BANK, 0).requireId()
        food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        salary = t.categories.createCategory("Gaji", CategoryType.INCOME, null, null, null).requireId()
    }

    @After
    fun tearDown() = t.close()

    private fun row(line: Int, type: TransactionType, amount: Long, print: String, wallet: String = bca, category: String? = null) =
        PlannedRow.Ready(
            line,
            TransactionDraft(type, wallet, null, category ?: if (type == TransactionType.EXPENSE) food else salary, amount,
                Instant.parse("2025-12-10T05:00:00Z"), "baris $line"),
            print,
        )

    private suspend fun balance(wallet: String) = t.wallets.observeBalances().first().single { it.walletId == wallet }.balance

    @Test
    fun `imports every row in one batch with source, batch id and fingerprint`() = runTest {
        val result = t.imports.import(listOf(row(2, TransactionType.EXPENSE, 25_000, "a"), row(3, TransactionType.INCOME, 100_000, "b")))
        val imported = result as ImportResult.Imported
        assertEquals(2, imported.count)
        val stored = t.transactions.observeTransactions().first()
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.source == TransactionSource.CSV_IMPORT })
        val columns = t.db.openHelper.readableDatabase.query("SELECT importBatchId, externalId FROM transactions").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }
        assertEquals(setOf(imported.batchId to "a", imported.batchId to "b"), columns.toSet())
        assertEquals(1_075_000L, balance(bca))
    }

    @Test
    fun `an invalid row rejects the whole import`() = runTest {
        t.categories.archiveCategory(food).requireId()
        val result = t.imports.import(listOf(row(2, TransactionType.INCOME, 1_000, "a"), row(3, TransactionType.EXPENSE, 1_000, "b")))
        assertEquals(ImportResult.Rejected(3, listOf(TransactionValidationError.CategoryArchived)), result)
        assertTrue(t.transactions.observeTransactions().first().isEmpty())
        assertEquals(1_000_000L, balance(bca))
    }

    @Test
    fun `an archived wallet rejects the import`() = runTest {
        t.wallets.archiveWallet(mandiri).requireId()
        val result = t.imports.import(listOf(row(2, TransactionType.EXPENSE, 1_000, "a", wallet = mandiri)))
        assertTrue(result is ImportResult.Rejected)
        assertTrue(t.transactions.observeTransactions().first().isEmpty())
    }

    @Test
    fun `importing the same fingerprint twice is refused, also after soft deletion`() = runTest {
        t.imports.import(listOf(row(2, TransactionType.EXPENSE, 1_000, "a")))
        assertEquals(ImportResult.Duplicate(5), t.imports.import(listOf(row(4, TransactionType.EXPENSE, 9, "new"), row(5, TransactionType.EXPENSE, 1_000, "a"))))
        assertEquals(1, t.transactions.observeTransactions().first().size)

        val id = t.transactions.observeTransactions().first().single().id
        t.transactions.deleteTransaction(id).requireId()
        assertEquals(setOf("a"), t.imports.storedFingerprints(bca, listOf("a", "zzz")))
        assertEquals(emptySet<String>(), t.imports.storedFingerprints(mandiri, listOf("a")))
    }

    @Test
    fun `fingerprint look-up handles more ids than SQLite parameters`() = runTest {
        t.imports.import(listOf(row(2, TransactionType.EXPENSE, 1_000, "id-1500")))
        val candidates = (0 until 2_500).map { "id-$it" }
        assertEquals(setOf("id-1500"), t.imports.storedFingerprints(bca, candidates))
    }

    @Test
    fun `existing keys cover income and expense of the wallet by Jakarta day, not transfers or deleted rows`() = runTest {
        // 23:30 WIB on 9 Dec is 16:30 UTC; it belongs to 9 Dec in Jakarta.
        t.transactions.addTransaction(TransactionDraft(TransactionType.EXPENSE, bca, null, food, 5_000, Instant.parse("2025-12-09T16:30:00Z"))).requireId()
        t.transactions.addTransaction(TransactionDraft(TransactionType.TRANSFER, bca, mandiri, null, 7_000, Instant.parse("2025-12-09T03:00:00Z"))).requireId()
        t.transactions.addTransaction(TransactionDraft(TransactionType.INCOME, mandiri, null, salary, 9_000, Instant.parse("2025-12-09T03:00:00Z"))).requireId()
        val deleted = t.transactions.addTransaction(TransactionDraft(TransactionType.EXPENSE, bca, null, food, 11_000, Instant.parse("2025-12-09T03:00:00Z"))).requireId()
        t.transactions.deleteTransaction(deleted).requireId()
        t.transactions.addTransaction(TransactionDraft(TransactionType.EXPENSE, bca, null, food, 13_000, Instant.parse("2025-12-11T03:00:00Z"))).requireId()

        val keys = t.imports.existingKeys(bca, LocalDate.of(2025, 12, 9), LocalDate.of(2025, 12, 10))
        assertEquals(setOf(ExistingTransactionKey(LocalDate.of(2025, 12, 9), TransactionType.EXPENSE, 5_000)), keys)
    }
}
