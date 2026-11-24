package com.fintrack.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Queue of local changes waiting for cloud upload (outbox pattern).
 *
 * The table is part of schema version 1 so that Phase 6 does not need a structural migration for
 * it. It is intentionally not written to before Phase 6: until then, rows with
 * syncStatus = PENDING are the record of unsynchronized changes.
 */
@Entity(
    tableName = "sync_outbox",
    indices = [Index(value = ["entityType", "entityId"]), Index("createdAt")],
)
data class SyncOutboxEntity(
    @PrimaryKey val id: String,
    /** Table name of the changed entity, e.g. "transactions". */
    val entityType: String,
    val entityId: String,
    /** UPSERT or DELETE. */
    val operation: String,
    val createdAt: Long,
    val attemptCount: Int,
    val lastAttemptAt: Long?,
    val lastError: String?,
)
