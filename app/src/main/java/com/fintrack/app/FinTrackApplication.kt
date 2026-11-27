package com.fintrack.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.fintrack.app.core.common.BudgetNotifications
import com.fintrack.app.sync.SupabaseConfig
import com.fintrack.app.sync.SyncTrigger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

@HiltAndroidApp
class FinTrackApplication : Application(), Configuration.Provider {
    @Inject lateinit var budgetNotifications: BudgetNotifications
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var supabaseConfig: SupabaseConfig
    @Inject lateinit var syncTrigger: SyncTrigger

    /** Lives as long as the process; only used to observe when to schedule sync. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        budgetNotifications.createChannel()
        if (supabaseConfig.isConfigured) syncTrigger.start(appScope)
    }
}
