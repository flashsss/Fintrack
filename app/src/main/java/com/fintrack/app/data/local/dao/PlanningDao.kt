package com.fintrack.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fintrack.app.data.local.entity.BudgetEntity
import com.fintrack.app.data.local.entity.GoalEntity
import kotlinx.coroutines.flow.Flow

/** Budgets and financial goals (schema version 2). Rows are soft-deleted via deletedAt. */
@Dao
interface PlanningDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBudget(budget: BudgetEntity)

    @Update
    suspend fun updateBudget(budget: BudgetEntity)

    @Query("SELECT * FROM budgets WHERE id = :id AND deletedAt IS NULL")
    suspend fun getBudget(id: String): BudgetEntity?

    @Query("SELECT * FROM budgets WHERE deletedAt IS NULL")
    fun observeBudgets(): Flow<List<BudgetEntity>>

    /** Active budget for a category, or the overall budget when [categoryId] is null. */
    @Query("SELECT * FROM budgets WHERE deletedAt IS NULL AND categoryId IS :categoryId LIMIT 1")
    suspend fun findActiveBudget(categoryId: String?): BudgetEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGoal(goal: GoalEntity)

    @Update
    suspend fun updateGoal(goal: GoalEntity)

    @Query("SELECT * FROM financial_goals WHERE id = :id AND deletedAt IS NULL")
    suspend fun getGoal(id: String): GoalEntity?

    @Query("SELECT * FROM financial_goals WHERE deletedAt IS NULL ORDER BY createdAt")
    fun observeGoals(): Flow<List<GoalEntity>>
}
