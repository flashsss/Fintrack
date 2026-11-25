package com.fintrack.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fintrack.app.domain.model.FinancialGoal
import com.fintrack.app.domain.model.SyncStatus
import java.time.Instant

/**
 * A savings goal. [currentAmount] is maintained by the user (it is not derived from wallets).
 * [targetDate] is the UTC millis of the Asia/Jakarta midnight of the target day, or null.
 * Introduced in schema version 2 (MIGRATION_1_2).
 */
@Entity(tableName = "financial_goals", indices = [Index("deletedAt")])
data class GoalEntity(
    @PrimaryKey val id: String,
    val name: String,
    val targetAmount: Long,
    val currentAmount: Long,
    val targetDate: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val syncStatus: SyncStatus,
)

fun GoalEntity.toDomain() = FinancialGoal(id, name, targetAmount, currentAmount, targetDate?.let(Instant::ofEpochMilli))
