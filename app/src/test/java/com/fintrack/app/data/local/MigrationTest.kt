package com.fintrack.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fintrack.app.data.local.database.FinTrackDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates the exported schemas in app/schemas against the Room entities and the explicit
 * migrations. Each schema version added later must get a test here that creates the previous
 * version, inserts financial records, migrates, and asserts the records are preserved.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        FinTrackDatabase::class.java,
    )

    @Test
    fun `exported schema matches entities after all migrations`() {
        helper.createDatabase(TEST_DB, FIRST_VERSION).close()
        helper.runMigrationsAndValidate(TEST_DB, LATEST_VERSION, true, *FinTrackDatabase.ALL_MIGRATIONS).close()
    }

    @Test
    fun `records written with the first schema survive opening with the current database`() {
        helper.createDatabase(TEST_DB, FIRST_VERSION).use { db ->
            db.execSQL(
                """
                INSERT INTO wallets (id, name, type, initialBalance, currency, isActive, createdAt, updatedAt,
                    deletedAt, syncStatus)
                VALUES ('w1', 'BCA', 'BANK', 10000000, 'IDR', 1, 0, 0, NULL, 'PENDING')
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO categories (id, name, type, parentId, icon, color, isDefault, isArchived, createdAt,
                    updatedAt, deletedAt, syncStatus)
                VALUES ('c1', 'Gaji', 'INCOME', NULL, NULL, NULL, 0, 0, 0, 0, NULL, 'PENDING')
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO transactions (id, walletId, destinationWalletId, categoryId, type, amount,
                    transactionDate, description, merchant, source, importBatchId, externalId, createdAt,
                    updatedAt, deletedAt, syncStatus)
                VALUES ('t1', 'w1', NULL, 'c1', 'INCOME', 5000000, 0, NULL, NULL, 'MANUAL', NULL, NULL, 0, 0,
                    NULL, 'PENDING')
                """.trimIndent(),
            )
        }

        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            FinTrackDatabase::class.java,
            TEST_DB,
        ).addMigrations(*FinTrackDatabase.ALL_MIGRATIONS).build()
        try {
            val balance = runBlocking { database.walletDao().getById("w1") }
            assertEquals(10_000_000L, balance?.initialBalance)
            assertEquals(5_000_000L, runBlocking { database.transactionDao().getById("t1") }?.amount)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val FIRST_VERSION = 1
        const val LATEST_VERSION = 1
    }
}
