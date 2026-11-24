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
}
