package com.fintrack.app.presentation.importing

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.R
import com.fintrack.app.core.common.FileReadResult
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.importing.DuplicateKind
import com.fintrack.app.domain.importing.ImportDateFormat
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType
import com.fintrack.app.presentation.MainDispatcherRule
import com.fintrack.app.presentation.awaitValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ImportViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val t = TestDatabase() // clock: 2026-01-01T00:00Z = 1 Jan 2026 07:00 WIB
    private lateinit var bca: String
    private lateinit var food: String
    private lateinit var otherOut: String
    private lateinit var otherIn: String
    private var file: FileReadResult = FileReadResult.Unreadable

    @Before
    fun setUp() = runTest {
        bca = t.wallets.createWallet("BCA", WalletType.BANK, 1_000_000).requireId()
        food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        otherOut = t.categories.createCategory("Lainnya", CategoryType.EXPENSE, null, null, null).requireId()
        otherIn = t.categories.createCategory("Pemasukan lain", CategoryType.INCOME, null, null, null).requireId()
    }

    @After
    fun tearDown() = t.close()

    private fun viewModel() = ImportViewModel({ file }, t.imports, t.wallets, t.categories, t.clock)

    private suspend fun ImportViewModel.load(text: String): ImportUiState {
        file = FileReadResult.Text(text)
        onFilePicked("content://test")
        return state.awaitValue { !it.isWorking || it.stage != ImportStage.PICK }
    }

    private suspend fun ImportViewModel.mapAndReview(): ImportUiState {
        onMappingChange { it.copy(walletId = bca, fallbackExpenseCategoryId = otherOut, fallbackIncomeCategoryId = otherIn) }
        review()
        return state.awaitValue { it.stage == ImportStage.REVIEW || (!it.isWorking && it.mappingErrors != MappingErrors()) }
    }

    private val csv = "Tanggal;Jumlah;Keterangan;Kategori\n" +
        "30/12/2025;-25.000;Nasi;Makanan\n" +
        "31/12/2025;-25.000;Nasi;Makanan\n" +
        "31/12/2025;1.000.000;Bonus;\n" +
        "02/01/2026;-5.000;Besok;\n" +
        "xx;-1;;\n"

    @Test
    fun `columns are guessed from Indonesian headers`() = runTest {
        val m = viewModel().load(csv).mapping
        assertEquals(listOf(0, 1, 2, null, 3), listOf(m.date, m.amount, m.description, m.merchant, m.category))
        assertEquals(ImportDateFormat.DAY_MONTH_YEAR_SLASH, m.dateFormat)
    }

    @Test
    fun `file errors are shown and stay on the pick step`() = runTest {
        val vm = viewModel()
        file = FileReadResult.TooLarge
        vm.onFilePicked("content://x")
        assertEquals(R.string.import_error_too_large, vm.state.awaitValue { !it.isWorking }.error)
        assertEquals(R.string.import_error_empty, vm.load("Tanggal,Jumlah\n").error)
        assertEquals(ImportStage.PICK, vm.state.value.stage)
        val tooMany = vm.load("a,b\n" + "1,2\n".repeat(com.fintrack.app.domain.importing.CsvImport.MAX_ROWS + 1))
        assertEquals(com.fintrack.app.domain.importing.CsvImport.MAX_ROWS, tooMany.rowLimitExceeded)
        assertEquals(null, tooMany.error)
    }

    @Test
    fun `review requires the wallet and fallback categories and writes nothing`() = runTest {
        val vm = viewModel()
        vm.load(csv)
        vm.review()
        val errors = vm.state.value.mappingErrors
        assertEquals(R.string.error_wallet_required, errors.wallet)
        assertEquals(R.string.error_category_required, errors.fallbackExpense)

        val reviewed = vm.mapAndReview()
        val plan = reviewed.plan!!
        assertEquals(listOf(2, 3, 4), plan.ready.map { it.line })
        assertEquals(listOf(5, 6), plan.failed.map { it.line }) // future date, invalid date
        assertTrue(t.transactions.observeTransactions().first().isEmpty())
    }

    @Test
    fun `confirmed import writes rows and a second import of the file finds them`() = runTest {
        val vm = viewModel()
        vm.load(csv)
        vm.mapAndReview()
        vm.confirmImport()
        val done = vm.state.awaitValue { it.stage == ImportStage.DONE }
        assertEquals(3, done.importedCount)
        assertEquals(1_000_000L - 50_000 + 1_000_000, t.wallets.observeBalances().first().single().balance)
        assertEquals(setOf(food, otherIn), t.transactions.observeTransactions().first().map { it.categoryId }.toSet())

        val again = viewModel()
        again.load(csv)
        val plan = again.mapAndReview().plan!!
        assertTrue(plan.ready.isEmpty())
        assertEquals(List(3) { DuplicateKind.ALREADY_IMPORTED }, plan.duplicates.map { it.kind })
        assertTrue(again.state.value.rowsToImport.isEmpty())
    }

    @Test
    fun `possible duplicates are skipped unless the user includes them`() = runTest {
        t.transactions.addTransaction(
            TransactionDraft(TransactionType.EXPENSE, bca, null, food, 25_000, Instant.parse("2025-12-30T02:00:00Z")),
        ).requireId()
        val vm = viewModel()
        vm.load(csv)
        val reviewed = vm.mapAndReview()
        assertEquals(DuplicateKind.POSSIBLE_EXISTING, reviewed.plan!!.duplicates.single().kind)
        assertEquals(listOf(3, 4), reviewed.rowsToImport.map { it.line })

        vm.onIncludePossibleDuplicatesChange(true)
        assertEquals(listOf(2, 3, 4), vm.state.value.rowsToImport.map { it.line })
        vm.confirmImport()
        vm.state.awaitValue { it.stage == ImportStage.DONE }
        assertEquals(4, t.transactions.observeTransactions().first().size)
    }

    @Test
    fun `a fallback category archived after review rejects the whole import`() = runTest {
        val vm = viewModel()
        vm.load(csv)
        vm.mapAndReview()
        t.categories.archiveCategory(otherIn).requireId()
        vm.confirmImport()
        val failed = vm.state.awaitValue { !it.isWorking }
        assertEquals(R.string.import_error_row_rejected, failed.error)
        assertEquals(4, failed.errorArg)
        assertEquals(ImportStage.REVIEW, failed.stage)
        assertTrue(t.transactions.observeTransactions().first().isEmpty())
    }
}
