package com.fintrack.app.domain.importing

import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.domain.importing.ImportPlanner.AmountParse
import com.fintrack.app.domain.model.Category
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ImportPlannerTest {

    private val today = LocalDate.of(2026, 11, 25)
    private val food = category("food", "Makanan", CategoryType.EXPENSE)
    private val salary = category("salary", "Gaji", CategoryType.INCOME)
    private val oldCat = category("old", "Lama", CategoryType.EXPENSE, archived = true)
    private val mapping = ImportMapping(
        dateColumn = 0, dateFormat = ImportDateFormat.DAY_MONTH_YEAR_SLASH, amountColumn = 1,
        descriptionColumn = 2, merchantColumn = null, categoryColumn = 3,
        walletId = "w1", fallbackIncomeCategoryId = "other-in", fallbackExpenseCategoryId = "other-out",
    )

    private fun category(id: String, name: String, type: CategoryType, archived: Boolean = false) =
        Category(id, name, type, null, null, null, false, archived, Instant.EPOCH, Instant.EPOCH)

    private fun rows(vararg cells: List<String>) =
        CsvTable(listOf("Tanggal", "Jumlah", "Keterangan", "Kategori"), cells.mapIndexed { i, c -> CsvRow(i + 2, c) })

    private fun plan(table: CsvTable, stored: Set<String> = emptySet(), existing: Set<ExistingTransactionKey> = emptySet()) =
        ImportPlanner.plan(table, mapping, listOf(food, salary, oldCat), today, stored, existing)

    @Test
    fun `amounts in Indonesian formats`() {
        assertEquals(AmountParse.Value(1_500_000), ImportPlanner.parseAmount("Rp1.500.000"))
        assertEquals(AmountParse.Value(1_500_000), ImportPlanner.parseAmount("Rp 1.500.000,00"))
        assertEquals(AmountParse.Value(-25_000), ImportPlanner.parseAmount("-25.000"))
        assertEquals(AmountParse.Value(-25_000), ImportPlanner.parseAmount("(25.000)"))
        assertEquals(AmountParse.Value(2_500), ImportPlanner.parseAmount("2500"))
        assertEquals(AmountParse.Value(0), ImportPlanner.parseAmount("0"))
    }

    @Test
    fun `invalid amounts are classified`() {
        assertEquals(AmountParse.Missing, ImportPlanner.parseAmount("  "))
        assertEquals(AmountParse.Invalid, ImportPlanner.parseAmount("abc"))
        assertEquals(AmountParse.Invalid, ImportPlanner.parseAmount("1.50.000"))
        assertEquals(AmountParse.Invalid, ImportPlanner.parseAmount("1,5,0"))
        assertEquals(AmountParse.Fractional, ImportPlanner.parseAmount("1.500,50"))
        assertEquals(AmountParse.TooLarge, ImportPlanner.parseAmount("99999999999999999999"))
    }

    @Test
    fun `sign decides type and category name matches active categories of that type`() {
        val plan = plan(rows(
            listOf("01/11/2026", "-25.000", "Nasi", "makanan"),
            listOf("02/11/2026", "5.000.000", "Gaji Nov", "Gaji"),
            listOf("03/11/2026", "-10.000", "x", "Lama"),        // archived → fallback
            listOf("04/11/2026", "7.000", "y", "Makanan"),       // income named like an expense category → fallback
        ))
        val ready = plan.ready.map { it.draft }
        assertEquals(listOf(TransactionType.EXPENSE, TransactionType.INCOME, TransactionType.EXPENSE, TransactionType.INCOME), ready.map { it.type })
        assertEquals(listOf(25_000L, 5_000_000L, 10_000L, 7_000L), ready.map { it.amount })
        assertEquals(listOf("food", "salary", "other-out", "other-in"), ready.map { it.categoryId })
        assertNull(ready[0].destinationWalletId)
        assertEquals("Nasi", ready[0].description)
    }

    @Test
    fun `transaction time is noon in Jakarta on the row date`() {
        val draft = plan(rows(listOf("01/11/2026", "-1", "", ""))).ready.single().draft
        assertEquals(Instant.parse("2026-11-01T05:00:00Z"), draft.transactionDate)
        assertEquals(LocalDate.of(2026, 11, 1), JakartaTime.localDate(draft.transactionDate))
        assertNull(draft.description)
    }

    @Test
    fun `row problems are reported per row`() {
        val plan = plan(rows(
            listOf("", "", "", ""),
            listOf("31/02/2026", "abc", "", ""),
            listOf("26/11/2026", "1.000,50", "", ""),
            listOf("2026-11-01", "0", "", ""),
            listOf("25/11/2026", "1", "", ""),
        ))
        assertEquals(
            listOf(
                listOf(RowProblem.MISSING_DATE, RowProblem.MISSING_AMOUNT),
                listOf(RowProblem.INVALID_DATE, RowProblem.INVALID_AMOUNT),
                listOf(RowProblem.FUTURE_DATE, RowProblem.FRACTIONAL_AMOUNT),
                listOf(RowProblem.INVALID_DATE, RowProblem.ZERO_AMOUNT),
            ),
            plan.failed.map { it.problems },
        )
        assertEquals(listOf(6), plan.ready.map { it.line }) // today is allowed
    }

    @Test
    fun `fingerprint ignores text case and spacing but not wallet, date or amount`() {
        val base = ImportPlanner.fingerprint("w1", today, TransactionType.EXPENSE, 100, " Kopi  Susu ", null)
        assertEquals(base, ImportPlanner.fingerprint("w1", today, TransactionType.EXPENSE, 100, "kopi susu", ""))
        assertNotEquals(base, ImportPlanner.fingerprint("w2", today, TransactionType.EXPENSE, 100, "kopi susu", null))
        assertNotEquals(base, ImportPlanner.fingerprint("w1", today.minusDays(1), TransactionType.EXPENSE, 100, "kopi susu", null))
        assertNotEquals(base, ImportPlanner.fingerprint("w1", today, TransactionType.EXPENSE, 101, "kopi susu", null))
    }

    @Test
    fun `already imported, repeated and possible duplicates are separated`() {
        val table = rows(
            listOf("01/11/2026", "-25.000", "Kopi", ""),
            listOf("01/11/2026", "-25.000", "Kopi", ""),   // identical row: flagged, own fingerprint
            listOf("02/11/2026", "-30.000", "Bensin", ""),
            listOf("03/11/2026", "-40.000", "Pulsa", ""),
        )
        val first = plan(table)
        assertEquals(listOf(2, 4, 5), first.ready.map { it.line })
        val repeat = first.duplicates.single()
        assertEquals(DuplicateKind.REPEATED_IN_FILE, repeat.kind)
        assertNotEquals(first.ready[0].fingerprint, repeat.fingerprint)

        val stored = setOf(first.ready[0].fingerprint, repeat.fingerprint)
        val existing = setOf(ExistingTransactionKey(LocalDate.of(2026, 11, 2), TransactionType.EXPENSE, 30_000))
        val second = plan(table, stored, existing)
        assertEquals(listOf(5), second.ready.map { it.line })
        assertEquals(
            listOf(DuplicateKind.ALREADY_IMPORTED, DuplicateKind.ALREADY_IMPORTED, DuplicateKind.POSSIBLE_EXISTING),
            second.duplicates.map { it.kind },
        )
        // Fingerprints are stable between plans, so a re-import of the same file is recognized.
        assertEquals(first.ready.last().fingerprint, second.ready.single().fingerprint)
    }

    @Test
    fun `other date formats parse strictly`() {
        val iso = ImportPlanner.plan(rows(listOf("2026-11-01", "1", "", "")), mapping.copy(dateFormat = ImportDateFormat.ISO), emptyList(), today, emptySet(), emptySet())
        assertEquals(1, iso.ready.size)
        val dash = ImportPlanner.plan(rows(listOf("1-11-2026", "1", "", "")), mapping.copy(dateFormat = ImportDateFormat.DAY_MONTH_YEAR_DASH), emptyList(), today, emptySet(), emptySet())
        assertEquals(listOf(RowProblem.INVALID_DATE), dash.failed.single().problems)
    }
}
