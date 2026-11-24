package com.fintrack.app.domain.model

import java.time.Instant

data class Transaction(
    val id: String,
    val walletId: String,
    val destinationWalletId: String?,
    val categoryId: String?,
    val type: TransactionType,
    /** Positive integer rupiah. The direction is defined by [type], never by the sign. */
    val amount: Long,
    val transactionDate: Instant,
    val description: String?,
    val merchant: String?,
    val source: TransactionSource,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** User-provided values for creating or editing a transaction. */
data class TransactionDraft(
    val type: TransactionType,
    val walletId: String,
    val destinationWalletId: String?,
    val categoryId: String?,
    val amount: Long,
    val transactionDate: Instant,
    val description: String? = null,
    val merchant: String? = null,
)
