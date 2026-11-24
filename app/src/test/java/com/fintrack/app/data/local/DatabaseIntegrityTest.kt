package com.fintrack.app.data.local

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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class DatabaseIntegrityTest {

    private val t = TestDatabase()
    private val sql get() = t.db.openHelper.writableDatabase

    @After
    fun tearDown() = t.close()

    private fun rawTransaction(walletId: String, categoryId: String?) = TransactionEntity(
        id = "tx-raw", walletId = walletId, destinationWalletId = null, categoryId = categoryId,
        type = TransactionType.EXPENSE, amount = 1_000, transactionDate = 0, description = null,
        merchant = null, source = TransactionSource.MANUAL, importBatchId = null, externalId = null,
        createdAt = 0, updatedAt = 0, deletedAt = null, syncStatus = SyncStatus.PENDING,
    )

    @Test
    fun `foreign key enforcement is enabled`() {
        sql.query("PRAGMA foreign_keys").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun `transaction with unknown wallet violates foreign key`() = runTest {
        val food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { t.db.transactionDao().insert(rawTransaction("missing-wallet", food)) }
        }
    }

    @Test
    fun `transaction with unknown category violates foreign key`() = runTest {
        val bca = t.wallets.createWallet("BCA", WalletType.BANK, 0).requireId()
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { t.db.transactionDao().insert(rawTransaction(bca, "missing-category")) }
        }
    }

    @Test
    fun `wallet and category referenced by a transaction cannot be physically deleted`() = runTest {
        val bca = t.wallets.createWallet("BCA", WalletType.BANK, 0).requireId()
        val food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        t.transactions.addTransaction(
            TransactionDraft(TransactionType.EXPENSE, bca, null, food, 1_000, Instant.EPOCH),
        ).requireId()

        assertThrows(SQLiteConstraintException::class.java) {
            sql.execSQL("DELETE FROM categories WHERE id = ?", arrayOf(food))
        }
        assertThrows(SQLiteConstraintException::class.java) {
            sql.execSQL("DELETE FROM wallets WHERE id = ?", arrayOf(bca))
        }
    }

    @Test
    fun `parent category referenced by a child cannot be physically deleted`() = runTest {
        val food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        t.categories.createCategory("Restoran", CategoryType.EXPENSE, food, null, null).requireId()
        assertThrows(SQLiteConstraintException::class.java) {
            sql.execSQL("DELETE FROM categories WHERE id = ?", arrayOf(food))
        }
    }

    @Test
    fun `monetary values round-trip as exact integers`() = runTest {
        val large = 9_000_000_000_000L
        val id = t.wallets.createWallet("Investasi", WalletType.INVESTMENT, large).requireId()
        assertEquals(large, t.wallets.getWallet(id)!!.initialBalance)
    }
}
