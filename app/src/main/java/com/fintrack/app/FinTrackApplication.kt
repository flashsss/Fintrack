package com.fintrack.app

import android.app.Application
import com.fintrack.app.core.common.BudgetNotifications
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FinTrackApplication : Application() {
    @Inject lateinit var budgetNotifications: BudgetNotifications

    override fun onCreate() {
        super.onCreate()
        budgetNotifications.createChannel()
    }
}
