package com.fintrack.app.data.repository

import com.fintrack.app.analytics.metrics.BudgetAlert
import com.fintrack.app.analytics.metrics.BudgetAlertRules
import com.fintrack.app.analytics.metrics.BudgetCalculator
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.domain.model.TransactionType
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Receives budget alerts; the app posts system notifications, tests collect them. */
fun interface BudgetAlertSink {
    fun deliver(alerts: List<BudgetAlert>, categoryNames: Map<String, String>)
}

/** Computes which budgets a just-saved new expense pushed across a threshold. */
@Singleton
class BudgetAlertChecker @Inject constructor(
    private val planningRepository: PlanningRepository,
    private val reportRepository: ReportRepository,
    private val categoryRepository: CategoryRepository,
    private val sink: BudgetAlertSink,
    private val clock: Clock,
) {
    /** Only new expenses dated in the current Jakarta month can change this month's budgets. */
    suspend fun onExpenseAdded(type: TransactionType, categoryId: String?, amount: Long, date: Instant) {
        if (type != TransactionType.EXPENSE) return
        val today = JakartaTime.today(clock)
        val monthStart = today.withDayOfMonth(1)
        val day = JakartaTime.localDate(date)
        if (day.isBefore(monthStart) || day.isAfter(today)) return

        val budgets = planningRepository.observeBudgets().first()
        if (budgets.isEmpty()) return
        val categories = categoryRepository.observeAllCategories().first().associateBy { it.id }
        val range = JakartaTime.range(monthStart, today)
        val progress = BudgetCalculator.progress(
            budgets,
            reportRepository.observePeriodTotals(range).first().expense,
            reportRepository.observeExpenseByCategory(range).first().mapNotNull { e -> e.categoryId?.let { it to e.total } }.toMap(),
            categories,
        )
        val parentId = categoryId?.let { categories[it]?.parentId }
        val affected = budgets.filter { it.categoryId == null || it.categoryId == categoryId || (parentId != null && it.categoryId == parentId) }
            .map { it.id }.toSet()
        val alerts = BudgetAlertRules.afterNewExpense(progress, amount, affected)
        if (alerts.isNotEmpty()) sink.deliver(alerts, categories.mapValues { it.value.name })
    }
}
