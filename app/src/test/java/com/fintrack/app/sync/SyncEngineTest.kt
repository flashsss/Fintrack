package com.fintrack.app.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.core.common.MutableClock
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Two devices (real Room databases) synchronizing through one in-memory server. */
@RunWith(AndroidJUnit4::class)
class SyncEngineTest {

    private val server = FakeRemote()
    private val clockA = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val clockB = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val a = TestDatabase(clockA)
    private val b = TestDatabase(clockB)
    private val cursorsA = MemoryCursors()
    private val cursorsB = MemoryCursors()
    private val syncA = SyncEngine(a.syncStore, server, cursorsA)
    private val syncB = SyncEngine(b.syncStore, server, cursorsB)

    @After
    fun tearDown() {
        a.close()
        b.close()
    }

    private fun tick(clock: MutableClock, seconds: Long = 1) {
        clock.now = clock.now.plusSeconds(seconds)
    }

    private suspend fun TestDatabase.balances() = wallets.observeBalances().first().associate { it.walletId to it.balance }
    private suspend fun TestDatabase.pendingCount() = db.syncDao().observePendingCount().first()

    private suspend fun seedA(): Triple<String, String, String> {
        val bca = a.wallets.createWallet("BCA", WalletType.BANK, 1_000_000).requireId()
        val food = a.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        val tx = a.transactions.addTransaction(
            TransactionDraft(TransactionType.EXPENSE, bca, null, food, 25_000, Instant.parse("2026-01-01T03:00:00Z"), "Kopi"),
        ).requireId()
        return Triple(bca, food, tx)
    }

    @Test
    fun `push uploads every pending row and marks it synced`() = runTest {
        val (bca, food, tx) = seedA()
        val report = syncA.run()
        assertEquals(3, report.pushed)
        assertEquals(0, a.pendingCount())
        assertEquals(bca, server.record(SyncTable.WALLETS, bca)!!.id)
        assertEquals(food, server.record(SyncTable.CATEGORIES, food)!!.id)
        assertEquals(25_000L, (server.record(SyncTable.TRANSACTIONS, tx) as SyncRecord.Transaction).entity.amount)

        val calls = server.upsertCalls
        assertEquals(0, syncA.run().pushed)
        assertEquals(calls, server.upsertCalls)
    }

    @Test
    fun `a second device receives everything and computes the same balance`() = runTest {
        val (bca, _, _) = seedA()
        syncA.run()
        val report = syncB.run()
        assertEquals(3, report.pulled)
        assertEquals(mapOf(bca to 975_000L), b.balances())
        assertEquals(0, b.pendingCount())
        assertEquals("Kopi", b.transactions.observeTransactions().first().single().description)
    }

    @Test
    fun `an edit made while the row uploads stays pending and is sent next time`() = runTest {
        val (bca, _, _) = seedA()
        server.onUpsert = { records ->
            if (records.any { it.table == SyncTable.WALLETS }) {
                server.onUpsert = {}
                tick(clockA)
                a.wallets.updateWallet(bca, "BCA Utama", WalletType.BANK, 1_000_000).requireId()
            }
        }
        syncA.run()
        assertEquals(1, a.pendingCount())
        syncA.run()
        assertEquals(0, a.pendingCount())
        assertEquals("BCA Utama", (server.record(SyncTable.WALLETS, bca) as SyncRecord.Wallet).entity.name)
    }

    @Test
    fun `the newer edit wins in both directions`() = runTest {
        val (bca, _, tx) = seedA()
        syncA.run()
        syncB.run()

        // B edits later than A: B's version wins on both devices.
        tick(clockA, 10)
        a.wallets.updateWallet(bca, "Lama", WalletType.BANK, 1_000_000).requireId()
        tick(clockB, 20)
        b.wallets.updateWallet(bca, "Baru", WalletType.BANK, 1_000_000).requireId()
        syncB.run()
        val reportA = syncA.run() // A pushes its older edit (ignored by the server), then pulls B's
        assertEquals(0, reportA.keptLocal)
        assertEquals("Baru", a.wallets.getWallet(bca)!!.name)
        assertEquals("Baru", (server.record(SyncTable.WALLETS, bca) as SyncRecord.Wallet).entity.name)

        // A local pending edit newer than the cloud copy is kept and uploaded.
        tick(clockA, 60)
        a.transactions.updateTransaction(tx, TransactionDraft(TransactionType.EXPENSE, bca, null,
            a.transactions.getTransaction(tx)!!.categoryId, 30_000, Instant.parse("2026-01-01T03:00:00Z"))).requireId()
        syncA.run()
        syncB.run()
        assertEquals(30_000L, b.transactions.getTransaction(tx)!!.amount)
        assertEquals(mapOf(bca to 970_000L), b.balances())
    }

    @Test
    fun `a local edit newer than the pulled row is kept`() = runTest {
        val (bca, _, _) = seedA()
        syncA.run()
        syncB.run()
        tick(clockA, 10)
        a.wallets.updateWallet(bca, "Dari A", WalletType.BANK, 1_000_000).requireId()
        syncA.run()
        // B edits later but syncs pull before push would matter; simulate by merging directly.
        tick(clockB, 30)
        b.wallets.updateWallet(bca, "Dari B", WalletType.BANK, 1_000_000).requireId()
        val remote = server.record(SyncTable.WALLETS, bca)!!
        assertEquals(MergeOutcome.KEPT_LOCAL, b.syncStore.merge(remote))
        assertEquals("Dari B", b.wallets.getWallet(bca)!!.name)
    }

    @Test
    fun `fresh default categories on a new device do not overwrite a synced rename`() = runTest {
        a.categories.seedDefaultCategories()
        val salary = a.categories.observeAllCategories().first().single { it.name == "Gaji" }
        tick(clockA)
        a.categories.updateCategory(salary.id, "Gaji kantor", null, null, null).requireId()
        syncA.run()

        b.categories.seedDefaultCategories()  // same ids, updatedAt = 0
        syncB.run()
        assertEquals("Gaji kantor", b.categories.getCategory(salary.id)!!.name)
        assertEquals("Gaji kantor", (server.record(SyncTable.CATEGORIES, salary.id) as SyncRecord.Category).entity.name)
        assertEquals(0, b.pendingCount())
    }

    @Test
    fun `soft deletion is synchronized and leaves the balance consistent`() = runTest {
        val (bca, _, tx) = seedA()
        syncA.run()
        syncB.run()
        tick(clockA)
        a.transactions.deleteTransaction(tx).requireId()
        syncA.run()
        syncB.run()
        assertNull(b.transactions.getTransaction(tx))
        assertTrue(b.transactions.observeTransactions().first().isEmpty())
        assertEquals(mapOf(bca to 1_000_000L), b.balances())
        assertTrue((server.record(SyncTable.TRANSACTIONS, tx) as SyncRecord.Transaction).entity.deletedAt != null)
    }

    @Test
    fun `a row the server refuses stays pending and does not block the others`() = runTest {
        val (bca, _, tx) = seedA()
        server.rejectIf = { it.id == tx }
        val report = syncA.run()
        assertEquals(1, report.pushRejected)
        assertEquals(2, report.pushed)
        assertEquals(1, a.pendingCount())
        assertEquals(bca, server.record(SyncTable.WALLETS, bca)!!.id)

        server.rejectIf = { false }
        syncA.run()
        assertEquals(0, a.pendingCount())
    }

    @Test
    fun `an invalid cloud transaction is not written`() = runTest {
        val (bca, _, _) = seedA()
        val salary = a.categories.createCategory("Gaji", CategoryType.INCOME, null, null, null).requireId()
        syncA.run()
        // Corrupt row: an expense pointing at an income category (not caught by server CHECKs).
        val bad = (server.record(SyncTable.TRANSACTIONS, a.transactions.observeTransactions().first().single().id) as SyncRecord.Transaction)
            .entity.copy(id = "00000000-0000-0000-0000-0000000000ff", categoryId = salary, updatedAt = 99)
        server.upsert(SyncTable.TRANSACTIONS, listOf(SyncRecord.Transaction(bad)))

        val report = syncB.run()
        assertEquals(1, report.pullRejected)
        assertNull(b.transactions.getTransaction(bad.id))
        assertEquals(mapOf(bca to 975_000L), b.balances())
    }

    @Test
    fun `an interrupted pull keeps committed pages and the next run completes`() = runTest {
        val bca = a.wallets.createWallet("BCA", WalletType.BANK, 0).requireId()
        val food = a.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        repeat(SyncEngine.BATCH_SIZE + 20) { i ->
            a.transactions.addTransaction(TransactionDraft(TransactionType.EXPENSE, bca, null, food, 1_000L + i, Instant.parse("2026-01-01T03:00:00Z"))).requireId()
        }
        assertEquals(SyncEngine.BATCH_SIZE + 22, syncA.run().pushed)

        // Pull calls on B: wallets, categories, transactions page 1, transactions page 2 (fails).
        server.failPullCall = server.pullCalls + 4
        try {
            syncB.run()
            fail("expected Unavailable")
        } catch (expected: RemoteSyncException.Unavailable) {
        }
        assertEquals(SyncEngine.BATCH_SIZE, b.transactions.observeTransactions().first().size)
        val cursor = cursorsB.values[SyncTable.TRANSACTIONS]!!

        server.failPullCall = null
        syncB.run()
        assertEquals(SyncEngine.BATCH_SIZE + 20, b.transactions.observeTransactions().first().size)
        assertTrue(cursorsB.values[SyncTable.TRANSACTIONS]!! > cursor)
        assertEquals(a.balances(), b.balances())
    }

    @Test
    fun `rows re-read in the cursor overlap are not written again`() = runTest {
        val (bca, _, _) = seedA()
        syncA.run()
        assertEquals(3, syncB.run().pulled)
        assertEquals(0, syncB.run().pulled) // same rows come back within the overlap window
        assertEquals(MergeOutcome.UNCHANGED, b.syncStore.merge(server.record(SyncTable.WALLETS, bca)!!))
    }

    @Test
    fun `subcategories arriving before their parent are merged parents first`() = runTest {
        val parent = a.categories.createCategory("Rumah", CategoryType.EXPENSE, null, null, null).requireId()
        val child = a.categories.createCategory("Listrik", CategoryType.EXPENSE, parent, null, null).requireId()
        syncA.run()
        // Rename the parent so the server orders it after the child.
        tick(clockA)
        a.categories.updateCategory(parent, "Rumah tangga", null, null, null).requireId()
        syncA.run()

        syncB.run()
        assertEquals(parent, b.categories.getCategory(child)!!.parentId)
        assertEquals("Rumah tangga", b.categories.getCategory(parent)!!.name)
    }

    @Test
    fun `transfers sync as one record and never as income or expense`() = runTest {
        val bca = a.wallets.createWallet("BCA", WalletType.BANK, 1_000_000).requireId()
        val mandiri = a.wallets.createWallet("Mandiri", WalletType.BANK, 0).requireId()
        a.transactions.addTransaction(TransactionDraft(TransactionType.TRANSFER, bca, mandiri, null, 300_000, Instant.parse("2026-01-01T03:00:00Z"))).requireId()
        syncA.run()
        syncB.run()
        assertEquals(mapOf(bca to 700_000L, mandiri to 300_000L), b.balances())
        assertEquals(1, server.count(SyncTable.TRANSACTIONS))
        assertEquals(0, b.pendingCount())
        val transfer = (server.record(SyncTable.TRANSACTIONS, b.transactions.observeTransactions().first().single().id) as SyncRecord.Transaction).entity
        assertEquals(TransactionType.TRANSFER, transfer.type)
        assertNull(transfer.categoryId)
    }
}
