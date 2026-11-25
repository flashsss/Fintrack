package com.fintrack.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query

data class ExistingKeyRow(val transactionDate: Long, val type: String, val amount: Long)

/** Look-ups for CSV import duplicate detection (one-shot, not observed). */
@Dao
interface ImportDao {

    /**
     * Fingerprints already stored for the wallet (fingerprints include the wallet), including
     * soft-deleted rows: a deleted import must not come back silently on re-import. Callers pass at most [ImportSql.MAX_PARAMS] ids per call
     * (SQLite 3.22 allows 999 bound parameters).
     */
    @Query(ImportSql.STORED_FINGERPRINTS)
    suspend fun storedFingerprints(walletId: String, externalIds: List<String>): List<String>

    @Query(ImportSql.EXISTING_KEYS)
    suspend fun existingKeys(walletId: String, startMillis: Long, endMillis: Long): List<ExistingKeyRow>
}

object ImportSql {
    const val MAX_PARAMS = 900

    const val STORED_FINGERPRINTS = "SELECT externalId FROM transactions WHERE walletId = :walletId AND externalId IN (:externalIds)"

    const val EXISTING_KEYS = """
        SELECT transactionDate, type, amount
        FROM transactions
        WHERE +deletedAt IS NULL
          AND walletId = :walletId
          AND type IN ('INCOME', 'EXPENSE')
          AND transactionDate >= :startMillis AND transactionDate < :endMillis
        """
}
