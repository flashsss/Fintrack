package com.fintrack.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fintrack.app.domain.model.SyncStatus
import com.fintrack.app.domain.model.Wallet
import com.fintrack.app.domain.model.WalletType
import java.time.Instant

/**
 * Wallet balances are never stored; they are derived from transactions by
 * [com.fintrack.app.data.local.dao.WalletDao.observeBalances].
 * Timestamps are UTC epoch milliseconds.
 */
@Entity(
    tableName = "wallets",
    indices = [Index("isActive"), Index("deletedAt")],
)
data class WalletEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: WalletType,
    val initialBalance: Long,
    val currency: String,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val syncStatus: SyncStatus,
)

fun WalletEntity.toDomain() = Wallet(
    id = id,
    name = name,
    type = type,
    initialBalance = initialBalance,
    currency = currency,
    isActive = isActive,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
)
