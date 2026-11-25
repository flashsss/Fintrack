package com.fintrack.app.presentation.transactions

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TransactionFormViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val t = TestDatabase()
    private lateinit var bca: String
    private lateinit var mandiri: String
    private lateinit var salary: String
    private lateinit var food: String

    @Before
    fun setUp() = runTest {
        bca = t.wallets.createWallet("BCA", WalletType.BANK, 10_000_000).requireId()
        mandiri = t.wallets.createWallet("Mandiri", WalletType.BANK, 5_000_000).requireId()
        salary = t.categories.createCategory("Gaji", CategoryType.INCOME, null, null, null).requireId()
        food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
    }

    @After
    fun tearDown() = t.close()

    private fun newForm(type: TransactionType? = null) = TransactionFormViewModel(
        SavedStateHandle(type?.let { mapOf(Routes.ARG_TYPE to it.name) } ?: emptyMap()),
        t.transactions, t.wallets, t.categories, t.clock,
    )

    private fun editForm(id: String) = TransactionFormViewModel(
        SavedStateHandle(mapOf(Routes.ARG_TRANSACTION_ID to id)),
        t.transactions, t.wallets, t.categories, t.clock,
    )

    private suspend fun balances() = t.wallets.observeBalances().first().associate { it.walletId to it.balance }

    private suspend fun TransactionFormViewModel.saveAndAwait(): TransactionFormState {
        save()
        return state.awaitValue { !it.isSaving }
    }

    private suspend fun addDirect(draft: TransactionDraft) = t.transactions.addTransaction(draft).requireId()

    // Create

    @Test
    fun `creates income`() = runTest {
        val form = newForm(TransactionType.INCOME)
        form.onAmountChange("5.000.000")
        form.onWalletChange(bca)
        form.onCategoryChange(salary)
        form.onDescriptionChange("Gaji November")

        assertTrue(form.saveAndAwait().isSaved)
        assertEquals(15_000_000L, balances()[bca])
        val saved = t.transactions.observeTransactions().first().single()
        assertEquals(TransactionType.INCOME, saved.type)
        assertEquals("Gaji November", saved.description)
    }

    @Test
    fun `creates expense`() = runTest {
        val form = newForm(TransactionType.EXPENSE)
        form.onAmountChange("2.000.000")
        form.onWalletChange(bca)
        form.onCategoryChange(food)
        form.onMerchantChange("Warung")

        assertTrue(form.saveAndAwait().isSaved)
        assertEquals(8_000_000L, balances()[bca])
    }

    @Test
    fun `creates transfer as a single record`() = runTest {
        val form = newForm(TransactionType.TRANSFER)
        form.onAmountChange("3.000.000")
        form.onWalletChange(bca)
        form.onDestinationChange(mandiri)

        assertTrue(form.saveAndAwait().isSaved)
        assertEquals(mapOf(bca to 7_000_000L, mandiri to 8_000_000L), balances())
        val rows = t.transactions.observeTransactions().first()
        assertEquals(1, rows.size)
        assertNull(rows.single().categoryId)
    }

    @Test
    fun `selected date keeps Jakarta time of day`() = runTest {
        val form = newForm(TransactionType.EXPENSE)
        val before = form.state.value.timestamp
        form.onDateChange(LocalDate.of(2026, 2, 3))
        assertEquals(LocalDate.of(2026, 2, 3), form.state.value.date)
        assertEquals(before.atZone(com.fintrack.app.core.util.JakartaTime.ZONE).toLocalTime(),
            form.state.value.timestamp.atZone(com.fintrack.app.core.util.JakartaTime.ZONE).toLocalTime())
    }

    // Edit

    @Test
    fun `edits income`() = runTest {
        val id = addDirect(TransactionDraft(TransactionType.INCOME, bca, null, salary, 5_000_000, Instant.EPOCH))
        val form = editForm(id)
        val loaded = form.state.awaitValue { !it.isLoading }
        assertEquals("5.000.000", loaded.amountText)

        form.onAmountChange("6.000.000")
        assertTrue(form.saveAndAwait().isSaved)
        assertEquals(16_000_000L, balances()[bca])
        assertEquals(1, t.transactions.observeTransactions().first().size)
    }

    @Test
    fun `edits expense wallet`() = runTest {
        val id = addDirect(TransactionDraft(TransactionType.EXPENSE, bca, null, food, 2_000_000, Instant.EPOCH))
        val form = editForm(id)
        form.state.awaitValue { !it.isLoading }

        form.onWalletChange(mandiri)
        assertTrue(form.saveAndAwait().isSaved)
        assertEquals(mapOf(bca to 10_000_000L, mandiri to 3_000_000L), balances())
    }

    @Test
    fun `edits transfer`() = runTest {
        val id = addDirect(TransactionDraft(TransactionType.TRANSFER, bca, mandiri, null, 3_000_000, Instant.EPOCH))
        val form = editForm(id)
        form.state.awaitValue { !it.isLoading }

        form.onAmountChange("1.000.000")
        assertTrue(form.saveAndAwait().isSaved)
        assertEquals(mapOf(bca to 9_000_000L, mandiri to 6_000_000L), balances())
        assertEquals(1, t.transactions.observeTransactions().first().size)
    }

    @Test
    fun `editing a deleted transaction reports not found`() = runTest {
        val id = addDirect(TransactionDraft(TransactionType.EXPENSE, bca, null, food, 1_000, Instant.EPOCH))
        t.transactions.deleteTransaction(id).requireId()
        assertTrue(editForm(id).state.awaitValue { !it.isLoading }.notFound)
    }

    // Archived references

    @Test
    fun `archived wallet is not offered for new transactions`() = runTest {
        t.wallets.archiveWallet(mandiri).requireId()
        val choices = newForm().choices.awaitValue { it.sourceWallets.isNotEmpty() }
        assertEquals(listOf(bca), choices.sourceWallets.map { it.id })
        assertEquals(listOf(bca), choices.destinationWallets.map { it.id })
    }

    @Test
    fun `archived wallet cannot be submitted for a new transaction`() = runTest {
        t.wallets.archiveWallet(mandiri).requireId()
        val form = newForm(TransactionType.EXPENSE)
        form.onAmountChange("10.000")
        form.onWalletChange(mandiri)
        form.onCategoryChange(food)

        val result = form.saveAndAwait()
        assertFalse(result.isSaved)
        assertEquals(R.string.error_wallet_archived, result.errors.wallet)
        assertTrue(t.transactions.observeTransactions().first().isEmpty())
    }

    @Test
    fun `transaction on an archived wallet stays editable`() = runTest {
        val id = addDirect(TransactionDraft(TransactionType.EXPENSE, mandiri, null, food, 1_000_000, Instant.EPOCH))
        t.wallets.archiveWallet(mandiri).requireId()
        val form = editForm(id)
        form.state.awaitValue { !it.isLoading }

        val choices = form.choices.awaitValue { it.sourceWallets.size == 2 }
        assertTrue(choices.sourceWallets.single { it.id == mandiri }.isArchived)

        form.onAmountChange("500.000")
        assertTrue(form.saveAndAwait().isSaved)
        assertEquals(4_500_000L, balances()[mandiri])
    }

    @Test
    fun `transaction with an archived category stays editable and keeps the category`() = runTest {
        val id = addDirect(TransactionDraft(TransactionType.EXPENSE, bca, null, food, 1_000_000, Instant.EPOCH))
        t.categories.archiveCategory(food).requireId()
        val form = editForm(id)
        form.state.awaitValue { !it.isLoading }

        val choices = form.choices.awaitValue { it.categories.isNotEmpty() }
        assertEquals(listOf(food), choices.categories.map { it.id })
        assertTrue(choices.categories.single().isArchived)

        form.onAmountChange("750.000")
        assertTrue(form.saveAndAwait().isSaved)
        assertEquals(food, t.transactions.getTransaction(id)!!.categoryId)
    }

    @Test
    fun `archived category is not offered for new transactions`() = runTest {
        t.categories.archiveCategory(food).requireId()
        val other = t.categories.createCategory("Transportasi", CategoryType.EXPENSE, null, null, null).requireId()
        val choices = newForm(TransactionType.EXPENSE).choices.awaitValue { it.categories.isNotEmpty() }
        assertEquals(listOf(other), choices.categories.map { it.id })
    }

    // Invalid submissions

    @Test
    fun `empty amount is rejected and nothing is saved`() = runTest {
        val form = newForm(TransactionType.EXPENSE)
        form.onWalletChange(bca)
        form.onCategoryChange(food)

        val result = form.saveAndAwait()
        assertFalse(result.isSaved)
        assertEquals(R.string.error_amount_required, result.errors.amount)
        assertTrue(t.transactions.observeTransactions().first().isEmpty())
    }

    @Test
    fun `zero amount is rejected by the validator`() = runTest {
        val form = newForm(TransactionType.EXPENSE)
        form.onAmountChange("0")
        form.onWalletChange(bca)
        form.onCategoryChange(food)
        assertEquals(R.string.error_amount_not_positive, form.saveAndAwait().errors.amount)
    }

    @Test
    fun `missing wallet and category are reported on their fields`() = runTest {
        val form = newForm(TransactionType.INCOME)
        form.onAmountChange("100.000")

        val errors = form.saveAndAwait().errors
        assertEquals(R.string.error_wallet_required, errors.wallet)
        assertEquals(R.string.error_category_required, errors.category)
        assertNull(errors.amount)
    }

    @Test
    fun `transfer to the same wallet is rejected`() = runTest {
        val form = newForm(TransactionType.TRANSFER)
        form.onAmountChange("100.000")
        form.onWalletChange(bca)
        form.onDestinationChange(bca)

        assertEquals(R.string.error_destination_same, form.saveAndAwait().errors.destination)
        assertTrue(t.transactions.observeTransactions().first().isEmpty())
    }

    @Test
    fun `transfer without destination is rejected`() = runTest {
        val form = newForm(TransactionType.TRANSFER)
        form.onAmountChange("100.000")
        form.onWalletChange(bca)
        assertEquals(R.string.error_destination_required, form.saveAndAwait().errors.destination)
    }

    @Test
    fun `changing type clears category and destination`() = runTest {
        val form = newForm(TransactionType.TRANSFER)
        form.onDestinationChange(mandiri)
        form.onTypeChange(TransactionType.EXPENSE)
        form.onCategoryChange(food)
        assertNull(form.state.value.destinationWalletId)

        form.onTypeChange(TransactionType.INCOME)
        assertNull(form.state.value.categoryId)
    }

    @Test
    fun `fields are cleared of errors after correction`() = runTest {
        val form = newForm(TransactionType.EXPENSE)
        form.saveAndAwait()
        form.onAmountChange("1.000")
        form.onWalletChange(bca)
        form.onCategoryChange(food)
        assertEquals(TransactionFormErrors(), form.state.value.errors)
        assertTrue(form.saveAndAwait().isSaved)
    }
}
