package com.fintrack.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fintrack.app.data.local.database.FinTrackDatabase
import com.fintrack.app.data.repository.CategoryRepository
import com.fintrack.app.data.repository.TransactionRepository
import com.fintrack.app.data.repository.WalletRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Real Room database in memory (SQLite via Robolectric) with real repositories. */
class TestDatabase {
    val clock: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

    val db: FinTrackDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        FinTrackDatabase::class.java,
    ).build()

    val wallets = WalletRepository(db, db.walletDao(), clock)
    val categories = CategoryRepository(db, db.categoryDao(), clock)
    val transactions = TransactionRepository(db, db.transactionDao(), db.walletDao(), db.categoryDao(), clock)

    fun close() = db.close()
}
