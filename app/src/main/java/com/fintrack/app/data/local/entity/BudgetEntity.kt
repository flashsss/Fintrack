package com.fintrack.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fintrack.app.domain.model.Budget
import com.fintrack.app.domain.model.SyncStatus

/**
 * A monthly spending limit that repeats every Asia/Jakarta calendar month. [categoryId] null is
 * the overall budget (all expenses). Introduced in schema version 2 (MIGRATION_1_2).
 */
@Entity(
    tableName = "budgets",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("categoryId"), Index("deletedAt")],
)
data class BudgetEntity(
    @PrimaryKey val id: String,
    val categoryId: String?,
    val amount: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val syncStatus: SyncStatus,
)

fun BudgetEntity.toDomain() = Budget(id, categoryId, amount)
