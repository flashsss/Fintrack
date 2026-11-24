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
}
