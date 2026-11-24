package com.fintrack.app.domain.model

/** Stored in Room by enum name. Never rename existing constants without a migration. */
enum class WalletType { CASH, BANK, E_WALLET, CREDIT_CARD, INVESTMENT, OTHER }

enum class CategoryType { INCOME, EXPENSE }

enum class TransactionType { INCOME, EXPENSE, TRANSFER }

enum class TransactionSource { MANUAL, CSV_IMPORT }

/**
 * PENDING: the local row has changes that were not uploaded yet.
 * SYNCED: the local row matches the last uploaded state.
 * Cloud synchronization is not implemented before Phase 6, so every local write is PENDING.
 */
enum class SyncStatus { PENDING, SYNCED }
