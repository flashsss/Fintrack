package com.fintrack.app.data.repository

import androidx.room.withTransaction
import com.fintrack.app.data.local.dao.CategoryDao
import com.fintrack.app.data.local.dao.TransactionDao
import com.fintrack.app.data.local.dao.WalletDao
import com.fintrack.app.data.local.database.FinTrackDatabase
import com.fintrack.app.data.local.entity.TransactionEntity
import com.fintrack.app.data.local.entity.toDomain
import com.fintrack.app.domain.model.DateRange
import com.fintrack.app.domain.model.SyncStatus
import com.fintrack.app.domain.model.Transaction
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionSource
import com.fintrack.app.domain.model.WriteResult
import com.fintrack.app.domain.validation.CategoryRef
import com.fintrack.app.domain.validation.ExistingReferences
import com.fintrack.app.domain.validation.TransactionValidationError
import com.fintrack.app.domain.validation.TransactionValidator
import com.fintrack.app.domain.validation.WalletRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only write path for transactions. Every write validates against the current database
 * state inside a single Room transaction, so validation and the write cannot interleave with
 * another write. Wallet balances are derived from these rows and therefore update automatically.
 */
@Singleton
class TransactionRepository @Inject constructor(
    private val database: FinTrackDatabase,
    private val transactionDao: TransactionDao,
    private val walletDao: WalletDao,
    private val categoryDao: CategoryDao,
    private val clock: Clock,
) {

    fun observeTransactions(): Flow<List<Transaction>> =
        transactionDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /**
     * Transaction history filtered by an optional date range and a free-text [query] matched
     * against description, merchant and category name. A blank query matches everything.
     */
    fun observeTransactions(query: String, range: DateRange?): Flow<List<Transaction>> =
        transactionDao.observeFiltered(
            startMillis = range?.start?.toEpochMilli(),
            endMillis = range?.endExclusive?.toEpochMilli(),
            pattern = query.trim().takeIf { it.isNotEmpty() }?.let { "%${it.escapeLike()}%" },
        ).map { rows -> rows.map { it.toDomain() } }

    fun observeWalletTransactions(walletId: String): Flow<List<Transaction>> =
        transactionDao.observeByWallet(walletId).map { rows -> rows.map { it.toDomain() } }

    fun observeTransaction(id: String): Flow<Transaction?> =
        transactionDao.observeById(id).map { it?.toDomain() }

    suspend fun getTransaction(id: String): Transaction? = transactionDao.getById(id)?.toDomain()

    suspend fun addTransaction(draft: TransactionDraft): WriteResult<TransactionValidationError> =
        database.withTransaction {
            val errors = validate(draft, existing = null)
            if (errors.isNotEmpty()) return@withTransaction WriteResult.Invalid(errors)

            val now = clock.millis()
            val entity = TransactionEntity(
                id = UUID.randomUUID().toString(),
                walletId = draft.walletId,
                destinationWalletId = draft.destinationWalletId,
                categoryId = draft.categoryId,
                type = draft.type,
                amount = draft.amount,
                transactionDate = draft.transactionDate.toEpochMilli(),
                description = draft.description.normalized(),
                merchant = draft.merchant.normalized(),
                source = TransactionSource.MANUAL,
                importBatchId = null,
                externalId = null,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                syncStatus = SyncStatus.PENDING,
            )
            transactionDao.insert(entity)
            WriteResult.Success(entity.id)
        }

    /** Replaces the editable fields of an existing transaction. The row id never changes. */
    suspend fun updateTransaction(
        id: String,
        draft: TransactionDraft,
    ): WriteResult<TransactionValidationError> = database.withTransaction {
        val existing = transactionDao.getById(id) ?: return@withTransaction WriteResult.NotFound
        val errors = validate(
            draft,
            existing = ExistingReferences(
                walletId = existing.walletId,
                destinationWalletId = existing.destinationWalletId,
                categoryId = existing.categoryId,
            ),
        )
        if (errors.isNotEmpty()) return@withTransaction WriteResult.Invalid(errors)

        transactionDao.update(
            existing.copy(
                walletId = draft.walletId,
                destinationWalletId = draft.destinationWalletId,
                categoryId = draft.categoryId,
                type = draft.type,
                amount = draft.amount,
                transactionDate = draft.transactionDate.toEpochMilli(),
                description = draft.description.normalized(),
                merchant = draft.merchant.normalized(),
                updatedAt = clock.millis(),
                syncStatus = SyncStatus.PENDING,
            ),
        )
        WriteResult.Success(id)
    }

    /**
     * Soft delete: the row is kept with deletedAt set, so it can be synchronized and audited,
     * and it no longer contributes to balances. Deleting an already deleted transaction
     * returns [WriteResult.NotFound] and has no further effect.
     */
    suspend fun deleteTransaction(id: String): WriteResult<TransactionValidationError> =
        database.withTransaction {
            val existing = transactionDao.getById(id) ?: return@withTransaction WriteResult.NotFound
            val now = clock.millis()
            transactionDao.update(existing.copy(deletedAt = now, updatedAt = now, syncStatus = SyncStatus.PENDING))
            WriteResult.Success(id)
        }

    private suspend fun validate(
        draft: TransactionDraft,
        existing: ExistingReferences?,
    ): List<TransactionValidationError> {
        val source = walletDao.getById(draft.walletId)?.let { WalletRef(it.id, it.isActive) }
        val destination = draft.destinationWalletId
            ?.let { walletDao.getById(it) }
            ?.let { WalletRef(it.id, it.isActive) }
        val category = draft.categoryId
            ?.let { categoryDao.getById(it) }
            ?.let { CategoryRef(it.id, it.type, it.isArchived) }
        return TransactionValidator.validate(draft, source, destination, category, existing)
    }

    private fun String.escapeLike(): String =
        replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
