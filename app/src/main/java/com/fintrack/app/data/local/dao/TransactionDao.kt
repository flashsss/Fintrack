package com.fintrack.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fintrack.app.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    /** ABORT: inserting an existing id fails instead of silently replacing a financial record. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transaction: TransactionEntity)

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id = :id AND deletedAt IS NULL")
    fun observeById(id: String): Flow<TransactionEntity?>

    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL ORDER BY transactionDate DESC, createdAt DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    /** Transactions that affect the wallet, including transfers into it. */
    @Query(
        """
        SELECT * FROM transactions
        WHERE deletedAt IS NULL AND (walletId = :walletId OR destinationWalletId = :walletId)
        ORDER BY transactionDate DESC, createdAt DESC
        """,
    )
    fun observeByWallet(walletId: String): Flow<List<TransactionEntity>>

    /**
     * History filtered by an optional UTC date range [startMillis, endMillis) and an optional LIKE
     * [pattern] (already escaped with a backslash) matched against description, merchant and category name.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE deletedAt IS NULL
          AND (:startMillis IS NULL OR transactionDate >= :startMillis)
          AND (:endMillis IS NULL OR transactionDate < :endMillis)
          AND (
            :pattern IS NULL
            OR description LIKE :pattern ESCAPE '\'
            OR merchant LIKE :pattern ESCAPE '\'
            OR categoryId IN (SELECT id FROM categories WHERE name LIKE :pattern ESCAPE '\')
          )
        ORDER BY transactionDate DESC, createdAt DESC
        """,
    )
    fun observeFiltered(startMillis: Long?, endMillis: Long?, pattern: String?): Flow<List<TransactionEntity>>
}
