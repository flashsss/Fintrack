package com.fintrack.app.presentation.transactions

import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.core.util.formatRupiah
import com.fintrack.app.domain.model.Category
import com.fintrack.app.domain.model.Transaction
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.Wallet
import com.fintrack.app.presentation.common.categoryLabel
import java.time.LocalDate

/** How an amount affects the wallet(s) being viewed. Transfers in the overall history are NEUTRAL. */
enum class AmountDirection { IN, OUT, NEUTRAL }

data class TransactionRow(
    val id: String,
    val type: TransactionType,
    /** Category label; null for transfers, which the UI labels "Transfer". */
    val categoryLabel: String?,
    /** Wallet name, or "Asal → Tujuan" for transfers. */
    val walletLabel: String,
    val note: String?,
    val amountText: String,
    val direction: AmountDirection,
)

data class TransactionDayGroup(val date: LocalDate, val rows: List<TransactionRow>)

object TransactionRows {

    private const val MISSING = "—"

    /**
     * Groups transactions by Asia/Jakarta calendar day, keeping the input order (newest first).
     * With [perspectiveWalletId], transfers are shown as money in or out of that wallet.
     */
    fun group(
        transactions: List<Transaction>,
        walletsById: Map<String, Wallet>,
        categoriesById: Map<String, Category>,
        perspectiveWalletId: String? = null,
    ): List<TransactionDayGroup> =
        transactions
            .groupBy { JakartaTime.localDate(it.transactionDate) }
            .map { (date, items) ->
                TransactionDayGroup(date, items.map { toRow(it, walletsById, categoriesById, perspectiveWalletId) })
            }

    fun toRow(
        transaction: Transaction,
        walletsById: Map<String, Wallet>,
        categoriesById: Map<String, Category>,
        perspectiveWalletId: String? = null,
    ): TransactionRow {
        val source = walletsById[transaction.walletId]?.name ?: MISSING
        val direction = when (transaction.type) {
            TransactionType.INCOME -> AmountDirection.IN
            TransactionType.EXPENSE -> AmountDirection.OUT
            TransactionType.TRANSFER -> when (perspectiveWalletId) {
                null -> AmountDirection.NEUTRAL
                transaction.walletId -> AmountDirection.OUT
                transaction.destinationWalletId -> AmountDirection.IN
                else -> AmountDirection.NEUTRAL
            }
        }
        val amount = formatRupiah(transaction.amount)
        return TransactionRow(
            id = transaction.id,
            type = transaction.type,
            categoryLabel = transaction.categoryId?.let { id ->
                categoriesById[id]?.let { categoryLabel(it, categoriesById) } ?: MISSING
            },
            walletLabel = if (transaction.type == TransactionType.TRANSFER) {
                "$source → ${transaction.destinationWalletId?.let(walletsById::get)?.name ?: MISSING}"
            } else {
                source
            },
            note = transaction.description ?: transaction.merchant,
            amountText = when (direction) {
                AmountDirection.IN -> "+$amount"
                AmountDirection.OUT -> "-$amount"
                AmountDirection.NEUTRAL -> amount
            },
            direction = direction,
        )
    }
}
