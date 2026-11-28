package com.fintrack.app.data.repository

import androidx.room.withTransaction
import com.fintrack.app.data.local.dao.WalletDao
import com.fintrack.app.data.local.database.FinTrackDatabase
import com.fintrack.app.data.local.entity.WalletEntity
import com.fintrack.app.data.local.entity.toDomain
import com.fintrack.app.domain.model.SyncStatus
import com.fintrack.app.domain.model.Wallet
import com.fintrack.app.domain.model.WalletBalance
import com.fintrack.app.domain.model.WalletType
import com.fintrack.app.domain.model.WriteResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface WalletValidationError {
    data object NameBlank : WalletValidationError
    /** The wallet still has [count] transactions; deleting it would erase their history. */
    data class HasTransactions(val count: Int) : WalletValidationError
}

@Singleton
class WalletRepository @Inject constructor(
    private val database: FinTrackDatabase,
    private val walletDao: WalletDao,
    private val clock: Clock,
) {

    fun observeActiveWallets(): Flow<List<Wallet>> =
        walletDao.observeActive().map { wallets -> wallets.map { it.toDomain() } }

    fun observeAllWallets(): Flow<List<Wallet>> =
        walletDao.observeAll().map { wallets -> wallets.map { it.toDomain() } }

    fun observeBalances(): Flow<List<WalletBalance>> = walletDao.observeBalances()

    suspend fun getWallet(id: String): Wallet? = walletDao.getById(id)?.toDomain()

    suspend fun createWallet(
        name: String,
        type: WalletType,
        initialBalance: Long,
    ): WriteResult<WalletValidationError> {
        if (name.isBlank()) return WriteResult.Invalid(listOf(WalletValidationError.NameBlank))
        val now = clock.millis()
        val wallet = WalletEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            type = type,
            initialBalance = initialBalance,
            currency = CURRENCY_IDR,
            isActive = true,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            syncStatus = SyncStatus.PENDING,
        )
        walletDao.insert(wallet)
        return WriteResult.Success(wallet.id)
    }

    suspend fun updateWallet(
        id: String,
        name: String,
        type: WalletType,
        initialBalance: Long,
    ): WriteResult<WalletValidationError> {
        if (name.isBlank()) return WriteResult.Invalid(listOf(WalletValidationError.NameBlank))
        return database.withTransaction {
            val existing = walletDao.getById(id) ?: return@withTransaction WriteResult.NotFound
            walletDao.update(
                existing.copy(
                    name = name.trim(),
                    type = type,
                    initialBalance = initialBalance,
                    updatedAt = clock.millis(),
                    syncStatus = SyncStatus.PENDING,
                ),
            )
            WriteResult.Success(id)
        }
    }

    /**
     * Archived wallets keep their transactions and balance but cannot be chosen for new
     * transactions.
     */
    suspend fun archiveWallet(id: String): WriteResult<WalletValidationError> =
        database.withTransaction {
            val existing = walletDao.getById(id) ?: return@withTransaction WriteResult.NotFound
            walletDao.update(
                existing.copy(isActive = false, updatedAt = clock.millis(), syncStatus = SyncStatus.PENDING),
            )
            WriteResult.Success(id)
        }

    /** Makes an archived wallet selectable for new transactions again. */
    suspend fun unarchiveWallet(id: String): WriteResult<WalletValidationError> =
        database.withTransaction {
            val existing = walletDao.getById(id) ?: return@withTransaction WriteResult.NotFound
            if (!existing.isActive) {
                walletDao.update(existing.copy(isActive = true, updatedAt = clock.millis(), syncStatus = SyncStatus.PENDING))
            }
            WriteResult.Success(id)
        }

    /**
     * Soft-deletes a wallet that no transaction uses (deletedAt; synced like other deletions).
     * A wallet with transactions is refused: its history must be preserved, so it can only be
     * archived. The check and the deletion run in one transaction.
     */
    suspend fun deleteWallet(id: String): WriteResult<WalletValidationError> =
        database.withTransaction {
            val existing = walletDao.getById(id) ?: return@withTransaction WriteResult.NotFound
            val used = walletDao.activeTransactionCount(id)
            if (used > 0) return@withTransaction WriteResult.Invalid(listOf(WalletValidationError.HasTransactions(used)))
            val now = clock.millis()
            walletDao.update(existing.copy(deletedAt = now, updatedAt = now, syncStatus = SyncStatus.PENDING))
            WriteResult.Success(id)
        }

    companion object {
        const val CURRENCY_IDR = "IDR"
    }
}
