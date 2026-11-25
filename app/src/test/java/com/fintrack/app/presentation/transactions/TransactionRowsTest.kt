package com.fintrack.app.presentation.transactions

import com.fintrack.app.domain.model.Category
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.Transaction
import com.fintrack.app.domain.model.TransactionSource
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.Wallet
import com.fintrack.app.domain.model.WalletType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TransactionRowsTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val wallets = listOf("bca" to "BCA", "mandiri" to "Mandiri").associate { (id, name) ->
        id to Wallet(id, name, WalletType.BANK, 0, "IDR", true, now, now)
    }
    private val food = Category("food", "Makanan", CategoryType.EXPENSE, null, null, null, false, false, now, now)
    private val cafe = Category("cafe", "Kafe", CategoryType.EXPENSE, "food", null, null, false, false, now, now)
    private val categories = listOf(food, cafe).associateBy { it.id }

    private fun tx(type: TransactionType, wallet: String = "bca", dest: String? = null, category: String? = null) =
        Transaction("t", wallet, dest, category, type, 1_500_000, now, null, "Toko", TransactionSource.MANUAL, now, now)

    @Test
    fun `income and expense carry sign and category label with parent`() {
        val expense = TransactionRows.toRow(tx(TransactionType.EXPENSE, category = "cafe"), wallets, categories)
        assertEquals("-Rp1.500.000", expense.amountText)
        assertEquals("Makanan › Kafe", expense.categoryLabel)
        assertEquals("Toko", expense.note)

        val income = TransactionRows.toRow(tx(TransactionType.INCOME, category = "food"), wallets, categories)
        assertEquals("+Rp1.500.000", income.amountText)
        assertEquals(AmountDirection.IN, income.direction)
    }

    @Test
    fun `transfer direction depends on the viewed wallet`() {
        val transfer = tx(TransactionType.TRANSFER, dest = "mandiri")
        assertEquals(AmountDirection.NEUTRAL, TransactionRows.toRow(transfer, wallets, categories).direction)
        assertEquals("-Rp1.500.000", TransactionRows.toRow(transfer, wallets, categories, "bca").amountText)
        assertEquals("+Rp1.500.000", TransactionRows.toRow(transfer, wallets, categories, "mandiri").amountText)
    }

    @Test
    fun `note shows description and merchant when both exist`() {
        val both = tx(TransactionType.EXPENSE, category = "food").copy(description = "Makan siang")
        assertEquals("Makan siang · Toko", TransactionRows.toRow(both, wallets, categories).note)
        val none = tx(TransactionType.EXPENSE, category = "food").copy(merchant = null)
        assertEquals(null, TransactionRows.toRow(none, wallets, categories).note)
    }
}
