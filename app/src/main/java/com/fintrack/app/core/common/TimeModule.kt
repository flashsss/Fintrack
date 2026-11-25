package com.fintrack.app.core.common

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TimeModule {
    /** UTC clock for createdAt/updatedAt/deletedAt. Tests pass a fixed clock instead. */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    fun provideTodayProvider(clock: Clock, @ApplicationContext context: Context): TodayProvider =
        TodayProvider(clock, systemTimeChanges(context))
}
