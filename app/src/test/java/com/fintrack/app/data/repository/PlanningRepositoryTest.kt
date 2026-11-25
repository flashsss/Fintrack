package com.fintrack.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.WriteResult
import com.fintrack.app.domain.validation.BudgetValidationError
import com.fintrack.app.domain.validation.GoalValidationError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PlanningRepositoryTest {

    private val t = TestDatabase()

    @After
    fun tearDown() = t.close()

    private fun category(name: String, type: CategoryType = CategoryType.EXPENSE) =
        runBlocking { t.categories.createCategory(name, type, null, null, null).requireId() }

    @Test
    fun `budgets validate amount, category type, archive state and duplicates`() = runBlocking {
        val food = category("Makanan")
        val salary = category("Gaji", CategoryType.INCOME)
        val old = category("Lama").also { t.categories.archiveCategory(it).requireId() }

        t.planning.createBudget(food, 1_500_000).requireId()
        t.planning.createBudget(null, 5_000_000).requireId()
        assertEquals(WriteResult.Invalid(listOf(BudgetValidationError.Duplicate)), t.planning.createBudget(food, 1))
        assertEquals(WriteResult.Invalid(listOf(BudgetValidationError.Duplicate)), t.planning.createBudget(null, 1))
        assertEquals(WriteResult.Invalid(listOf(BudgetValidationError.AmountNotPositive)), t.planning.createBudget(category("Baru"), 0))
        assertEquals(WriteResult.Invalid(listOf(BudgetValidationError.CategoryNotExpense)), t.planning.createBudget(salary, 10))
        assertEquals(WriteResult.Invalid(listOf(BudgetValidationError.CategoryArchived)), t.planning.createBudget(old, 10))
        assertEquals(WriteResult.Invalid(listOf(BudgetValidationError.CategoryNotFound)), t.planning.createBudget("missing", 10))
        assertEquals(2, t.planning.observeBudgets().first().size)
    }

    @Test
    fun `deleted budget is soft-deleted and frees its category`() = runBlocking {
        val food = category("Makanan")
        val id = t.planning.createBudget(food, 1_000_000).requireId()
        t.planning.updateBudget(id, 1_200_000).requireId()
        assertEquals(1_200_000L, t.planning.getBudget(id)!!.amount)
        t.planning.deleteBudget(id).requireId()
        assertNull(t.planning.getBudget(id))
        t.planning.createBudget(food, 900_000).requireId()
        t.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM budgets").use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
    }

    @Test
    fun `goals validate and store the target date as a Jakarta midnight`() = runBlocking {
        assertEquals(
            WriteResult.Invalid(listOf(GoalValidationError.NameBlank, GoalValidationError.TargetNotPositive, GoalValidationError.CurrentNegative)),
            t.planning.createGoal(" ", 0, -1, null),
        )
        val id = t.planning.createGoal("Dana darurat", 30_000_000, 5_000_000, LocalDate.of(2027, 6, 30)).requireId()
        val goal = t.planning.getGoal(id)!!
        assertEquals("2027-06-29T17:00:00Z", goal.targetDate.toString())
        t.planning.updateGoal(id, "Dana darurat", 30_000_000, 7_500_000, null).requireId()
        assertEquals(7_500_000L, t.planning.getGoal(id)!!.currentAmount)
        assertNull(t.planning.getGoal(id)!!.targetDate)
        t.planning.deleteGoal(id).requireId()
        assertEquals(0, t.planning.observeGoals().first().size)
    }
}
