package com.fintrack.app.domain.validation

sealed interface BudgetValidationError {
    data object AmountNotPositive : BudgetValidationError
    data object CategoryNotFound : BudgetValidationError
    /** Budgets limit spending, so only expense categories qualify. */
    data object CategoryNotExpense : BudgetValidationError
    data object CategoryArchived : BudgetValidationError
    /** One active budget per category, and one overall budget. */
    data object Duplicate : BudgetValidationError
}

sealed interface GoalValidationError {
    data object NameBlank : GoalValidationError
    data object TargetNotPositive : GoalValidationError
    data object CurrentNegative : GoalValidationError
}
