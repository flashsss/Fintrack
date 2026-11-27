package com.fintrack.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.fintrack.app.data.local.entity.BudgetEntity
import com.fintrack.app.data.local.entity.CategoryEntity
import com.fintrack.app.data.local.entity.GoalEntity
import com.fintrack.app.data.local.entity.TransactionEntity
import com.fintrack.app.data.local.entity.WalletEntity

/**
 * Cloud sync access (Phase 6). Pending rows are read in a stable order; marking a row as synced
 * is a compare-and-set on updatedAt so an edit made while the row was uploading stays pending.
 * Writes from the cloud use @Upsert (insert or update in place), never REPLACE, which would
 * delete the row and break foreign keys.
 */
@Dao
interface SyncDao {
    @Query("SELECT * FROM wallets WHERE syncStatus = 'PENDING' ORDER BY id LIMIT :limit")
    suspend fun pendingWallets(limit: Int): List<WalletEntity>

    /** Parents before subcategories, so the server's foreign key on parent_id holds. */
    @Query("SELECT * FROM categories WHERE syncStatus = 'PENDING' ORDER BY parentId IS NOT NULL, id LIMIT :limit")
    suspend fun pendingCategories(limit: Int): List<CategoryEntity>

    @Query("SELECT * FROM transactions WHERE syncStatus = 'PENDING' ORDER BY id LIMIT :limit")
    suspend fun pendingTransactions(limit: Int): List<TransactionEntity>

    @Query("SELECT * FROM budgets WHERE syncStatus = 'PENDING' ORDER BY id LIMIT :limit")
    suspend fun pendingBudgets(limit: Int): List<BudgetEntity>

    @Query("SELECT * FROM financial_goals WHERE syncStatus = 'PENDING' ORDER BY id LIMIT :limit")
    suspend fun pendingGoals(limit: Int): List<GoalEntity>

    @Query("UPDATE wallets SET syncStatus = 'SYNCED' WHERE id = :id AND updatedAt = :updatedAt AND syncStatus = 'PENDING'")
    suspend fun markWalletSynced(id: String, updatedAt: Long): Int

    @Query("UPDATE categories SET syncStatus = 'SYNCED' WHERE id = :id AND updatedAt = :updatedAt AND syncStatus = 'PENDING'")
    suspend fun markCategorySynced(id: String, updatedAt: Long): Int

    @Query("UPDATE transactions SET syncStatus = 'SYNCED' WHERE id = :id AND updatedAt = :updatedAt AND syncStatus = 'PENDING'")
    suspend fun markTransactionSynced(id: String, updatedAt: Long): Int

    @Query("UPDATE budgets SET syncStatus = 'SYNCED' WHERE id = :id AND updatedAt = :updatedAt AND syncStatus = 'PENDING'")
    suspend fun markBudgetSynced(id: String, updatedAt: Long): Int

    @Query("UPDATE financial_goals SET syncStatus = 'SYNCED' WHERE id = :id AND updatedAt = :updatedAt AND syncStatus = 'PENDING'")
    suspend fun markGoalSynced(id: String, updatedAt: Long): Int

    @Query("SELECT * FROM wallets WHERE id = :id")
    suspend fun wallet(id: String): WalletEntity?

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun category(id: String): CategoryEntity?

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun transaction(id: String): TransactionEntity?

    @Query("SELECT * FROM budgets WHERE id = :id")
    suspend fun budget(id: String): BudgetEntity?

    @Query("SELECT * FROM financial_goals WHERE id = :id")
    suspend fun goal(id: String): GoalEntity?

    @Upsert suspend fun upsertWallet(entity: WalletEntity)
    @Upsert suspend fun upsertCategory(entity: CategoryEntity)
    @Upsert suspend fun upsertTransaction(entity: TransactionEntity)
    @Upsert suspend fun upsertBudget(entity: BudgetEntity)
    @Upsert suspend fun upsertGoal(entity: GoalEntity)

    @Query(
        """
        SELECT (SELECT COUNT(*) FROM wallets WHERE syncStatus = 'PENDING')
             + (SELECT COUNT(*) FROM categories WHERE syncStatus = 'PENDING')
             + (SELECT COUNT(*) FROM transactions WHERE syncStatus = 'PENDING')
             + (SELECT COUNT(*) FROM budgets WHERE syncStatus = 'PENDING')
             + (SELECT COUNT(*) FROM financial_goals WHERE syncStatus = 'PENDING')
        """,
    )
    fun observePendingCount(): kotlinx.coroutines.flow.Flow<Int>
}
