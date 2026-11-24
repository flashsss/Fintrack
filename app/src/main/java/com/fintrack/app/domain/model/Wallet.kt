package com.fintrack.app.domain.model

import java.time.Instant

data class Wallet(
    val id: String,
    val name: String,
    val type: WalletType,
    /** Integer rupiah. May be negative, e.g. an outstanding credit card balance. */
    val initialBalance: Long,
    val currency: String,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class WalletBalance(
    val walletId: String,
    /** Integer rupiah, see the wallet balance formula in docs/ENGINEERING_SPEC.md. */
    val balance: Long,
)
