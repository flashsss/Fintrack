package com.fintrack.app.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SyncSchedulerTest {

    private lateinit var workManager: WorkManager
    private lateinit var scheduler: SyncScheduler

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(context)
        scheduler = SyncScheduler(workManager)
    }

    private fun oneTime() = workManager.getWorkInfosForUniqueWork(SyncScheduler.ONE_TIME_WORK).get()
    private fun periodic() = workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_WORK).get()

    @Test
    fun `a burst of changes leaves one delayed run that needs a network`() {
        repeat(5) { scheduler.requestSoon() }
        val waiting = oneTime().filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, waiting.size)
        assertEquals(NetworkType.CONNECTED, waiting.single().constraints.requiredNetworkType)
        assertTrue(waiting.single().initialDelayMillis >= TimeUnit.SECONDS.toMillis(SyncScheduler.DEBOUNCE_SECONDS))
    }

    @Test
    fun `periodic sync is scheduled once every six hours`() {
        scheduler.schedulePeriodic()
        scheduler.schedulePeriodic()
        val work = periodic().single { !it.state.isFinished }
        assertEquals(TimeUnit.HOURS.toMillis(SyncScheduler.PERIODIC_HOURS), work.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
    }

    @Test
    fun `sign out cancels all sync work`() {
        scheduler.schedulePeriodic()
        scheduler.requestSoon()
        scheduler.cancelAll()
        assertTrue((oneTime() + periodic()).all { it.state == WorkInfo.State.CANCELLED })
    }
}
