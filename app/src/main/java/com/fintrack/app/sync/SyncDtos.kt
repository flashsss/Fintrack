package com.fintrack.app.sync

import com.fintrack.app.data.local.entity.BudgetEntity
import com.fintrack.app.data.local.entity.CategoryEntity
import com.fintrack.app.data.local.entity.GoalEntity
import com.fintrack.app.data.local.entity.TransactionEntity
import com.fintrack.app.data.local.entity.WalletEntity
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.SyncStatus
import com.fintrack.app.domain.model.TransactionSource
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime

/*
 * JSON shapes of the Supabase tables (supabase/migrations/0001_initial.sql). user_id is never sent:
 * the server fills it from the signed-in session. server_updated_at is only read.
 */

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class WalletDto(
    val id: String,
    val name: String,
    val type: String,
    @SerialName("initial_balance") val initialBalance: Long,
    val currency: String,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long?,
    @SerialName("server_updated_at") @EncodeDefault(EncodeDefault.Mode.NEVER) val serverUpdatedAt: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CategoryDto(
    val id: String,
    val name: String,
    val type: String,
    @SerialName("parent_id") val parentId: String?,
    val icon: String?,
    val color: String?,
    @SerialName("is_default") val isDefault: Boolean,
    @SerialName("is_archived") val isArchived: Boolean,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long?,
    @SerialName("server_updated_at") @EncodeDefault(EncodeDefault.Mode.NEVER) val serverUpdatedAt: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TransactionDto(
    val id: String,
    @SerialName("wallet_id") val walletId: String,
    @SerialName("destination_wallet_id") val destinationWalletId: String?,
    @SerialName("category_id") val categoryId: String?,
    val type: String,
    val amount: Long,
    @SerialName("transaction_date") val transactionDate: Long,
    val description: String?,
    val merchant: String?,
    val source: String,
    @SerialName("import_batch_id") val importBatchId: String?,
    @SerialName("external_id") val externalId: String?,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long?,
    @SerialName("server_updated_at") @EncodeDefault(EncodeDefault.Mode.NEVER) val serverUpdatedAt: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BudgetDto(
    val id: String,
    @SerialName("category_id") val categoryId: String?,
    val amount: Long,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long?,
    @SerialName("server_updated_at") @EncodeDefault(EncodeDefault.Mode.NEVER) val serverUpdatedAt: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GoalDto(
    val id: String,
    val name: String,
    @SerialName("target_amount") val targetAmount: Long,
    @SerialName("current_amount") val currentAmount: Long,
    @SerialName("target_date") val targetDate: Long?,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long?,
    @SerialName("server_updated_at") @EncodeDefault(EncodeDefault.Mode.NEVER) val serverUpdatedAt: String? = null,
)

/** Entity ↔ DTO mapping. Rows read from the server become SYNCED; enum names are stored as text. */
object SyncMapping {
    fun toDto(e: WalletEntity) = WalletDto(e.id, e.name, e.type.name, e.initialBalance, e.currency, e.isActive, e.createdAt, e.updatedAt, e.deletedAt)
    fun toDto(e: CategoryEntity) = CategoryDto(e.id, e.name, e.type.name, e.parentId, e.icon, e.color, e.isDefault, e.isArchived, e.createdAt, e.updatedAt, e.deletedAt)
    fun toDto(e: TransactionEntity) = TransactionDto(
        e.id, e.walletId, e.destinationWalletId, e.categoryId, e.type.name, e.amount, e.transactionDate, e.description, e.merchant,
        e.source.name, e.importBatchId, e.externalId, e.createdAt, e.updatedAt, e.deletedAt,
    )
    fun toDto(e: BudgetEntity) = BudgetDto(e.id, e.categoryId, e.amount, e.createdAt, e.updatedAt, e.deletedAt)
    fun toDto(e: GoalEntity) = GoalDto(e.id, e.name, e.targetAmount, e.currentAmount, e.targetDate, e.createdAt, e.updatedAt, e.deletedAt)

    fun toEntity(d: WalletDto) = WalletEntity(d.id, d.name, WalletType.valueOf(d.type), d.initialBalance, d.currency, d.isActive, d.createdAt, d.updatedAt, d.deletedAt, SyncStatus.SYNCED)
    fun toEntity(d: CategoryDto) = CategoryEntity(
        d.id, d.name, CategoryType.valueOf(d.type), d.parentId, d.icon, d.color, d.isDefault, d.isArchived, d.createdAt, d.updatedAt, d.deletedAt, SyncStatus.SYNCED,
    )
    fun toEntity(d: TransactionDto) = TransactionEntity(
        d.id, d.walletId, d.destinationWalletId, d.categoryId, TransactionType.valueOf(d.type), d.amount, d.transactionDate, d.description, d.merchant,
        TransactionSource.valueOf(d.source), d.importBatchId, d.externalId, d.createdAt, d.updatedAt, d.deletedAt, SyncStatus.SYNCED,
    )
    fun toEntity(d: BudgetDto) = BudgetEntity(d.id, d.categoryId, d.amount, d.createdAt, d.updatedAt, d.deletedAt, SyncStatus.SYNCED)
    fun toEntity(d: GoalDto) = GoalEntity(d.id, d.name, d.targetAmount, d.currentAmount, d.targetDate, d.createdAt, d.updatedAt, d.deletedAt, SyncStatus.SYNCED)

    /** PostgREST timestamptz text, e.g. "2026-11-27T03:51:12.123456+00:00" (microseconds kept). */
    fun parseServerTime(text: String): Instant = OffsetDateTime.parse(text).toInstant()
}
