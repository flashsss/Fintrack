package com.fintrack.app.presentation.planning

import android.database.SQLException
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintrack.app.R
import com.fintrack.app.core.util.AmountInput
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.data.repository.CategoryRepository
import com.fintrack.app.data.repository.PlanningRepository
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.WriteResult
import com.fintrack.app.domain.validation.BudgetValidationError
import com.fintrack.app.domain.validation.GoalValidationError
import com.fintrack.app.presentation.common.SelectOption
import com.fintrack.app.presentation.common.categoryLabel
import com.fintrack.app.presentation.common.errorRes
import com.fintrack.app.presentation.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** Shared form flags for budget and goal forms. */
data class FormStatus(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isDone: Boolean = false,
    val notFound: Boolean = false,
    @StringRes val generalError: Int? = null,
)

data class BudgetFormState(
    val isEdit: Boolean = false,
    /** "" = all expenses (overall budget). */
    val categoryId: String = "",
    val amountText: String = "",
    @StringRes val categoryError: Int? = null,
    @StringRes val amountError: Int? = null,
    val status: FormStatus = FormStatus(),
)

@HiltViewModel
class BudgetFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val planningRepository: PlanningRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {
    private val budgetId: String? = savedStateHandle[Routes.ARG_BUDGET_ID]
    private val _state = MutableStateFlow(BudgetFormState(isEdit = budgetId != null, status = FormStatus(isLoading = budgetId != null)))
    val state: StateFlow<BudgetFormState> = _state.asStateFlow()

    /** Active expense categories; an overall budget option is added by the screen. */
    val categoryOptions: StateFlow<List<SelectOption>> = categoryRepository.observeAllCategories().map { all ->
        val byId = all.associateBy { it.id }
        all.filter { it.type == CategoryType.EXPENSE && !it.isArchived }
            .map { SelectOption(it.id, categoryLabel(it, byId)) }.sortedBy { it.label.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (budgetId != null) viewModelScope.launch {
            val budget = planningRepository.getBudget(budgetId)
            _state.update {
                if (budget == null) it.copy(status = FormStatus(notFound = true))
                else it.copy(categoryId = budget.categoryId.orEmpty(), amountText = AmountInput.textFor(budget.amount), status = FormStatus())
            }
        }
    }

    fun onCategoryChange(id: String) = _state.update { if (it.isEdit) it else it.copy(categoryId = id, categoryError = null) }
    fun onAmountChange(text: String) = _state.update { it.copy(amountText = text, amountError = null) }

    fun save() {
        val form = _state.value
        if (form.status.isLoading || form.status.isSaving || form.status.isDone) return
        val parsed = AmountInput.parse(form.amountText)
        val amount = (parsed as? AmountInput.Result.Valid)?.amount ?: 0L
        _state.update { it.copy(status = it.status.copy(isSaving = true, generalError = null)) }
        viewModelScope.launch {
            try {
                val result = if (budgetId == null) planningRepository.createBudget(form.categoryId.ifEmpty { null }, amount)
                else planningRepository.updateBudget(budgetId, amount)
                _state.update { current ->
                    when (result) {
                        is WriteResult.Success -> current.copy(status = FormStatus(isDone = true))
                        WriteResult.NotFound -> current.copy(status = FormStatus(notFound = true))
                        is WriteResult.Invalid -> current.copy(
                            status = FormStatus(),
                            amountError = parsed.errorRes() ?: R.string.error_amount_not_positive.takeIf { BudgetValidationError.AmountNotPositive in result.errors },
                            categoryError = result.errors.firstOrNull { it != BudgetValidationError.AmountNotPositive }?.let {
                                when (it) {
                                    BudgetValidationError.Duplicate -> R.string.budget_error_duplicate
                                    BudgetValidationError.CategoryArchived -> R.string.error_category_archived
                                    else -> R.string.error_category_type
                                }
                            },
                        )
                    }
                }
            } catch (error: SQLException) {
                _state.update { it.copy(status = FormStatus(generalError = R.string.error_save_failed)) }
            }
        }
    }

    fun delete() {
        val id = budgetId ?: return
        viewModelScope.launch {
            try {
                planningRepository.deleteBudget(id)
                _state.update { it.copy(status = FormStatus(isDone = true)) }
            } catch (error: SQLException) {
                _state.update { it.copy(status = FormStatus(generalError = R.string.error_save_failed)) }
            }
        }
    }
}

data class GoalFormState(
    val isEdit: Boolean = false,
    val name: String = "",
    val targetText: String = "",
    val currentText: String = "",
    val targetDate: LocalDate? = null,
    @StringRes val nameError: Int? = null,
    @StringRes val targetError: Int? = null,
    @StringRes val currentError: Int? = null,
    val status: FormStatus = FormStatus(),
)

@HiltViewModel
class GoalFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val planningRepository: PlanningRepository,
) : ViewModel() {
    private val goalId: String? = savedStateHandle[Routes.ARG_GOAL_ID]
    private val _state = MutableStateFlow(GoalFormState(isEdit = goalId != null, status = FormStatus(isLoading = goalId != null)))
    val state: StateFlow<GoalFormState> = _state.asStateFlow()

    init {
        if (goalId != null) viewModelScope.launch {
            val goal = planningRepository.getGoal(goalId)
            _state.update {
                if (goal == null) it.copy(status = FormStatus(notFound = true))
                else it.copy(
                    name = goal.name,
                    targetText = AmountInput.textFor(goal.targetAmount),
                    currentText = AmountInput.textFor(goal.currentAmount),
                    targetDate = goal.targetDate?.let(JakartaTime::localDate),
                    status = FormStatus(),
                )
            }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value, nameError = null) }
    fun onTargetChange(value: String) = _state.update { it.copy(targetText = value, targetError = null) }
    fun onCurrentChange(value: String) = _state.update { it.copy(currentText = value, currentError = null) }
    fun onTargetDateChange(value: LocalDate?) = _state.update { it.copy(targetDate = value) }

    fun save() {
        val form = _state.value
        if (form.status.isLoading || form.status.isSaving || form.status.isDone) return
        val target = AmountInput.parse(form.targetText)
        // An empty current amount means nothing has been saved yet.
        val current = AmountInput.parse(form.currentText.ifBlank { "0" })
        val targetParseError = target.errorRes().takeIf { target !is AmountInput.Result.Empty }
        if (targetParseError != null || current !is AmountInput.Result.Valid) {
            _state.update { it.copy(targetError = targetParseError, currentError = current.errorRes()) }
            return
        }
        // An empty target becomes 0, which the repository rejects as TargetNotPositive.
        val targetAmount = (target as? AmountInput.Result.Valid)?.amount ?: 0L
        _state.update { it.copy(status = it.status.copy(isSaving = true, generalError = null)) }
        viewModelScope.launch {
            try {
                val result = if (goalId == null) planningRepository.createGoal(form.name, targetAmount, current.amount, form.targetDate)
                else planningRepository.updateGoal(goalId, form.name, targetAmount, current.amount, form.targetDate)
                _state.update { state ->
                    when (result) {
                        is WriteResult.Success -> state.copy(status = FormStatus(isDone = true))
                        WriteResult.NotFound -> state.copy(status = FormStatus(notFound = true))
                        is WriteResult.Invalid -> state.copy(
                            status = FormStatus(),
                            nameError = R.string.error_name_required.takeIf { GoalValidationError.NameBlank in result.errors },
                            targetError = R.string.goal_error_target.takeIf { GoalValidationError.TargetNotPositive in result.errors },
                        )
                    }
                }
            } catch (error: SQLException) {
                _state.update { it.copy(status = FormStatus(generalError = R.string.error_save_failed)) }
            }
        }
    }

    fun delete() {
        val id = goalId ?: return
        viewModelScope.launch {
            try {
                planningRepository.deleteGoal(id)
                _state.update { it.copy(status = FormStatus(isDone = true)) }
            } catch (error: SQLException) {
                _state.update { it.copy(status = FormStatus(generalError = R.string.error_save_failed)) }
            }
        }
    }
}
