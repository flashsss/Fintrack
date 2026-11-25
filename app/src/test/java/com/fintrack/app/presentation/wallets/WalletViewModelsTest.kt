package com.fintrack.app.presentation.wallets

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.R
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType
import com.fintrack.app.presentation.MainDispatcherRule
import com.fintrack.app.presentation.awaitValue
import com.fintrack.app.presentation.navigation.Routes
import com.fintrack.app.presentation.transactions.AmountDirection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class WalletViewModelsTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val t = TestDatabase()

    @After
    fun tearDown() = t.close()

    private fun newForm() = WalletFormViewModel(SavedStateHandle(), t.wallets)
    private fun editForm(id: String) = WalletFormViewModel(SavedStateHandle(mapOf(Routes.ARG_WALLET_ID to id)), t.wallets)
    private fun detail(id: String) = WalletDetailViewModel(
        SavedStateHandle(mapOf(Routes.ARG_WALLET_ID to id)), t.wallets, t.transactions, t.categories,
    )

    private suspend fun WalletFormViewModel.saveAndAwait(): WalletFormState {
        save()
        return state.awaitValue { !it.isSaving }
    }

    @Test
    fun `creates wallet with initial balance`() = runTest {
        val form = newForm()
        form.onNameChange("BCA")
        form.onTypeChange(WalletType.BANK)
        form.onInitialBalanceChange("10.000.000")
        assertTrue(form.saveAndAwait().isSaved)

        val wallet = t.wallets.observeAllWallets().first().single()
        assertEquals("BCA", wallet.name)
        assertEquals(WalletType.BANK, wallet.type)
        assertEquals(10_000_000L, wallet.initialBalance)
    }

    @Test
    fun `empty initial balance means zero and negative switch stores debt`() = runTest {
        val cash = newForm()
        cash.onNameChange("Tunai")
        assertTrue(cash.saveAndAwait().isSaved)

        val card = newForm()
        card.onNameChange("Kartu kredit")
        card.onTypeChange(WalletType.CREDIT_CARD)
        card.onInitialBalanceChange("1.500.000")
        card.onNegativeChange(true)
        assertTrue(card.saveAndAwait().isSaved)

        val balances = t.wallets.observeAllWallets().first().associate { it.name to it.initialBalance }
        assertEquals(mapOf("Kartu kredit" to -1_500_000L, "Tunai" to 0L), balances)
    }

    @Test
    fun `blank name is rejected`() = runTest {
        val form = newForm()
        form.onNameChange("   ")
        val result = form.saveAndAwait()
        assertFalse(result.isSaved)
        assertEquals(R.string.error_name_required, result.nameError)
        assertTrue(t.wallets.observeAllWallets().first().isEmpty())
    }

    @Test
    fun `edits wallet and keeps negative sign`() = runTest {
        val id = t.wallets.createWallet("Kartu", WalletType.CREDIT_CARD, -200_000).requireId()
        val form = editForm(id)
        val loaded = form.state.awaitValue { !it.isLoading }
        assertEquals("200.000", loaded.initialBalanceText)
        assertTrue(loaded.isNegative)

        form.onNameChange("Kartu Kredit BCA")
        form.onInitialBalanceChange("300.000")
        assertTrue(form.saveAndAwait().isSaved)
        val wallet = t.wallets.getWallet(id)!!
        assertEquals("Kartu Kredit BCA", wallet.name)
        assertEquals(-300_000L, wallet.initialBalance)
    }

    @Test
    fun `list separates archived wallets and shows SQL balances`() = runTest {
        val bca = t.wallets.createWallet("BCA", WalletType.BANK, 1_000_000).requireId()
        val old = t.wallets.createWallet("Lama", WalletType.CASH, 50_000).requireId()
        t.wallets.archiveWallet(old).requireId()

        val state = WalletListViewModel(t.wallets).state.awaitValue { !it.isLoading }
        assertEquals(listOf(bca), state.active.map { it.id })
        assertEquals("Rp1.000.000", state.active.single().balanceText)
        assertEquals(listOf(old), state.archived.map { it.id })
    }

    @Test
    fun `detail balance and history follow transaction changes`() = runTest {
        val bca = t.wallets.createWallet("BCA", WalletType.BANK, 10_000_000).requireId()
        val mandiri = t.wallets.createWallet("Mandiri", WalletType.BANK, 5_000_000).requireId()
        val food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        val vm = detail(mandiri)
        vm.state.awaitValue { !it.isLoading && it.balanceText == "Rp5.000.000" }

        val transfer = t.transactions.addTransaction(
            TransactionDraft(TransactionType.TRANSFER, bca, mandiri, null, 3_000_000, Instant.EPOCH),
        ).requireId()
        val afterTransfer = vm.state.awaitValue { it.balanceText == "Rp8.000.000" }
        val row = afterTransfer.history.single().rows.single()
        assertEquals(AmountDirection.IN, row.direction)
        assertEquals("+Rp3.000.000", row.amountText)

        t.transactions.addTransaction(
            TransactionDraft(TransactionType.EXPENSE, mandiri, null, food, 500_000, Instant.EPOCH),
        ).requireId()
        vm.state.awaitValue { it.balanceText == "Rp7.500.000" }

        t.transactions.deleteTransaction(transfer).requireId()
        val afterDelete = vm.state.awaitValue { it.balanceText == "Rp4.500.000" }
        assertEquals(1, afterDelete.history.single().rows.size)
    }

    @Test
    fun `archive from detail keeps wallet visible and marks it inactive`() = runTest {
        val id = t.wallets.createWallet("OVO", WalletType.E_WALLET, 25_000).requireId()
        val vm = detail(id)
        vm.state.awaitValue { !it.isLoading }

        vm.archive()
        val archived = vm.state.awaitValue { it.wallet?.isActive == false }
        assertEquals("Rp25.000", archived.balanceText)
        assertEquals(null, vm.archiveError.value)
    }

    @Test
    fun `unknown wallet shows not found`() = runTest {
        assertTrue(editForm("missing").state.awaitValue { !it.isLoading }.notFound)
        assertEquals(null, detail("missing").state.awaitValue { !it.isLoading }.wallet)
    }
}
