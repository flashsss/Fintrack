package com.fintrack.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fintrack.app.data.local.entity.WalletEntity
import com.fintrack.app.domain.model.WalletBalance
import kotlinx.coroutines.flow.Flow

@Dao
interface WalletDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(wallet: WalletEntity)

    @Update
    suspend fun update(wallet: WalletEntity)

    /** Non-deleted transactions that use the wallet as source or transfer destination. */
    @Query("SELECT COUNT(*) FROM transactions WHERE deletedAt IS NULL AND (walletId = :id OR destinationWalletId = :id)")
    suspend fun activeTransactionCount(id: String): Int

    @Query("SELECT * FROM wallets WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: String): WalletEntity?

    /** Wallets that can be chosen for new transactions. */
    @Query("SELECT * FROM wallets WHERE deletedAt IS NULL AND isActive = 1 ORDER BY name COLLATE NOCASE")
    fun observeActive(): Flow<List<WalletEntity>>

    /** All non-deleted wallets, including archived ones. */
    @Query("SELECT * FROM wallets WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<WalletEntity>>

    /**
     * Wallet balance formula (docs/ENGINEERING_SPEC.md):
     * initialBalance + income - expenses - outgoing transfers + incoming transfers.
     * Soft-deleted transactions are excluded. This query is the single implementation of the formula.
     */
    @Query(
        """
        SELECT w.id AS walletId,
            w.initialBalance
            + COALESCE((
                SELECT SUM(CASE WHEN t.type = 'INCOME' THEN t.amount ELSE -t.amount END)
                FROM transactions t
                WHERE t.walletId = w.id
                  AND t.deletedAt IS NULL
                  AND t.type IN ('INCOME', 'EXPENSE', 'TRANSFER')
            ), 0)
            + COALESCE((
                SELECT SUM(t.amount)
                FROM transactions t
                WHERE t.destinationWalletId = w.id
                  AND t.deletedAt IS NULL
                  AND t.type = 'TRANSFER'
            ), 0) AS balance
        FROM wallets w
        WHERE w.deletedAt IS NULL
        ORDER BY w.name COLLATE NOCASE
        """,
    )
    fun observeBalances(): Flow<List<WalletBalance>>
}
