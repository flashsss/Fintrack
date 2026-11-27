package com.fintrack.app.sync

import androidx.room.withTransaction
import com.fintrack.app.data.local.dao.SyncDao
import com.fintrack.app.data.local.database.FinTrackDatabase
import com.fintrack.app.domain.model.SyncStatus
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.validation.CategoryRef
import com.fintrack.app.domain.validation.ExistingReferences
import com.fintrack.app.domain.validation.TransactionValidator
import com.fintrack.app.domain.validation.WalletRef
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Result of offering one cloud row to the local database. */
enum class MergeOutcome {
    WRITTEN,
    /** The same version is already stored and synced (e.g. re-read in the cursor overlap). */
    UNCHANGED,
    KEPT_LOCAL,
    REJECTED,
}

/** The device side of sync, backed by Room. */
@Singleton
class LocalSyncStore @Inject constructor(
    private val database: FinTrackDatabase,
    private val dao: SyncDao,
) {
    suspend fun pending(table: SyncTable, limit: Int): List<SyncRecord> = when (table) {
        SyncTable.WALLETS -> dao.pendingWallets(limit).map(SyncRecord::Wallet)
        SyncTable.CATEGORIES -> dao.pendingCategories(limit).map(SyncRecord::Category)
        SyncTable.TRANSACTIONS -> dao.pendingTransactions(limit).map(SyncRecord::Transaction)
        SyncTable.BUDGETS -> dao.pendingBudgets(limit).map(SyncRecord::Budget)
        SyncTable.GOALS -> dao.pendingGoals(limit).map(SyncRecord::Goal)
    }

    /** Marks [record] synced unless it changed since it was read. Returns whether it was marked. */
    suspend fun markSynced(record: SyncRecord): Boolean = when (record.table) {
        SyncTable.WALLETS -> dao.markWalletSynced(record.id, record.updatedAt)
        SyncTable.CATEGORIES -> dao.markCategorySynced(record.id, record.updatedAt)
        SyncTable.TRANSACTIONS -> dao.markTransactionSynced(record.id, record.updatedAt)
        SyncTable.BUDGETS -> dao.markBudgetSynced(record.id, record.updatedAt)
        SyncTable.GOALS -> dao.markGoalSynced(record.id, record.updatedAt)
    } == 1

    /** Runs [block] in one Room transaction, so a page of cloud rows is written fully or not at all. */
    suspend fun <T> inTransaction(block: suspend () -> T): T = database.withTransaction { block() }

    /**
     * Last-writer-wins merge of one cloud row (design §6.2): a local copy with a newer updatedAt is
     * kept (it is pushed later); otherwise the cloud version is written as SYNCED.
     */
    suspend fun merge(remote: SyncRecord): MergeOutcome {
        val local = localVersion(remote)
        if (local != null && local.first > remote.updatedAt) return MergeOutcome.KEPT_LOCAL
        if (local != null && local.first == remote.updatedAt && local.second == SyncStatus.SYNCED) return MergeOutcome.UNCHANGED
        if (remote is SyncRecord.Transaction && !isValid(remote)) return MergeOutcome.REJECTED
        when (remote) {
            is SyncRecord.Wallet -> dao.upsertWallet(remote.entity.copy(syncStatus = SyncStatus.SYNCED))
            is SyncRecord.Category -> dao.upsertCategory(remote.entity.copy(syncStatus = SyncStatus.SYNCED))
            is SyncRecord.Transaction -> dao.upsertTransaction(remote.entity.copy(syncStatus = SyncStatus.SYNCED))
            is SyncRecord.Budget -> dao.upsertBudget(remote.entity.copy(syncStatus = SyncStatus.SYNCED))
            is SyncRecord.Goal -> dao.upsertGoal(remote.entity.copy(syncStatus = SyncStatus.SYNCED))
        }
        return MergeOutcome.WRITTEN
    }

    /** (updatedAt, syncStatus) of the local copy, or null when there is none. */
    private suspend fun localVersion(remote: SyncRecord): Pair<Long, SyncStatus>? = when (remote) {
        is SyncRecord.Wallet -> dao.wallet(remote.id)?.let { it.updatedAt to it.syncStatus }
        is SyncRecord.Category -> dao.category(remote.id)?.let { it.updatedAt to it.syncStatus }
        is SyncRecord.Transaction -> dao.transaction(remote.id)?.let { it.updatedAt to it.syncStatus }
        is SyncRecord.Budget -> dao.budget(remote.id)?.let { it.updatedAt to it.syncStatus }
        is SyncRecord.Goal -> dao.goal(remote.id)?.let { it.updatedAt to it.syncStatus }
    }

    /**
     * Same rules as manual entry (TransactionValidator). Archived wallets and categories are allowed
     * because synced rows are history: they are passed as the transaction's existing references.
     */
    private suspend fun isValid(remote: SyncRecord.Transaction): Boolean {
        val e = remote.entity
        val draft = TransactionDraft(e.type, e.walletId, e.destinationWalletId, e.categoryId, e.amount, Instant.ofEpochMilli(e.transactionDate), e.description, e.merchant)
        val wallet = dao.wallet(e.walletId)?.takeIf { it.deletedAt == null }?.let { WalletRef(it.id, it.isActive) }
        val destination = e.destinationWalletId?.let { dao.wallet(it) }?.takeIf { it.deletedAt == null }?.let { WalletRef(it.id, it.isActive) }
        val category = e.categoryId?.let { dao.category(it) }?.takeIf { it.deletedAt == null }?.let { CategoryRef(it.id, it.type, it.isArchived) }
        val existing = ExistingReferences(e.walletId, e.destinationWalletId, e.categoryId)
        return TransactionValidator.validate(draft, wallet, destination, category, existing).isEmpty()
    }
}
