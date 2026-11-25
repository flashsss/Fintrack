package com.fintrack.app.data.repository

import androidx.room.withTransaction
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.data.local.dao.CategoryDao
import com.fintrack.app.data.local.dao.PlanningDao
import com.fintrack.app.data.local.database.FinTrackDatabase
import com.fintrack.app.data.local.entity.BudgetEntity
import com.fintrack.app.data.local.entity.GoalEntity
import com.fintrack.app.data.local.entity.toDomain
import com.fintrack.app.domain.model.Budget
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.FinancialGoal
import com.fintrack.app.domain.model.SyncStatus
import com.fintrack.app.domain.model.WriteResult
import com.fintrack.app.domain.validation.BudgetValidationError
import com.fintrack.app.domain.validation.GoalValidationError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Budgets and goals. Every write validates inside one Room transaction; deletes are soft. */
@Singleton
class PlanningRepository @Inject constructor(
    private val database: FinTrackDatabase,
    private val planningDao: PlanningDao,
    private val categoryDao: CategoryDao,
    private val clock: Clock,
) {
    fun observeBudgets(): Flow<List<Budget>> = planningDao.observeBudgets().map { rows -> rows.map { it.toDomain() } }

    fun observeGoals(): Flow<List<FinancialGoal>> = planningDao.observeGoals().map { rows -> rows.map { it.toDomain() } }

    suspend fun getBudget(id: String): Budget? = planningDao.getBudget(id)?.toDomain()

    suspend fun getGoal(id: String): FinancialGoal? = planningDao.getGoal(id)?.toDomain()

    suspend fun createBudget(categoryId: String?, amount: Long): WriteResult<BudgetValidationError> = database.withTransaction {
        val errors = validateBudget(categoryId, amount, editingId = null)
        if (errors.isNotEmpty()) return@withTransaction WriteResult.Invalid(errors)
        val now = clock.millis()
        val entity = BudgetEntity(UUID.randomUUID().toString(), categoryId, amount, now, now, null, SyncStatus.PENDING)
        planningDao.insertBudget(entity)
        WriteResult.Success(entity.id)
    }

    /** Only the amount can change; a budget for another category is a new budget. */
    suspend fun updateBudget(id: String, amount: Long): WriteResult<BudgetValidationError> = database.withTransaction {
        val existing = planningDao.getBudget(id) ?: return@withTransaction WriteResult.NotFound
        if (amount <= 0) return@withTransaction WriteResult.Invalid(listOf(BudgetValidationError.AmountNotPositive))
        planningDao.updateBudget(existing.copy(amount = amount, updatedAt = clock.millis(), syncStatus = SyncStatus.PENDING))
        WriteResult.Success(id)
    }

    suspend fun deleteBudget(id: String): WriteResult<BudgetValidationError> = database.withTransaction {
        val existing = planningDao.getBudget(id) ?: return@withTransaction WriteResult.NotFound
        val now = clock.millis()
        planningDao.updateBudget(existing.copy(deletedAt = now, updatedAt = now, syncStatus = SyncStatus.PENDING))
        WriteResult.Success(id)
    }

    private suspend fun validateBudget(categoryId: String?, amount: Long, editingId: String?): List<BudgetValidationError> {
        val errors = mutableListOf<BudgetValidationError>()
        if (amount <= 0) errors += BudgetValidationError.AmountNotPositive
        if (categoryId != null) {
            val category = categoryDao.getById(categoryId)
            when {
                category == null -> errors += BudgetValidationError.CategoryNotFound
                category.type != CategoryType.EXPENSE -> errors += BudgetValidationError.CategoryNotExpense
                category.isArchived -> errors += BudgetValidationError.CategoryArchived
            }
        }
        val duplicate = planningDao.findActiveBudget(categoryId)
        if (duplicate != null && duplicate.id != editingId) errors += BudgetValidationError.Duplicate
        return errors
    }

    suspend fun createGoal(name: String, target: Long, current: Long, targetDate: LocalDate?): WriteResult<GoalValidationError> {
        val errors = validateGoal(name, target, current)
        if (errors.isNotEmpty()) return WriteResult.Invalid(errors)
        val now = clock.millis()
        val entity = GoalEntity(UUID.randomUUID().toString(), name.trim(), target, current, targetDate?.let(::dateMillis), now, now, null, SyncStatus.PENDING)
        planningDao.insertGoal(entity)
        return WriteResult.Success(entity.id)
    }

    suspend fun updateGoal(id: String, name: String, target: Long, current: Long, targetDate: LocalDate?): WriteResult<GoalValidationError> =
        database.withTransaction {
            val existing = planningDao.getGoal(id) ?: return@withTransaction WriteResult.NotFound
            val errors = validateGoal(name, target, current)
            if (errors.isNotEmpty()) return@withTransaction WriteResult.Invalid(errors)
            planningDao.updateGoal(
                existing.copy(
                    name = name.trim(), targetAmount = target, currentAmount = current, targetDate = targetDate?.let(::dateMillis),
                    updatedAt = clock.millis(), syncStatus = SyncStatus.PENDING,
                ),
            )
            WriteResult.Success(id)
        }

    suspend fun deleteGoal(id: String): WriteResult<GoalValidationError> = database.withTransaction {
        val existing = planningDao.getGoal(id) ?: return@withTransaction WriteResult.NotFound
        val now = clock.millis()
        planningDao.updateGoal(existing.copy(deletedAt = now, updatedAt = now, syncStatus = SyncStatus.PENDING))
        WriteResult.Success(id)
    }

    private fun validateGoal(name: String, target: Long, current: Long) = buildList {
        if (name.isBlank()) add(GoalValidationError.NameBlank)
        if (target <= 0) add(GoalValidationError.TargetNotPositive)
        if (current < 0) add(GoalValidationError.CurrentNegative)
    }

    private fun dateMillis(date: LocalDate) = JakartaTime.range(date, date).start.toEpochMilli()
}
