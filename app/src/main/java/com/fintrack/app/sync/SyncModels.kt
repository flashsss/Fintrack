package com.fintrack.app.sync

import com.fintrack.app.data.local.entity.BudgetEntity
import com.fintrack.app.data.local.entity.CategoryEntity
import com.fintrack.app.data.local.entity.GoalEntity
import com.fintrack.app.data.local.entity.TransactionEntity
import com.fintrack.app.data.local.entity.WalletEntity
import java.time.Instant

/** Synchronized tables in foreign-key order: every table only references tables before it. */
enum class SyncTable(val remoteName: String) {
    WALLETS("wallets"),
    CATEGORIES("categories"),
    TRANSACTIONS("transactions"),
    BUDGETS("budgets"),
    GOALS("financial_goals"),
}

/** One row of a synchronized table. The local syncStatus is not part of the record's content. */
sealed interface SyncRecord {
    val id: String
    val updatedAt: Long
    val table: SyncTable

    data class Wallet(val entity: WalletEntity) : SyncRecord {
        override val id get() = entity.id
        override val updatedAt get() = entity.updatedAt
        override val table get() = SyncTable.WALLETS
    }

    data class Category(val entity: CategoryEntity) : SyncRecord {
        override val id get() = entity.id
        override val updatedAt get() = entity.updatedAt
        override val table get() = SyncTable.CATEGORIES
    }

    data class Transaction(val entity: TransactionEntity) : SyncRecord {
        override val id get() = entity.id
        override val updatedAt get() = entity.updatedAt
        override val table get() = SyncTable.TRANSACTIONS
    }

    data class Budget(val entity: BudgetEntity) : SyncRecord {
        override val id get() = entity.id
        override val updatedAt get() = entity.updatedAt
        override val table get() = SyncTable.BUDGETS
    }

    data class Goal(val entity: GoalEntity) : SyncRecord {
        override val id get() = entity.id
        override val updatedAt get() = entity.updatedAt
        override val table get() = SyncTable.GOALS
    }
}

/** A row read from the server with its server-side change time (the pull cursor). */
data class PulledRecord(val record: SyncRecord, val serverUpdatedAt: Instant)

/** The cloud side, implemented with Supabase PostgREST; tests use an in-memory fake. */
interface RemoteSyncSource {
    /** Inserts or updates [records] of one table for the signed-in user. */
    suspend fun upsert(table: SyncTable, records: List<SyncRecord>)

    /**
     * Rows of [table] with server_updated_at ≥ [since] (all rows when null), ordered by
     * (server_updated_at, id), skipping [offset] rows, at most [limit] rows.
     */
    suspend fun pull(table: SyncTable, since: Instant?, offset: Int, limit: Int): List<PulledRecord>
}

/** Failures a [RemoteSyncSource] reports. Anything else is a programming error and propagates. */
sealed class RemoteSyncException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No connection, timeout or server unavailable: retry later. */
    class Unavailable(cause: Throwable? = null) : RemoteSyncException("Cloud unavailable", cause)
    /** Session missing or expired and not refreshable: the user must sign in again. */
    class Unauthorized(cause: Throwable? = null) : RemoteSyncException("Not signed in", cause)
    /** The server refused the request (e.g. a CHECK constraint); retrying unchanged will not help. */
    class Rejected(message: String, cause: Throwable? = null) : RemoteSyncException(message, cause)
}

/** Per-device sync progress, stored in DataStore; tests use an in-memory map. */
interface SyncCursorStore {
    suspend fun cursor(table: SyncTable): Instant?
    suspend fun setCursor(table: SyncTable, value: Instant)
}

/** What one sync run did. Counts are rows. */
data class SyncReport(
    val pushed: Int,
    /** Local rows the server refused; they stay pending and are retried on later runs. */
    val pushRejected: Int,
    /** Cloud rows written locally (new or changed; unchanged re-reads are not counted). */
    val pulled: Int,
    /** Cloud rows skipped because the local copy is newer (it is uploaded on the next push). */
    val keptLocal: Int,
    /** Cloud rows not written because they failed local validation. */
    val pullRejected: Int,
)
