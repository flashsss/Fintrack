package com.fintrack.app.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncTriggerTest {

    private lateinit var workManager: WorkManager
    private val auth = MutableStateFlow<AuthState>(AuthState.Loading)
    private val pending = MutableStateFlow(0)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(context)
    }

    private fun active(name: String) = workManager.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `sync is scheduled only while signed in, at start and after local changes`() = runTest(UnconfinedTestDispatcher()) {
        val trigger = SyncTrigger(auth, pending, SyncScheduler(workManager))
        trigger.start(backgroundScope)

        pending.value = 3
        auth.value = AuthState.SignedOut
        assertTrue(active(SyncScheduler.ONE_TIME_WORK).isEmpty())
        assertTrue(active(SyncScheduler.PERIODIC_WORK).isEmpty())

        auth.value = AuthState.SignedIn("u", "saya@example.com")
        assertEquals(1, active(SyncScheduler.PERIODIC_WORK).size)
        val atStart = active(SyncScheduler.ONE_TIME_WORK).single()
        assertEquals(0L, atStart.initialDelayMillis) // immediate run at start / sign-in

        pending.value = 4 // a local edit
        val afterEdit = active(SyncScheduler.ONE_TIME_WORK).single()
        assertTrue(afterEdit.id != atStart.id && afterEdit.initialDelayMillis > 0) // debounced run replaced it
    }
}
