package com.fintrack.app.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.WalletType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SyncRunnerTest {

    private val t = TestDatabase()
    private val server = FakeRemote()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "sync-test-${System.nanoTime()}")
    private val state = SyncStateStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "sync.preferences_pb") })
    private var user: String? = "user-a"
    private val configured = SupabaseConfig("https://example.supabase.co", "sb_publishable_test")

    private fun runner(config: SupabaseConfig = configured, remote: RemoteSyncSource = server) =
        SyncRunner(config, { user }, SyncEngine(t.syncStore, remote, state), state, t.clock)

    @After
    fun tearDown() {
        scope.cancel()
        t.close()
        dir.deleteRecursively()
    }

    @Test
    fun `nothing happens without configuration or sign in`() = runBlocking {
        t.wallets.createWallet("BCA", WalletType.BANK, 0).requireId()
        assertEquals(SyncOutcome.NotConfigured, runner(SupabaseConfig("", "")).run())
        user = null
        assertEquals(SyncOutcome.SignedOut, runner().run())
        assertEquals(0, server.count(SyncTable.WALLETS))
        assertEquals(SyncProblem.SIGNED_OUT, state.status.first().problem)
    }

    @Test
    fun `the first account is bound and a different account is refused`() = runBlocking {
        t.wallets.createWallet("BCA", WalletType.BANK, 0).requireId()
        assertTrue(runner().run() is SyncOutcome.Done)
        assertEquals("user-a", state.boundUserId())
        val status = state.status.first()
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), status.lastSuccessAt)
        assertEquals(null, status.problem)

        t.wallets.createWallet("Mandiri", WalletType.BANK, 0).requireId()
        user = "user-b"
        assertEquals(SyncOutcome.AccountMismatch, runner().run())
        assertEquals(1, server.count(SyncTable.WALLETS)) // nothing of A's device went to B
        assertEquals(SyncProblem.ACCOUNT_MISMATCH, state.status.first().problem)
    }

    @Test
    fun `offline and refused runs are recorded and a later success clears them`() = runBlocking {
        t.wallets.createWallet("BCA", WalletType.BANK, 0).requireId()
        val offline = object : RemoteSyncSource {
            override suspend fun upsert(table: SyncTable, records: List<SyncRecord>) = throw RemoteSyncException.Unavailable()
            override suspend fun pull(table: SyncTable, since: Instant?, offset: Int, limit: Int) = throw RemoteSyncException.Unavailable()
        }
        assertEquals(SyncOutcome.Offline, runner(remote = offline).run())
        assertEquals(SyncProblem.OFFLINE, state.status.first().problem)

        server.rejectIf = { true }
        val done = runner().run() as SyncOutcome.Done
        assertEquals(1, done.report.pushRejected)
        assertEquals(1, state.status.first().rejectedRows)

        server.rejectIf = { false }
        runner().run()
        assertEquals(SyncStatusInfo(Instant.parse("2026-01-01T00:00:00Z"), null, 0), state.status.first())
    }

    @Test
    fun `cursors survive a new store instance`() = runBlocking {
        state.setCursor(SyncTable.TRANSACTIONS, Instant.parse("2026-11-27T03:51:12.123456Z"))
        assertEquals(Instant.parse("2026-11-27T03:51:12.123456Z"), state.cursor(SyncTable.TRANSACTIONS))
        assertEquals(null, state.cursor(SyncTable.WALLETS))
    }

    @Test
    fun `the worker retries only when offline`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun worker(runner: SyncRunner) = TestListenableWorkerBuilder<SyncWorker>(context).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                SyncWorker(appContext, workerParameters, runner)
        }).build()
        val offline = object : RemoteSyncSource {
            override suspend fun upsert(table: SyncTable, records: List<SyncRecord>) = throw RemoteSyncException.Unavailable()
            override suspend fun pull(table: SyncTable, since: Instant?, offset: Int, limit: Int) = throw RemoteSyncException.Unavailable()
        }
        assertEquals(ListenableWorker.Result.retry(), worker(runner(remote = offline)).doWork())
        assertEquals(ListenableWorker.Result.success(), worker(runner()).doWork())
        user = null
        assertEquals(ListenableWorker.Result.success(), worker(runner()).doWork()) // waits for sign-in, no retry loop
    }
}
