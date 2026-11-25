package com.fintrack.app.data.repository

import androidx.room.withTransaction
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.data.local.dao.CategoryDao
import com.fintrack.app.data.local.dao.ImportDao
import com.fintrack.app.data.local.dao.ImportSql
import com.fintrack.app.data.local.dao.TransactionDao
import com.fintrack.app.data.local.dao.WalletDao
import com.fintrack.app.data.local.database.FinTrackDatabase
import com.fintrack.app.data.local.entity.TransactionEntity
import com.fintrack.app.domain.importing.ExistingTransactionKey
import com.fintrack.app.domain.importing.PlannedRow
import com.fintrack.app.domain.model.SyncStatus
import com.fintrack.app.domain.model.TransactionSource
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.validation.CategoryRef
import com.fintrack.app.domain.validation.TransactionValidationError
import com.fintrack.app.domain.validation.TransactionValidator
import com.fintrack.app.domain.validation.WalletRef
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ImportResult {
    data class Imported(val batchId: String, val count: Int) : ImportResult
    /** Nothing was written: a row became invalid after review (e.g. its wallet was archived). */
    data class Rejected(val line: Int, val errors: List<TransactionValidationError>) : ImportResult
    /** Nothing was written: a fingerprint was stored by another import after review. */
    data class Duplicate(val line: Int) : ImportResult
}

@Singleton
class ImportRepository @Inject constructor(
    private val database: FinTrackDatabase,
    private val importDao: ImportDao,
    private val transactionDao: TransactionDao,
    private val walletDao: WalletDao,
    private val categoryDao: CategoryDao,
    private val clock: Clock,
) {
    suspend fun storedFingerprints(walletId: String, candidates: Collection<String>): Set<String> =
        candidates.chunked(ImportSql.MAX_PARAMS).flatMap { importDao.storedFingerprints(walletId, it) }.toSet()

    /** Income/expense of [walletId] keyed by Jakarta day, type and amount, for possible-duplicate checks. */
    suspend fun existingKeys(walletId: String, first: LocalDate, last: LocalDate): Set<ExistingTransactionKey> {
        val range = JakartaTime.range(first, last)
        return importDao.existingKeys(walletId, range.start.toEpochMilli(), range.endExclusive.toEpochMilli()).map {
            ExistingTransactionKey(JakartaTime.localDate(java.time.Instant.ofEpochMilli(it.transactionDate)), TransactionType.valueOf(it.type), it.amount)
        }.toSet()
    }

    /**
     * Writes every reviewed row or none (one Room transaction). Each row is validated again with
     * the same TransactionValidator as manual entry and its fingerprint re-checked.
     */
    suspend fun import(rows: List<PlannedRow.Ready>): ImportResult = database.withTransaction {
        val batchId = UUID.randomUUID().toString()
        val now = clock.millis()
        val stored = rows.groupBy { it.draft.walletId }
            .flatMap { (walletId, group) -> storedFingerprints(walletId, group.map { it.fingerprint }) }.toSet()
        for (row in rows) {
            if (row.fingerprint in stored) return@withTransaction ImportResult.Duplicate(row.line)
            val draft = row.draft
            val wallet = walletDao.getById(draft.walletId)?.let { WalletRef(it.id, it.isActive) }
            val category = draft.categoryId?.let { categoryDao.getById(it) }?.let { CategoryRef(it.id, it.type, it.isArchived) }
            val errors = TransactionValidator.validate(draft, wallet, null, category)
            if (errors.isNotEmpty()) return@withTransaction ImportResult.Rejected(row.line, errors)
        }
        rows.forEach { row ->
            val draft = row.draft
            transactionDao.insert(
                TransactionEntity(
                    id = UUID.randomUUID().toString(),
                    walletId = draft.walletId,
                    destinationWalletId = null,
                    categoryId = draft.categoryId,
                    type = draft.type,
                    amount = draft.amount,
                    transactionDate = draft.transactionDate.toEpochMilli(),
                    description = draft.description,
                    merchant = draft.merchant,
                    source = TransactionSource.CSV_IMPORT,
                    importBatchId = batchId,
                    externalId = row.fingerprint,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                    syncStatus = SyncStatus.PENDING,
                ),
            )
        }
        ImportResult.Imported(batchId, rows.size)
    }
}
