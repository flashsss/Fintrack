package com.fintrack.app.presentation.transactions

import com.fintrack.app.domain.model.Category
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.Wallet
import com.fintrack.app.presentation.common.SelectOption
import com.fintrack.app.presentation.common.categoryLabel

/**
 * Choices offered by the transaction form. Mirrors the validator's archive rule: archived wallets
 * and categories are offered only when the edited transaction already uses them ([keepId]).
 */
object TransactionFormOptions {

    fun wallets(all: List<Wallet>, keepId: String?): List<SelectOption> =
        all.filter { it.isActive || it.id == keepId }
            .map { SelectOption(it.id, it.name, isArchived = !it.isActive) }

    fun categories(all: List<Category>, type: TransactionType, keepId: String?): List<SelectOption> {
        val categoryType = when (type) {
            TransactionType.INCOME -> CategoryType.INCOME
            TransactionType.EXPENSE -> CategoryType.EXPENSE
            TransactionType.TRANSFER -> return emptyList()
        }
        val byId = all.associateBy { it.id }
        return all.filter { it.type == categoryType && (!it.isArchived || it.id == keepId) }
            .map { SelectOption(it.id, categoryLabel(it, byId), isArchived = it.isArchived) }
            .sortedBy { it.label.lowercase() }
    }
}
