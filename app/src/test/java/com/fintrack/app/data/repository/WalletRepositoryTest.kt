package com.fintrack.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.WalletType
import com.fintrack.app.domain.model.WriteResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalletRepositoryTest {

    private val t = TestDatabase()

    @After
    fun tearDown() = t.close()

    @Test
    fun `create stores trimmed name, IDR currency and UTC timestamps`() = runTest {
        val id = t.wallets.createWallet("  Dompet Tunai ", WalletType.CASH, 250_000).requireId()
        val wallet = t.wallets.getWallet(id)!!
        assertEquals("Dompet Tunai", wallet.name)
        assertEquals("IDR", wallet.currency)
        assertEquals(250_000, wallet.initialBalance)
        assertEquals(t.clock.instant(), wallet.createdAt)
    }

    @Test
    fun `blank name is rejected and nothing is saved`() = runTest {
        assertEquals(
            WriteResult.Invalid(listOf(WalletValidationError.NameBlank)),
            t.wallets.createWallet("  ", WalletType.CASH, 0),
        )
        assertEquals(0, t.wallets.observeAllWallets().first().size)
    }

    @Test
    fun `credit card wallet may start with a negative balance`() = runTest {
        val id = t.wallets.createWallet("Kartu Kredit", WalletType.CREDIT_CARD, -1_500_000).requireId()
        assertEquals(-1_500_000, t.wallets.observeBalances().first().single { it.walletId == id }.balance)
    }

    @Test
    fun `update changes initial balance used by the balance formula`() = runTest {
        val id = t.wallets.createWallet("BCA", WalletType.BANK, 1_000).requireId()
        t.wallets.updateWallet(id, "BCA Utama", WalletType.BANK, 2_000).requireId()
        assertEquals("BCA Utama", t.wallets.getWallet(id)!!.name)
        assertEquals(2_000, t.wallets.observeBalances().first().single().balance)
    }

    @Test
    fun `archive hides wallet from active list but keeps it`() = runTest {
        val id = t.wallets.createWallet("OVO", WalletType.E_WALLET, 0).requireId()
        t.wallets.archiveWallet(id).requireId()
        assertEquals(0, t.wallets.observeActiveWallets().first().size)
        assertFalse(t.wallets.getWallet(id)!!.isActive)
        assertEquals(1, t.wallets.observeAllWallets().first().size)
    }

    @Test
    fun `updating unknown wallet returns NotFound`() = runTest {
        assertEquals(WriteResult.NotFound, t.wallets.updateWallet("missing", "X", WalletType.CASH, 0))
        assertEquals(WriteResult.NotFound, t.wallets.archiveWallet("missing"))
    }

    // Restore and delete

    private fun pendingAndDeletedAt(id: String): Pair<String, Long?> =
        t.db.openHelper.readableDatabase.query("SELECT syncStatus, deletedAt FROM wallets WHERE id = ?", arrayOf(id)).use { c ->
            c.moveToFirst()
            c.getString(0) to (if (c.isNull(1)) null else c.getLong(1))
        }

    @Test
    fun `an archived wallet can be restored and chosen again`() = runTest {
        val id = t.wallets.createWallet("Dompet Lama", WalletType.CASH, 50_000).requireId()
        t.wallets.archiveWallet(id).requireId()
        assertTrue(t.wallets.observeActiveWallets().first().isEmpty())

        t.wallets.unarchiveWallet(id).requireId()
        assertEquals(listOf(id), t.wallets.observeActiveWallets().first().map { it.id })
        assertEquals("PENDING", pendingAndDeletedAt(id).first)
        t.wallets.unarchiveWallet(id).requireId() // idempotent
    }

    @Test
    fun `a wallet used by transactions or transfers cannot be deleted`() = runTest {
        val bca = t.wallets.createWallet("BCA", WalletType.BANK, 0).requireId()
        val mandiri = t.wallets.createWallet("Mandiri", WalletType.BANK, 0).requireId()
        t.transactions.addTransaction(
            com.fintrack.app.domain.model.TransactionDraft(com.fintrack.app.domain.model.TransactionType.TRANSFER, bca, mandiri, null, 1_000, java.time.Instant.EPOCH),
        ).requireId()

        // Mandiri is only a transfer destination; it still counts.
        assertEquals(WriteResult.Invalid(listOf(WalletValidationError.HasTransactions(1))), t.wallets.deleteWallet(mandiri))
        assertEquals(WriteResult.Invalid(listOf(WalletValidationError.HasTransactions(1))), t.wallets.deleteWallet(bca))
        assertEquals(2, t.wallets.observeAllWallets().first().size)
        assertEquals(null, pendingAndDeletedAt(bca).second)
    }

    @Test
    fun `an unused wallet is soft-deleted and its deleted transactions do not block it`() = runTest {
        val id = t.wallets.createWallet("Salah buat", WalletType.CASH, 25_000).requireId()
        val food = t.categories.createCategory("Makanan", com.fintrack.app.domain.model.CategoryType.EXPENSE, null, null, null).requireId()
        val tx = t.transactions.addTransaction(
            com.fintrack.app.domain.model.TransactionDraft(com.fintrack.app.domain.model.TransactionType.EXPENSE, id, null, food, 1_000, java.time.Instant.EPOCH),
        ).requireId()
        t.transactions.deleteTransaction(tx).requireId()

        t.wallets.deleteWallet(id).requireId()
        assertTrue(t.wallets.observeAllWallets().first().isEmpty())
        assertTrue(t.wallets.observeBalances().first().isEmpty())
        assertEquals(null, t.wallets.getWallet(id))
        // The row stays (soft deletion) and is queued for sync.
        val (status, deletedAt) = pendingAndDeletedAt(id)
        assertEquals("PENDING", status)
        assertEquals(t.clock.millis(), deletedAt)
        assertEquals(WriteResult.NotFound, t.wallets.deleteWallet(id))
    }
}
