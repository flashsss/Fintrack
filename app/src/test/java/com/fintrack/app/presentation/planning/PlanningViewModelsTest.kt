package com.fintrack.app.presentation.planning

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.R
import com.fintrack.app.analytics.metrics.BudgetLevel
import com.fintrack.app.core.common.MutableClock
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType
import com.fintrack.app.presentation.MainDispatcherRule
import com.fintrack.app.presentation.awaitValue
import com.fintrack.app.presentation.navigation.Routes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class PlanningViewModelsTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val t = TestDatabase()
    private lateinit var food: String
    private lateinit var cafe: String

    @Before
    fun setUp() = runBlocking {
        val bca = t.wallets.createWallet("BCA", WalletType.BANK, 0).requireId()
        val other = t.wallets.createWallet("Mandiri", WalletType.BANK, 0).requireId()
        food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        cafe = t.categories.createCategory("Kafe", CategoryType.EXPENSE, food, null, null).requireId()
        suspend fun add(type: TransactionType, category: String?, amount: Long, at: String, dest: String? = null) =
            t.transactions.addTransaction(TransactionDraft(type, bca, dest, category, amount, Instant.parse(at))).requireId()
        add(TransactionType.EXPENSE, food, 600_000, "2026-11-03T03:00:00Z")
        add(TransactionType.EXPENSE, cafe, 250_000, "2026-11-04T03:00:00Z")
        add(TransactionType.EXPENSE, food, 900_000, "2026-10-31T16:59:59Z") // 31 Oct WIB: previous month
        add(TransactionType.TRANSFER, null, 5_000_000, "2026-11-05T03:00:00Z", dest = other)
        Unit
    }

    @After
    fun tearDown() = t.close()

    private fun planning() = PlanningViewModel(t.planning, t.reports, t.categories, t.todayProvider(MutableClock(Instant.parse("2026-11-25T03:00:00Z"))))

    @Test
    fun `budget progress uses this Jakarta month, includes children and ignores transfers`() = runBlocking {
        t.planning.createBudget(food, 1_000_000).requireId()
        t.planning.createBudget(cafe, 200_000).requireId()
        t.planning.createBudget(null, 3_000_000).requireId()
        val state = planning().state.awaitValue { !it.isLoading && it.budgets.size == 3 }
        assertEquals(YearMonth.of(2026, 11), state.month)
        val byCategory = state.budgets.associateBy { it.budget.categoryId }
        assertEquals(850_000L, byCategory.getValue(food).spent)
        assertEquals(BudgetLevel.NEAR_LIMIT, byCategory.getValue(food).level)
        assertEquals(250_000L, byCategory.getValue(cafe).spent)
        assertEquals(BudgetLevel.OVER_LIMIT, byCategory.getValue(cafe).level)
        assertEquals(850_000L, byCategory.getValue(null).spent)
        assertEquals(listOf(cafe, food, null), state.budgets.map { it.budget.categoryId }) // most used first
    }

    @Test
    fun `goals show progress for today`() = runBlocking {
        t.planning.createGoal("Liburan", 12_000_000, 3_000_000, LocalDate.of(2027, 1, 31)).requireId()
        val goal = planning().state.awaitValue { it.goals.isNotEmpty() }.goals.single()
        assertEquals(3L, goal.monthsLeft)
        assertEquals(3_000_000L, goal.requiredPerMonth)
    }

    @Test
    fun `budget form creates, rejects duplicates, edits amount and soft-deletes`() = runBlocking {
        val form = BudgetFormViewModel(SavedStateHandle(), t.planning, t.categories)
        form.onCategoryChange(food)
        form.onAmountChange("1.000.000")
        form.save()
        assertTrue(form.state.awaitValue { it.status.isDone || it.categoryError != null }.status.isDone)

        val duplicate = BudgetFormViewModel(SavedStateHandle(), t.planning, t.categories)
        duplicate.onCategoryChange(food)
        duplicate.onAmountChange("5.000")
        duplicate.save()
        assertEquals(R.string.budget_error_duplicate, duplicate.state.awaitValue { it.categoryError != null }.categoryError)

        val empty = BudgetFormViewModel(SavedStateHandle(), t.planning, t.categories)
        empty.save()
        assertEquals(R.string.error_amount_required, empty.state.awaitValue { it.amountError != null }.amountError)

        val id = t.planning.observeBudgets().first().single().id
        val edit = BudgetFormViewModel(SavedStateHandle(mapOf(Routes.ARG_BUDGET_ID to id)), t.planning, t.categories)
        edit.state.awaitValue { !it.status.isLoading }
        edit.onCategoryChange(cafe) // ignored while editing
        edit.onAmountChange("1.500.000")
        edit.save()
        edit.state.awaitValue { it.status.isDone }
        assertEquals(1_500_000L, t.planning.getBudget(id)!!.amount)
        assertEquals(food, t.planning.getBudget(id)!!.categoryId)

        val delete = BudgetFormViewModel(SavedStateHandle(mapOf(Routes.ARG_BUDGET_ID to id)), t.planning, t.categories)
        delete.state.awaitValue { !it.status.isLoading }
        delete.delete()
        delete.state.awaitValue { it.status.isDone }
        assertTrue(t.planning.observeBudgets().first().isEmpty())
    }

    @Test
    fun `goal form validates, saves with optional deadline and edits`() = runBlocking {
        val form = GoalFormViewModel(SavedStateHandle(), t.planning)
        form.save()
        val invalid = form.state.awaitValue { it.nameError != null }
        assertEquals(R.string.goal_error_target, invalid.targetError)

        form.onNameChange("Dana darurat")
        form.onTargetChange("30.000.000")
        form.onTargetDateChange(LocalDate.of(2027, 6, 30))
        form.save()
        form.state.awaitValue { it.status.isDone }
        val goal = t.planning.observeGoals().first().single()
        assertEquals(0L, goal.currentAmount) // empty current amount = 0

        val edit = GoalFormViewModel(SavedStateHandle(mapOf(Routes.ARG_GOAL_ID to goal.id)), t.planning)
        assertEquals(LocalDate.of(2027, 6, 30), edit.state.awaitValue { !it.status.isLoading }.targetDate)
        edit.onCurrentChange("2.500.000")
        edit.onTargetDateChange(null)
        edit.save()
        edit.state.awaitValue { it.status.isDone }
        assertEquals(2_500_000L, t.planning.getGoal(goal.id)!!.currentAmount)
        assertEquals(null, t.planning.getGoal(goal.id)!!.targetDate)
    }
}
