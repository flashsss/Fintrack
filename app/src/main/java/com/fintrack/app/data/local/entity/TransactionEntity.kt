package com.fintrack.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fintrack.app.domain.model.SyncStatus
import com.fintrack.app.domain.model.Transaction
import com.fintrack.app.domain.model.TransactionSource
import com.fintrack.app.domain.model.TransactionType
import java.time.Instant

/**
 * A transfer is exactly one row: [walletId] is the source and [destinationWalletId] the
 * destination. Never write two rows for one transfer.
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = WalletEntity::class,
            parentColumns = ["id"],
            childColumns = ["walletId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = WalletEntity::class,
            parentColumns = ["id"],
            childColumns = ["destinationWalletId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("walletId"),
        Index("destinationWalletId"),
        Index("categoryId"),
        Index("transactionDate"),
        Index(value = ["type", "transactionDate"]),
        Index("importBatchId"),
        Index("deletedAt"),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val walletId: String,
    val destinationWalletId: String?,
    val categoryId: String?,
    val type: TransactionType,
    val amount: Long,
    val transactionDate: Long,
    val description: String?,
    val merchant: String?,
    val source: TransactionSource,
    val importBatchId: String?,
    val externalId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val syncStatus: SyncStatus,
)

fun TransactionEntity.toDomain() = Transaction(
    id = id,
    walletId = walletId,
    destinationWalletId = destinationWalletId,
    categoryId = categoryId,
    type = type,
    amount = amount,
    transactionDate = Instant.ofEpochMilli(transactionDate),
    description = description,
    merchant = merchant,
    source = source,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
)
