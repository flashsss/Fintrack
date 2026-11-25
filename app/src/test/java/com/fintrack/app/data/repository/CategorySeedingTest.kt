package com.fintrack.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class CategorySeedingTest {

    private val t = TestDatabase()

    @After
    fun tearDown() = t.close()

    @Test
    fun `seeds the approved Indonesian default categories`() = runTest {
        t.categories.seedDefaultCategories()

        val all = t.categories.observeAllCategories().first()
        assertEquals(
            setOf("Gaji", "Bisnis", "Investasi", "Bonus", "Hadiah", "Pemasukan lainnya"),
            all.filter { it.type == CategoryType.INCOME }.map { it.name }.toSet(),
        )
        assertEquals(
            setOf(
                "Makanan dan minuman", "Transportasi", "Belanja", "Tempat tinggal", "Tagihan", "Kesehatan",
                "Hiburan", "Pendidikan", "Perjalanan", "Kebutuhan pribadi", "Pengeluaran lainnya",
            ),
            all.filter { it.type == CategoryType.EXPENSE }.map { it.name }.toSet(),
        )
        assertTrue(all.all { it.isDefault && !it.isArchived && it.parentId == null })
    }

    @Test
    fun `seeding twice creates no duplicates`() = runTest {
        t.categories.seedDefaultCategories()
        t.categories.seedDefaultCategories()
        assertEquals(17, t.categories.observeAllCategories().first().size)
    }

    @Test
    fun `seeding does not overwrite renamed or archived defaults`() = runTest {
        t.categories.seedDefaultCategories()
        val food = t.categories.observeAllCategories().first().single { it.name == "Makanan dan minuman" }
        t.categories.updateCategory(food.id, "Makan", null, null, null).requireId()
        val gift = t.categories.observeAllCategories().first().single { it.name == "Hadiah" }
        t.categories.archiveCategory(gift.id).requireId()

        t.categories.seedDefaultCategories()

        val all = t.categories.observeAllCategories().first()
        assertEquals(17, all.size)
        assertEquals("Makan", all.single { it.id == food.id }.name)
        assertTrue(all.single { it.id == gift.id }.isArchived)
    }

    @Test
    fun `default ids are stable across databases`() = runTest {
        t.categories.seedDefaultCategories()
        val other = TestDatabase()
        try {
            other.categories.seedDefaultCategories()
            assertEquals(
                t.categories.observeAllCategories().first().map { it.id }.toSet(),
                other.categories.observeAllCategories().first().map { it.id }.toSet(),
            )
        } finally {
            other.close()
        }
    }

    @Test
    fun `seeded categories work with transactions and coexist with custom ones`() = runTest {
        t.categories.seedDefaultCategories()
        val custom = t.categories.createCategory("Kopi", CategoryType.EXPENSE, null, null, null).requireId()
        val food = t.categories.observeAllCategories().first().single { it.name == "Makanan dan minuman" }
        val wallet = t.wallets.createWallet("Tunai", WalletType.CASH, 100_000).requireId()

        t.transactions.addTransaction(
            TransactionDraft(TransactionType.EXPENSE, wallet, null, food.id, 20_000, Instant.EPOCH),
        ).requireId()
        t.transactions.addTransaction(
            TransactionDraft(TransactionType.EXPENSE, wallet, null, custom, 15_000, Instant.EPOCH),
        ).requireId()

        assertEquals(65_000, t.wallets.observeBalances().first().single().balance)
        assertEquals(18, t.categories.observeAllCategories().first().size)
    }
}
