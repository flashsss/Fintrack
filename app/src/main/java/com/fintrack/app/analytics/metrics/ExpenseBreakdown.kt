package com.fintrack.app.analytics.metrics

/** Total expense of one category id; null id means expenses without a category. */
data class CategoryExpense(val categoryId: String?, val total: Long)

data class BreakdownSlice(
    /** Category id, null for uncategorized expenses; ignored when [isOthers]. */
    val categoryId: String?,
    val total: Long,
    /** Share of the period's total expense, 0..100. */
    val percent: Double,
    val isOthers: Boolean = false,
    /** Number of categories merged into an "others" slice. */
    val mergedCount: Int = 1,
)

object ExpenseBreakdown {

    const val TOP_COUNT = 5

    /**
     * Largest [TOP_COUNT] categories (ties broken by id for a stable order), then every remaining
     * category merged into one "others" slice. Empty when there is no positive expense, which also
     * avoids dividing by zero.
     */
    fun of(expenses: List<CategoryExpense>): List<BreakdownSlice> {
        val positive = expenses.filter { it.total > 0 }
        val sum = positive.sumOf { it.total }
        if (sum <= 0L) return emptyList()

        val sorted = positive.sortedWith(compareByDescending<CategoryExpense> { it.total }.thenBy { it.categoryId.orEmpty() })
        val top = sorted.take(TOP_COUNT).map { BreakdownSlice(it.categoryId, it.total, it.total * 100.0 / sum) }
        val rest = sorted.drop(TOP_COUNT)
        if (rest.isEmpty()) return top
        val restTotal = rest.sumOf { it.total }
        return top + BreakdownSlice(null, restTotal, restTotal * 100.0 / sum, isOthers = true, mergedCount = rest.size)
    }
}
