package com.fintrack.app.data.local.database

import android.content.Context
import androidx.room.Room
import com.fintrack.app.data.local.dao.CategoryDao
import com.fintrack.app.data.local.dao.ReportDao
import com.fintrack.app.data.local.dao.TransactionDao
import com.fintrack.app.data.local.dao.WalletDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FinTrackDatabase =
        Room.databaseBuilder(context, FinTrackDatabase::class.java, FinTrackDatabase.NAME)
            .addMigrations(*FinTrackDatabase.ALL_MIGRATIONS)
            .build()

    @Provides
    fun provideWalletDao(database: FinTrackDatabase): WalletDao = database.walletDao()

    @Provides
    fun provideCategoryDao(database: FinTrackDatabase): CategoryDao = database.categoryDao()

    @Provides
    fun provideTransactionDao(database: FinTrackDatabase): TransactionDao = database.transactionDao()

    @Provides
    fun provideReportDao(database: FinTrackDatabase): ReportDao = database.reportDao()
}
