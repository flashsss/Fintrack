package com.fintrack.app.presentation.planning

import android.database.SQLException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintrack.app.analytics.metrics.BudgetCalculator
import com.fintrack.app.analytics.metrics.BudgetProgress
import com.fintrack.app.analytics.metrics.GoalProgress
import com.fintrack.app.core.common.TodayProvider
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.data.repository.CategoryRepository
import com.fintrack.app.data.repository.PlanningRepository
import com.fintrack.app.data.repository.ReportRepository
import com.fintrack.app.domain.model.Category
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class PlanningState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val month: YearMonth? = null,
    val budgets: List<BudgetProgress> = emptyList(),
    val goals: List<GoalProgress> = emptyList(),
    val categoriesById: Map<String, Category> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlanningViewModel @Inject constructor(
    planningRepository: PlanningRepository,
    reportRepository: ReportRepository,
    categoryRepository: CategoryRepository,
    todayProvider: TodayProvider,
) : ViewModel() {

    /** Budgets are measured against the current Jakarta calendar month, up to today. */
    val state: StateFlow<PlanningState> = todayProvider.today.flatMapLatest { today: LocalDate ->
        val range = JakartaTime.range(today.withDayOfMonth(1), today)
        combine(
            planningRepository.observeBudgets(),
            planningRepository.observeGoals(),
            reportRepository.observePeriodTotals(range),
            reportRepository.observeExpenseByCategory(range),
            categoryRepository.observeAllCategories(),
        ) { budgets, goals, totals, byCategory, categories ->
            val categoriesById = categories.associateBy { it.id }
            PlanningState(
                isLoading = false,
                month = YearMonth.from(today),
                budgets = BudgetCalculator.progress(
                    budgets, totals.expense, byCategory.mapNotNull { e -> e.categoryId?.let { it to e.total } }.toMap(), categoriesById,
                ),
                goals = goals.map { GoalProgress(it, today) },
                categoriesById = categoriesById,
            )
        }
    }
        .catch { error ->
            if (error !is SQLException) throw error
            emit(PlanningState(isLoading = false, loadFailed = true))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanningState())
}
