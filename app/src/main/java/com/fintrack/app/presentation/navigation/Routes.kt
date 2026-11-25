package com.fintrack.app.presentation.navigation

import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionType

/** Navigation routes. Arguments are read by ViewModels through SavedStateHandle. */
object Routes {
    const val ARG_TRANSACTION_ID = "transactionId"
    const val ARG_WALLET_ID = "walletId"
    const val ARG_CATEGORY_ID = "categoryId"
    const val ARG_TYPE = "type"
    const val ARG_BUDGET_ID = "budgetId"
    const val ARG_GOAL_ID = "goalId"

    const val HOME = "home"
    const val TRANSACTIONS = "transactions"
    const val ANALYTICS = "analytics"
    const val WALLETS = "wallets"
    const val SETTINGS = "settings"

    const val TRANSACTION_NEW = "transaction/new?$ARG_TYPE={$ARG_TYPE}"
    const val TRANSACTION_DETAIL = "transaction/{$ARG_TRANSACTION_ID}"
    const val TRANSACTION_EDIT = "transaction/{$ARG_TRANSACTION_ID}/edit"

    const val WALLET_NEW = "wallet/new"
    const val WALLET_DETAIL = "wallet/{$ARG_WALLET_ID}"
    const val WALLET_EDIT = "wallet/{$ARG_WALLET_ID}/edit"

    const val CATEGORIES = "categories"
    const val CATEGORY_NEW = "category/new?$ARG_TYPE={$ARG_TYPE}"
    const val CATEGORY_EDIT = "category/{$ARG_CATEGORY_ID}/edit"

    const val PLANNING = "planning"
    const val BUDGET_NEW = "budget/new"
    const val BUDGET_EDIT = "budget/{$ARG_BUDGET_ID}/edit"
    const val GOAL_NEW = "goal/new"
    const val GOAL_EDIT = "goal/{$ARG_GOAL_ID}/edit"

    fun budgetEdit(id: String) = "budget/$id/edit"
    fun goalEdit(id: String) = "goal/$id/edit"

    fun transactionNew(type: TransactionType? = null) =
        if (type == null) "transaction/new" else "transaction/new?$ARG_TYPE=${type.name}"

    fun transactionDetail(id: String) = "transaction/$id"
    fun transactionEdit(id: String) = "transaction/$id/edit"
    fun walletDetail(id: String) = "wallet/$id"
    fun walletEdit(id: String) = "wallet/$id/edit"
    fun categoryNew(type: CategoryType) = "category/new?$ARG_TYPE=${type.name}"
    fun categoryEdit(id: String) = "category/$id/edit"
}
