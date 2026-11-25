package com.fintrack.app.presentation.ui

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.core.ui.theme.FinTrackTheme
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType
import com.fintrack.app.presentation.categories.CategoryListScreen
import com.fintrack.app.presentation.categories.CategoryListViewModel
import com.fintrack.app.presentation.navigation.Routes
import com.fintrack.app.presentation.transactions.TransactionDetailScreen
import com.fintrack.app.presentation.transactions.TransactionDetailViewModel
import com.fintrack.app.presentation.transactions.TransactionFormScreen
import com.fintrack.app.presentation.transactions.TransactionFormViewModel
import com.fintrack.app.presentation.transactions.TransactionListScreen
import com.fintrack.app.presentation.transactions.TransactionListViewModel
import com.fintrack.app.presentation.wallets.WalletListScreen
import com.fintrack.app.presentation.wallets.WalletListViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Renders real screens with real ViewModels over an in-memory database and drives them through the
 * UI. Waits use real time because Room completes work on its own threads. The screen size is a
 * typical phone; Robolectric's default (320x470dp) hides most rows of lazy lists.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private val t = TestDatabase()

    @After
    fun tearDown() = t.close()

    private fun waitForText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * Waits for a condition that is not part of the composition (e.g. a navigation callback after a
     * database write). Robolectric's main looper is paused, and compose's waitUntil does not run the
     * main-looper messages Room posts after a write, so this loop idles the looper explicitly.
     */
    private fun waitForCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Condition not met within 5 s" }
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `adding an expense through the form saves it`() {
        val wallet = runBlocking { t.wallets.createWallet("BCA", WalletType.BANK, 1_000_000).requireId() }
        runBlocking { t.categories.seedDefaultCategories() }
        var saved = false
        val vm = TransactionFormViewModel(
            SavedStateHandle(mapOf(Routes.ARG_TYPE to TransactionType.EXPENSE.name)),
            t.transactions, t.wallets, t.categories, t.budgetAlerts, t.clock
        )
        compose.setContent { FinTrackTheme { TransactionFormScreen(onBack = {}, onSaved = { saved = true }, viewModel = vm) } }

        compose.onNodeWithText("Jumlah").performTextInput("25000")
        compose.onNodeWithText("25.000").assertIsDisplayed()

        compose.onNodeWithText("Dompet").performClick()
        waitForText("BCA")
        compose.onNodeWithText("BCA").performClick()

        compose.onNodeWithText("Kategori").performClick()
        waitForText("Transportasi")
        compose.onNodeWithText("Transportasi").performScrollTo().performClick()

        compose.onNodeWithText("Simpan").performScrollTo().performClick()
        waitForCondition { saved }

        val transaction = runBlocking { t.transactions.observeTransactions().first().single() }
        assertEquals(25_000L, transaction.amount)
        assertEquals(wallet, transaction.walletId)
        assertEquals(975_000L, runBlocking { t.wallets.observeBalances().first().single().balance })
    }

    @Test
    fun `submitting an empty form shows Indonesian validation messages`() {
        val vm = TransactionFormViewModel(SavedStateHandle(), t.transactions, t.wallets, t.categories, t.budgetAlerts, t.clock)
        compose.setContent { FinTrackTheme { TransactionFormScreen(onBack = {}, onSaved = {}, viewModel = vm) } }

        compose.onNodeWithText("Belum ada dompet aktif. Buat dompet terlebih dahulu di menu Dompet.").assertIsDisplayed()
        compose.onNodeWithText("Simpan").performScrollTo().performClick()
        waitForText("Masukkan jumlah.")
        compose.onNodeWithText("Pilih dompet.").assertIsDisplayed()
        compose.onNodeWithText("Pilih kategori.").assertIsDisplayed()
        assertTrue(runBlocking { t.transactions.observeTransactions().first() }.isEmpty())
    }

    @Test
    fun `transaction history shows empty state then formatted rows`() {
        val vm = TransactionListViewModel(t.transactions, t.wallets, t.categories, t.todayProvider())
        compose.setContent {
            FinTrackTheme { TransactionListScreen(onAddTransaction = {}, onOpenTransaction = {}, viewModel = vm) }
        }
        waitForText("Belum ada transaksi. Ketuk Tambah transaksi untuk mencatat yang pertama.")

        runBlocking {
            val bca = t.wallets.createWallet("BCA", WalletType.BANK, 0).requireId()
            val mandiri = t.wallets.createWallet("Mandiri", WalletType.BANK, 0).requireId()
            t.transactions.addTransaction(
                TransactionDraft(TransactionType.TRANSFER, bca, mandiri, null, 3_000_000, Instant.parse("2026-01-01T03:00:00Z")),
            ).requireId()
        }
        waitForText("Rp3.000.000")
        compose.onNodeWithText("BCA → Mandiri").assertIsDisplayed()
        compose.onNodeWithText("Kamis, 1 Januari 2026").assertIsDisplayed()
    }

    @Test
    fun `detail shows archived wallet and deletes after confirmation`() {
        val id = runBlocking {
            val wallet = t.wallets.createWallet("Dompet Lama", WalletType.CASH, 100_000).requireId()
            val food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
            val tx = t.transactions.addTransaction(
                TransactionDraft(TransactionType.EXPENSE, wallet, null, food, 20_000, Instant.EPOCH, "Bakso"),
            ).requireId()
            t.wallets.archiveWallet(wallet).requireId()
            tx
        }
        var closed = false
        val vm = TransactionDetailViewModel(
            SavedStateHandle(mapOf(Routes.ARG_TRANSACTION_ID to id)), t.transactions, t.wallets, t.categories,
        )
        compose.setContent { FinTrackTheme { TransactionDetailScreen(onBack = { closed = true }, onEdit = {}, viewModel = vm) } }

        waitForText("Dompet Lama")
        compose.onNodeWithText("Diarsipkan").assertIsDisplayed()
        compose.onNodeWithText("Bakso").performScrollTo().assertIsDisplayed()

        compose.onNodeWithContentDescription("Hapus").performClick()
        waitForText("Hapus transaksi?")
        compose.onNodeWithText("Hapus").performClick()
        waitForCondition { closed }
        assertEquals(100_000L, runBlocking { t.wallets.observeBalances().first().single().balance })
    }

    @Test
    fun `wallet list shows balances from the database`() {
        runBlocking {
            t.wallets.createWallet("BCA", WalletType.BANK, 10_000_000).requireId()
            t.wallets.createWallet("Kartu Kredit", WalletType.CREDIT_CARD, -1_500_000).requireId()
        }
        val vm = WalletListViewModel(t.wallets)
        compose.setContent { FinTrackTheme { WalletListScreen(onAddWallet = {}, onOpenWallet = {}, viewModel = vm) } }
        waitForText("Rp10.000.000")
        compose.onNodeWithText("-Rp1.500.000").assertIsDisplayed()
        compose.onNodeWithText("Kartu kredit").assertIsDisplayed()
    }

    @Test
    fun `category list shows seeded defaults for both types`() {
        runBlocking { t.categories.seedDefaultCategories() }
        val vm = CategoryListViewModel(t.categories)
        compose.setContent {
            FinTrackTheme { CategoryListScreen(onBack = {}, onAddCategory = {}, onOpenCategory = {}, viewModel = vm) }
        }
        waitForText("Makanan dan minuman")
        compose.onNodeWithText("Pemasukan").performClick()
        waitForText("Gaji")
    }
}
