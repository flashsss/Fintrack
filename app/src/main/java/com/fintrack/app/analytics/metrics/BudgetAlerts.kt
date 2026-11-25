package com.fintrack.app.analytics.metrics

enum class BudgetAlertKind { NEAR_LIMIT, OVER_LIMIT }

/** A budget whose threshold was crossed by one new expense. */
data class BudgetAlert(val budgetId: String, val categoryId: String?, val kind: BudgetAlertKind, val spent: Long, val limit: Long)

object BudgetAlertRules {
    /**
     * The highest threshold crossed when spending moves from [before] to [after] against [limit]:
     * over-limit when before ≤ limit < after; near-limit when before < 80% ≤ after (and not over).
     * Comparisons use integer arithmetic so exactly 80% counts as reached.
     */
    fun crossed(before: Long, after: Long, limit: Long): BudgetAlertKind? = when {
        before <= limit && after > limit -> BudgetAlertKind.OVER_LIMIT
        before * 100 < limit * NEAR_PERCENT && after * 100 >= limit * NEAR_PERCENT && after <= limit -> BudgetAlertKind.NEAR_LIMIT
        else -> null
    }

    /**
     * Alerts caused by adding [amount] to [categoryId]: the progress after the write minus that
     * amount gives the spending before it, for every budget the expense counts towards.
     */
    fun afterNewExpense(progressAfter: List<BudgetProgress>, amount: Long, affectedBudgetIds: Set<String>): List<BudgetAlert> =
        progressAfter.filter { it.budget.id in affectedBudgetIds }.mapNotNull { progress ->
            crossed(progress.spent - amount, progress.spent, progress.budget.amount)?.let {
                BudgetAlert(progress.budget.id, progress.budget.categoryId, it, progress.spent, progress.budget.amount)
            }
        }

    private const val NEAR_PERCENT = 80L
}
