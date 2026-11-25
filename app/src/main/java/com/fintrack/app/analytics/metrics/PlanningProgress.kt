package com.fintrack.app.analytics.metrics

import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.domain.model.Budget
import com.fintrack.app.domain.model.Category
import com.fintrack.app.domain.model.FinancialGoal
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

enum class BudgetLevel { OK, NEAR_LIMIT, OVER_LIMIT }

/** Budget use in one Jakarta month (formulas in docs/ENGINEERING_SPEC.md, Phase 5). */
data class BudgetProgress(val budget: Budget, val spent: Long) {
    /** spent / limit × 100. */
    val utilizationPercent: Double get() = spent * 100.0 / budget.amount
    /** limit − spent; negative when over budget. */
    val remaining: Long get() = budget.amount - spent
    val level: BudgetLevel
        get() = when {
            spent > budget.amount -> BudgetLevel.OVER_LIMIT
            utilizationPercent >= NEAR_LIMIT_PERCENT -> BudgetLevel.NEAR_LIMIT
            else -> BudgetLevel.OK
        }

    companion object {
        const val NEAR_LIMIT_PERCENT = 80.0
    }
}

object BudgetCalculator {
    /**
     * Spent per budget for a month. Overall budget = all expenses; a top-level category's budget
     * includes its children; a child category's budget counts only itself. Transfers never count.
     */
    fun progress(
        budgets: List<Budget>,
        monthExpense: Long,
        monthByCategory: Map<String, Long>,
        categoriesById: Map<String, Category>,
    ): List<BudgetProgress> {
        val rolledUp = CategoryAnalysis.rollUp(monthByCategory, categoriesById)
        return budgets.map { budget ->
            val categoryId = budget.categoryId
            val spent = when {
                categoryId == null -> monthExpense
                categoriesById[categoryId]?.parentId == null -> rolledUp[categoryId] ?: 0L
                else -> monthByCategory[categoryId] ?: 0L
            }
            BudgetProgress(budget, spent)
        }.sortedByDescending { it.utilizationPercent }
    }
}

/** Goal progress relative to [today] (Asia/Jakarta). */
data class GoalProgress(val goal: FinancialGoal, val today: LocalDate) {
    val percent: Double get() = goal.currentAmount * 100.0 / goal.targetAmount
    val remaining: Long get() = (goal.targetAmount - goal.currentAmount).coerceAtLeast(0)
    val isAchieved: Boolean get() = goal.currentAmount >= goal.targetAmount
    val targetDay: LocalDate? get() = goal.targetDate?.let(JakartaTime::localDate)
    val isOverdue: Boolean get() = !isAchieved && targetDay?.isBefore(today) == true

    /**
     * Calendar months left including the current one (target in this month = 1); null without a
     * deadline, when achieved or overdue.
     */
    val monthsLeft: Long?
        get() {
            val day = targetDay ?: return null
            if (isAchieved || day.isBefore(today)) return null
            return ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(day)) + 1
        }

    /** Remaining amount spread evenly over [monthsLeft], rounded up to whole rupiah. */
    val requiredPerMonth: Long? get() = monthsLeft?.let { months -> (remaining + months - 1) / months }
}
