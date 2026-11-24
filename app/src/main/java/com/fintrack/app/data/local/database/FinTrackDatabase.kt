package com.fintrack.app.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.fintrack.app.data.local.dao.CategoryDao
import com.fintrack.app.data.local.dao.TransactionDao
import com.fintrack.app.data.local.dao.WalletDao
import com.fintrack.app.data.local.entity.CategoryEntity
import com.fintrack.app.data.local.entity.SyncOutboxEntity
import com.fintrack.app.data.local.entity.TransactionEntity
import com.fintrack.app.data.local.entity.WalletEntity

/**
 * Schema changes require: a version bump, an explicit Migration added to [ALL_MIGRATIONS],
 * the exported schema JSON committed under app/schemas, and a migration test.
 * Destructive migration is never allowed.
 */
@Database(
    entities = [
        WalletEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        SyncOutboxEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class FinTrackDatabase : RoomDatabase() {
    abstract fun walletDao(): WalletDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao

    companion object {
        const val NAME = "fintrack.db"

        /** Explicit migrations, in order. Empty while the schema is at version 1. */
        val ALL_MIGRATIONS = arrayOf<androidx.room.migration.Migration>()
    }
}
