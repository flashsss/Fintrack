package com.fintrack.app.analytics.insights

import com.fintrack.app.analytics.metrics.CategoryTotal
import com.fintrack.app.analytics.metrics.PeriodTotals
import com.fintrack.app.analytics.metrics.WeekdayPattern

/**
 * Deterministic insight rules (docs/PHASE_4_TECHNICAL_DESIGN.md §5.6). Every threshold is a named
 * constant here; every rule reports whether it fired, found nothing, or lacked data and why.
 */
object InsightRules {
    // I1 — category spending increase
    const val I1_MIN_PREVIOUS = 100_000L
    const val I1_MIN_COUNT = 3
    const val I1_MIN_PERCENT = 30.0
    const val I1_MIN_ABSOLUTE = 200_000L
    const val I1_MAX_RESULTS = 3

    // I2 — weekend vs weekday daily average
    const val I2_MIN_WEEKEND_DAYS = 4
    const val I2_MIN_WEEKDAY_DAYS = 10
    const val I2_MIN_EXPENSES = 20
    const val I2_MIN_RATIO = 1.5

    // I5 — saving-rate drop
    const val I5_MIN_PREVIOUS_INCOME = 1_000_000L
    const val I5_MIN_DROP_POINTS = 10.0

    fun categoryIncreases(current: List<CategoryTotal>, previous: Map<String, Long>): RuleOutcome<List<Insight.CategoryIncrease>> {
        val eligible = current.filter { (previous[it.categoryId] ?: 0L) >= I1_MIN_PREVIOUS && it.count >= I1_MIN_COUNT }
        if (eligible.isEmpty()) return RuleOutcome.NotEnoughData(Reason.NO_ELIGIBLE_CATEGORY)
        val fired = eligible.mapNotNull { total ->
            val before = previous.getValue(total.categoryId)
            val absolute = total.total - before
            val percent = absolute * 100.0 / before
            if (percent >= I1_MIN_PERCENT && absolute >= I1_MIN_ABSOLUTE) {
                Insight.CategoryIncrease(total.categoryId, total.total, before, total.count, percent)
            } else {
                null
            }
        }.sortedWith(compareByDescending<Insight.CategoryIncrease> { it.current - it.previous }.thenBy { it.categoryId })
            .take(I1_MAX_RESULTS)
        return if (fired.isEmpty()) RuleOutcome.NoFinding else RuleOutcome.Fired(fired)
    }

    fun weekendSpending(pattern: WeekdayPattern): RuleOutcome<Insight.WeekendSpending> {
        if (pattern.weekendDays < I2_MIN_WEEKEND_DAYS || pattern.weekdayDays < I2_MIN_WEEKDAY_DAYS) {
            return RuleOutcome.NotEnoughData(Reason.SHORT_PERIOD)
        }
        if (pattern.expenseCount < I2_MIN_EXPENSES) return RuleOutcome.NotEnoughData(Reason.FEW_EXPENSES)
        val weekend = pattern.weekendDailyAverage!!
        val weekday = pattern.weekdayDailyAverage!!
        if (weekend == 0.0 || weekday == 0.0) return RuleOutcome.NotEnoughData(Reason.ONE_SIDED_SPENDING)
        val ratio = weekend / weekday
        return when {
            ratio >= I2_MIN_RATIO -> RuleOutcome.Fired(Insight.WeekendSpending(weekend, weekday, ratio))
            ratio <= 1 / I2_MIN_RATIO -> RuleOutcome.Fired(Insight.WeekendSpending(weekend, weekday, ratio))
            else -> RuleOutcome.NoFinding
        }
    }

    fun savingRateDrop(current: PeriodTotals, previous: PeriodTotals): RuleOutcome<Insight.SavingRateDrop> {
        if (current.income <= 0L) return RuleOutcome.NotEnoughData(Reason.NO_CURRENT_INCOME)
        if (previous.income < I5_MIN_PREVIOUS_INCOME) return RuleOutcome.NotEnoughData(Reason.LOW_PREVIOUS_INCOME)
        val now = current.savingRatePercent!!
        val before = previous.savingRatePercent!!
        return if (now - before <= -I5_MIN_DROP_POINTS) RuleOutcome.Fired(Insight.SavingRateDrop(now, before)) else RuleOutcome.NoFinding
    }
}

sealed interface Insight {
    data class CategoryIncrease(val categoryId: String, val current: Long, val previous: Long, val count: Int, val percent: Double) : Insight
    /** [ratio] = weekend daily average / weekday daily average; above 1 means weekends cost more. */
    data class WeekendSpending(val weekendDailyAverage: Double, val weekdayDailyAverage: Double, val ratio: Double) : Insight

    data class SavingRateDrop(val currentPercent: Double, val previousPercent: Double) : Insight {
        val pointChange: Double get() = currentPercent - previousPercent
    }
}

/** Why a rule could not be evaluated. */
enum class Reason { NO_ELIGIBLE_CATEGORY, NO_CURRENT_INCOME, LOW_PREVIOUS_INCOME, SHORT_PERIOD, FEW_EXPENSES, ONE_SIDED_SPENDING }

sealed interface RuleOutcome<out T> {
    data class Fired<T>(val value: T) : RuleOutcome<T>
    /** Enough data, but the threshold was not reached. */
    data object NoFinding : RuleOutcome<Nothing>
    data class NotEnoughData(val reason: Reason) : RuleOutcome<Nothing>
}
