package com.fintrack.app.domain.model

import java.time.Instant

/** Monthly limit in integer rupiah; [categoryId] null means all expenses. */
data class Budget(val id: String, val categoryId: String?, val amount: Long)

data class FinancialGoal(
    val id: String,
    val name: String,
    val targetAmount: Long,
    val currentAmount: Long,
    /** Jakarta midnight of the target day, or null when the goal has no deadline. */
    val targetDate: Instant?,
)
