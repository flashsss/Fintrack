package com.fintrack.app.presentation.common

import androidx.annotation.StringRes
import com.fintrack.app.R
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionSource
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType

@get:StringRes
val WalletType.labelRes: Int
    get() = when (this) {
        WalletType.CASH -> R.string.wallet_type_cash
        WalletType.BANK -> R.string.wallet_type_bank
        WalletType.E_WALLET -> R.string.wallet_type_e_wallet
        WalletType.CREDIT_CARD -> R.string.wallet_type_credit_card
        WalletType.INVESTMENT -> R.string.wallet_type_investment
        WalletType.OTHER -> R.string.wallet_type_other
    }

@get:StringRes
val TransactionType.labelRes: Int
    get() = when (this) {
        TransactionType.INCOME -> R.string.transaction_type_income
        TransactionType.EXPENSE -> R.string.transaction_type_expense
        TransactionType.TRANSFER -> R.string.transaction_type_transfer
    }

@get:StringRes
val CategoryType.labelRes: Int
    get() = when (this) {
        CategoryType.INCOME -> R.string.category_type_income
        CategoryType.EXPENSE -> R.string.category_type_expense
    }

@get:StringRes
val TransactionSource.labelRes: Int
    get() = when (this) {
        TransactionSource.MANUAL -> R.string.transaction_source_manual
        TransactionSource.CSV_IMPORT -> R.string.transaction_source_csv
    }
