package com.fintrack.app.domain.validation

import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionType

/** Minimal view of a wallet needed for validation. Null reference means missing or soft-deleted. */
data class WalletRef(val id: String, val isActive: Boolean)

/** Minimal view of a category needed for validation. Null reference means missing or soft-deleted. */
data class CategoryRef(val id: String, val type: CategoryType, val isArchived: Boolean)

/** References already used by the transaction being edited. Archived references may be kept. */
data class ExistingReferences(
    val walletId: String,
    val destinationWalletId: String?,
    val categoryId: String?,
)

sealed interface TransactionValidationError {
    data object AmountNotPositive : TransactionValidationError
    data object SourceWalletNotFound : TransactionValidationError
    data object SourceWalletArchived : TransactionValidationError
    data object DestinationWalletRequired : TransactionValidationError
    data object DestinationWalletNotAllowed : TransactionValidationError
    data object DestinationWalletNotFound : TransactionValidationError
    data object DestinationWalletArchived : TransactionValidationError
    data object SameSourceAndDestination : TransactionValidationError
    data object CategoryRequired : TransactionValidationError
    data object CategoryNotAllowedForTransfer : TransactionValidationError
    data object CategoryNotFound : TransactionValidationError
    data object CategoryTypeMismatch : TransactionValidationError
    data object CategoryArchived : TransactionValidationError
}

/**
 * Enforces the transaction business rules (docs/ENGINEERING_SPEC.md, "Transaction business rules").
 *
 * Archived wallets and categories cannot be chosen for a new transaction or newly assigned during an
 * edit, but an edit may keep the archived reference the transaction already had, so historical
 * records remain editable.
 */
object TransactionValidator {

    fun validate(
        draft: TransactionDraft,
        sourceWallet: WalletRef?,
        destinationWallet: WalletRef?,
        category: CategoryRef?,
        existing: ExistingReferences? = null,
    ): List<TransactionValidationError> {
        val errors = mutableListOf<TransactionValidationError>()

        if (draft.amount <= 0) errors += TransactionValidationError.AmountNotPositive

        when {
            sourceWallet == null -> errors += TransactionValidationError.SourceWalletNotFound
            !sourceWallet.isActive && sourceWallet.id != existing?.walletId ->
                errors += TransactionValidationError.SourceWalletArchived
        }

        when (draft.type) {
            TransactionType.INCOME -> validateCategorized(draft, category, CategoryType.INCOME, existing, errors)
            TransactionType.EXPENSE -> validateCategorized(draft, category, CategoryType.EXPENSE, existing, errors)
            TransactionType.TRANSFER -> validateTransfer(draft, destinationWallet, existing, errors)
        }
        return errors
    }

    private fun validateCategorized(
        draft: TransactionDraft,
        category: CategoryRef?,
        expectedType: CategoryType,
        existing: ExistingReferences?,
        errors: MutableList<TransactionValidationError>,
    ) {
        if (draft.destinationWalletId != null) errors += TransactionValidationError.DestinationWalletNotAllowed

        if (draft.categoryId == null) {
            errors += TransactionValidationError.CategoryRequired
            return
        }
        when {
            category == null -> errors += TransactionValidationError.CategoryNotFound
            category.type != expectedType -> errors += TransactionValidationError.CategoryTypeMismatch
            category.isArchived && category.id != existing?.categoryId ->
                errors += TransactionValidationError.CategoryArchived
        }
    }

    private fun validateTransfer(
        draft: TransactionDraft,
        destinationWallet: WalletRef?,
        existing: ExistingReferences?,
        errors: MutableList<TransactionValidationError>,
    ) {
        if (draft.categoryId != null) errors += TransactionValidationError.CategoryNotAllowedForTransfer

        if (draft.destinationWalletId == null) {
            errors += TransactionValidationError.DestinationWalletRequired
            return
        }
        if (draft.destinationWalletId == draft.walletId) {
            errors += TransactionValidationError.SameSourceAndDestination
            return
        }
        when {
            destinationWallet == null -> errors += TransactionValidationError.DestinationWalletNotFound
            !destinationWallet.isActive && destinationWallet.id != existing?.destinationWalletId ->
                errors += TransactionValidationError.DestinationWalletArchived
        }
    }
}
