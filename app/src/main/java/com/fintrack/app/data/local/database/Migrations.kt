package com.fintrack.app.data.local.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 → 2: adds `budgets` and `financial_goals` (Phase 5). Purely additive: no existing
 * table, column or row is touched. SQL copied from the exported schema `2.json`;
 * MigrationTest validates the result against it and checks that v1 data is preserved.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `budgets` (`id` TEXT NOT NULL, `categoryId` TEXT, `amount` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, `syncStatus` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`), FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_budgets_categoryId` ON `budgets` (`categoryId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_budgets_deletedAt` ON `budgets` (`deletedAt`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `financial_goals` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `targetAmount` INTEGER NOT NULL, " +
                "`currentAmount` INTEGER NOT NULL, `targetDate` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "`deletedAt` INTEGER, `syncStatus` TEXT NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_financial_goals_deletedAt` ON `financial_goals` (`deletedAt`)")
    }
}
