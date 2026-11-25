package com.fintrack.app.analytics

import com.fintrack.app.analytics.metrics.BudgetCalculator
import com.fintrack.app.analytics.metrics.BudgetLevel
import com.fintrack.app.analytics.metrics.BudgetProgress
import com.fintrack.app.analytics.metrics.GoalProgress
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.domain.model.Budget
import com.fintrack.app.domain.model.Category
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.FinancialGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class PlanningProgressTest {

    private val now = Instant.EPOCH
    private val food = Category("food", "Makanan", CategoryType.EXPENSE, null, null, null, false, false, now, now)
    private val cafe = Category("cafe", "Kafe", CategoryType.EXPENSE, "food", null, null, false, false, now, now)
    private val byId = listOf(food, cafe).associateBy { it.id }

    @Test
    fun `budget levels at 80 and 100 percent`() {
        assertEquals(BudgetLevel.OK, BudgetProgress(Budget("b", null, 1_000_000), 799_999).level)
        assertEquals(BudgetLevel.NEAR_LIMIT, BudgetProgress(Budget("b", null, 1_000_000), 800_000).level)
        assertEquals(BudgetLevel.NEAR_LIMIT, BudgetProgress(Budget("b", null, 1_000_000), 1_000_000).level)
        val over = BudgetProgress(Budget("b", null, 1_000_000), 1_250_000)
        assertEquals(BudgetLevel.OVER_LIMIT, over.level)
        assertEquals(-250_000L, over.remaining)
        assertEquals(125.0, over.utilizationPercent, 1e-9)
    }

    @Test
    fun `parent budgets include children, child budgets only themselves, overall uses all expense`() {
        val progress = BudgetCalculator.progress(
            listOf(Budget("all", null, 2_000_000), Budget("p", "food", 600_000), Budget("c", "cafe", 100_000)),
            monthExpense = 900_000,
            monthByCategory = mapOf("food" to 400_000, "cafe" to 150_000),
            categoriesById = byId,
        ).associateBy { it.budget.id }
        assertEquals(900_000L, progress.getValue("all").spent)
        assertEquals(550_000L, progress.getValue("p").spent)
        assertEquals(150_000L, progress.getValue("c").spent)
    }

    private fun goal(target: Long, current: Long, date: LocalDate?) =
        FinancialGoal("g", "Dana darurat", target, current, date?.let { JakartaTime.range(it, it).start })

    @Test
    fun `goal progress and required monthly saving`() {
        val today = LocalDate.of(2026, 11, 25)
        val g = GoalProgress(goal(30_000_000, 6_000_000, LocalDate.of(2027, 4, 30)), today)
        assertEquals(20.0, g.percent, 1e-9)
        assertEquals(24_000_000L, g.remaining)
        assertEquals(6L, g.monthsLeft) // Nov, Dec, Jan, Feb, Mar, Apr
        assertEquals(4_000_000L, g.requiredPerMonth)
        assertEquals(1L, GoalProgress(goal(100, 0, today), today).monthsLeft)
        assertEquals(100L, GoalProgress(goal(100, 0, LocalDate.of(2026, 11, 30)), today).requiredPerMonth) // one month left
    }

    @Test
    fun `achieved, overdue and open-ended goals`() {
        val today = LocalDate.of(2026, 11, 25)
        val achieved = GoalProgress(goal(1_000, 1_500, LocalDate.of(2026, 1, 1)), today)
        assertTrue(achieved.isAchieved)
        assertEquals(0L, achieved.remaining)
        assertTrue(!achieved.isOverdue)
        val overdue = GoalProgress(goal(1_000, 500, LocalDate.of(2026, 11, 24)), today)
        assertTrue(overdue.isOverdue)
        assertNull(overdue.requiredPerMonth)
        assertNull(GoalProgress(goal(1_000, 500, null), today).monthsLeft)
        assertEquals(34L, GoalProgress(goal(100, 0, LocalDate.of(2027, 1, 31)), today).requiredPerMonth) // ceil(100 / 3)
    }
}
