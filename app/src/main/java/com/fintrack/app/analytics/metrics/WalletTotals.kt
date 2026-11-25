package com.fintrack.app.analytics.metrics

import com.fintrack.app.domain.model.Wallet
import com.fintrack.app.domain.model.WalletBalance

/**
 * Sum of the current balances of active wallets. Balances come from the single SQL balance query;
 * this only adds them up. Archived wallets are excluded and negative balances (debt) reduce the
 * total. This is not net worth: assets and debts outside the app are not included.
 */
data class ActiveWalletTotal(val total: Long, val activeCount: Int, val archivedCount: Int) {
    companion object {
        fun of(wallets: List<Wallet>, balances: List<WalletBalance>): ActiveWalletTotal {
            val balanceById = balances.associate { it.walletId to it.balance }
            val active = wallets.filter { it.isActive }
            return ActiveWalletTotal(
                total = active.sumOf { balanceById[it.id] ?: 0L },
                activeCount = active.size,
                archivedCount = wallets.size - active.size,
            )
        }
    }
}
