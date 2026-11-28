package com.fintrack.app.presentation.common

import androidx.annotation.StringRes
import com.fintrack.app.R
import com.fintrack.app.core.util.AmountInput
import com.fintrack.app.data.repository.WalletValidationError
import com.fintrack.app.domain.validation.CategoryValidationError
import com.fintrack.app.domain.validation.TransactionValidationError

/** Message for an amount that could not be parsed, or null when it parsed. */
@StringRes
fun AmountInput.Result.errorRes(): Int? = when (this) {
    is AmountInput.Result.Valid -> null
    AmountInput.Result.Empty -> R.string.error_amount_required
    AmountInput.Result.Invalid -> R.string.error_amount_invalid
    AmountInput.Result.TooLarge -> R.string.error_amount_too_large
}

@get:StringRes
val TransactionValidationError.messageRes: Int
    get() = when (this) {
        TransactionValidationError.AmountNotPositive -> R.string.error_amount_not_positive
        TransactionValidationError.SourceWalletNotFound -> R.string.error_wallet_required
        TransactionValidationError.SourceWalletArchived -> R.string.error_wallet_archived
        TransactionValidationError.DestinationWalletRequired -> R.string.error_destination_required
        TransactionValidationError.DestinationWalletNotFound -> R.string.error_destination_required
        TransactionValidationError.DestinationWalletArchived -> R.string.error_wallet_archived
        TransactionValidationError.DestinationWalletNotAllowed -> R.string.error_destination_not_allowed
        TransactionValidationError.SameSourceAndDestination -> R.string.error_destination_same
        TransactionValidationError.CategoryRequired -> R.string.error_category_required
        TransactionValidationError.CategoryNotFound -> R.string.error_category_required
        TransactionValidationError.CategoryNotAllowedForTransfer -> R.string.error_category_transfer
        TransactionValidationError.CategoryTypeMismatch -> R.string.error_category_type
        TransactionValidationError.CategoryArchived -> R.string.error_category_archived
    }

@get:StringRes
val CategoryValidationError.messageRes: Int
    get() = when (this) {
        CategoryValidationError.NameBlank -> R.string.error_name_required
        CategoryValidationError.ParentNotFound -> R.string.error_parent_not_found
        CategoryValidationError.ParentArchived -> R.string.error_parent_archived
        CategoryValidationError.ParentTypeMismatch -> R.string.error_parent_type
        CategoryValidationError.ParentIsChildCategory -> R.string.error_parent_is_child
        CategoryValidationError.ParentIsSelf -> R.string.error_parent_self
        CategoryValidationError.CategoryHasChildren -> R.string.error_category_has_children
    }

@get:StringRes
val WalletValidationError.messageRes: Int
    get() = when (this) {
        WalletValidationError.NameBlank -> R.string.error_name_required
        is WalletValidationError.HasTransactions -> R.string.wallet_delete_blocked
    }
