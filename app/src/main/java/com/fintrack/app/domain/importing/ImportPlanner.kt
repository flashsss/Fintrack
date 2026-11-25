package com.fintrack.app.domain.importing

import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.domain.model.Category
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionType
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

enum class ImportDateFormat(val pattern: String) {
    DAY_MONTH_YEAR_SLASH("dd/MM/uuuu"),
    DAY_MONTH_YEAR_DASH("dd-MM-uuuu"),
    ISO("uuuu-MM-dd"),
    ;

    val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
}

/** Which columns hold what. Column values are header indices; optional ones may be null. */
data class ImportMapping(
    val dateColumn: Int,
    val dateFormat: ImportDateFormat,
    val amountColumn: Int,
    val descriptionColumn: Int?,
    val merchantColumn: Int?,
    val categoryColumn: Int?,
    val walletId: String,
    /** Used when a row has no category column value or the name matches no active category. */
    val fallbackIncomeCategoryId: String,
    val fallbackExpenseCategoryId: String,
)

enum class RowProblem { MISSING_DATE, INVALID_DATE, MISSING_AMOUNT, INVALID_AMOUNT, FRACTIONAL_AMOUNT, ZERO_AMOUNT, TOO_LARGE, FUTURE_DATE }

enum class DuplicateKind {
    /** Same fingerprint already stored (the file or this row was imported before). */
    ALREADY_IMPORTED,
    /** An identical row appears earlier in this file (may be a genuine repeat). */
    REPEATED_IN_FILE,
    /** An existing transaction with the same wallet, Jakarta day, type and amount. */
    POSSIBLE_EXISTING,
}

sealed interface PlannedRow {
    val line: Int
    data class Ready(override val line: Int, val draft: TransactionDraft, val fingerprint: String) : PlannedRow
    data class Failed(override val line: Int, val problems: List<RowProblem>) : PlannedRow
    data class Duplicate(override val line: Int, val draft: TransactionDraft, val fingerprint: String, val kind: DuplicateKind) : PlannedRow
}

data class ImportPlan(val rows: List<PlannedRow>) {
    val ready: List<PlannedRow.Ready> get() = rows.filterIsInstance<PlannedRow.Ready>()
    val failed: List<PlannedRow.Failed> get() = rows.filterIsInstance<PlannedRow.Failed>()
    val duplicates: List<PlannedRow.Duplicate> get() = rows.filterIsInstance<PlannedRow.Duplicate>()
}

/** Existing data the planner compares against. */
data class ExistingTransactionKey(val day: LocalDate, val type: TransactionType, val amount: Long)

object ImportPlanner {

    /**
     * Indonesian amount text to signed integer rupiah: "Rp1.500.000", "-25.000", "1.500.000,00",
     * "2500". '.' groups thousands and ',' starts decimals; non-zero decimals are rejected because
     * amounts are whole rupiah. Returns null when the text is not a number.
     */
    fun parseAmount(text: String): AmountParse {
        var t = text.trim().replace(" ", "").replace(" ", "")
        if (t.isEmpty()) return AmountParse.Missing
        val negative = t.startsWith("-") || (t.startsWith("(") && t.endsWith(")"))
        t = t.removePrefix("-").removePrefix("(").removeSuffix(")").removePrefix("Rp").removePrefix("rp").removePrefix("RP")
        val (integerPart, fraction) = t.split(',').let { parts ->
            when (parts.size) {
                1 -> parts[0] to ""
                2 -> parts[0] to parts[1]
                else -> return AmountParse.Invalid
            }
        }
        val digits = integerPart.replace(".", "")
        if (digits.isEmpty() || !digits.all { it in '0'..'9' } || !fraction.all { it in '0'..'9' }) return AmountParse.Invalid
        if (integerPart.contains('.') && !integerPart.matches(Regex("\\d{1,3}(\\.\\d{3})+"))) return AmountParse.Invalid
        if (fraction.any { it != '0' }) return AmountParse.Fractional
        val value = BigDecimal(digits)
        if (value > BigDecimal("999999999999999")) return AmountParse.TooLarge
        return AmountParse.Value(if (negative) -value.toLong() else value.toLong())
    }

    sealed interface AmountParse {
        data class Value(val amount: Long) : AmountParse
        data object Missing : AmountParse
        data object Invalid : AmountParse
        data object Fractional : AmountParse
        data object TooLarge : AmountParse
    }

    /**
     * Turns rows into drafts. Negative amounts are expenses and positive amounts income. Imported
     * rows get 12:00 WIB on their date (files carry dates, not times). Rows dated after [today]
     * are rejected. Nothing is written here.
     */
    fun plan(
        table: CsvTable,
        mapping: ImportMapping,
        activeCategories: List<Category>,
        today: LocalDate,
        storedFingerprints: Set<String>,
        existing: Set<ExistingTransactionKey>,
    ): ImportPlan {
        val byName = activeCategories.filter { !it.isArchived }.groupBy { it.type to it.name.trim().lowercase(Locale.ROOT) }
        val occurrences = HashMap<String, Int>()
        return ImportPlan(
            table.rows.map { row ->
                val problems = mutableListOf<RowProblem>()
                val date = cell(row, mapping.dateColumn).let { text ->
                    when {
                        text.isEmpty() -> null.also { problems += RowProblem.MISSING_DATE }
                        else -> try {
                            LocalDate.parse(text, mapping.dateFormat.formatter)
                        } catch (error: DateTimeParseException) {
                            null.also { problems += RowProblem.INVALID_DATE }
                        }
                    }
                }
                if (date != null && date.isAfter(today)) problems += RowProblem.FUTURE_DATE
                val amount = when (val parsed = parseAmount(cell(row, mapping.amountColumn))) {
                    is AmountParse.Value -> parsed.amount.also { if (it == 0L) problems += RowProblem.ZERO_AMOUNT }
                    AmountParse.Missing -> null.also { problems += RowProblem.MISSING_AMOUNT }
                    AmountParse.Invalid -> null.also { problems += RowProblem.INVALID_AMOUNT }
                    AmountParse.Fractional -> null.also { problems += RowProblem.FRACTIONAL_AMOUNT }
                    AmountParse.TooLarge -> null.also { problems += RowProblem.TOO_LARGE }
                }
                if (problems.isNotEmpty() || date == null || amount == null) return@map PlannedRow.Failed(row.line, problems)

                val type = if (amount < 0) TransactionType.EXPENSE else TransactionType.INCOME
                val categoryType = if (type == TransactionType.EXPENSE) CategoryType.EXPENSE else CategoryType.INCOME
                val categoryName = mapping.categoryColumn?.let { cell(row, it).lowercase(Locale.ROOT) }.orEmpty()
                val categoryId = byName[categoryType to categoryName]?.firstOrNull()?.id
                    ?: if (type == TransactionType.EXPENSE) mapping.fallbackExpenseCategoryId else mapping.fallbackIncomeCategoryId
                val description = mapping.descriptionColumn?.let { cell(row, it) }?.takeIf { it.isNotEmpty() }
                val merchant = mapping.merchantColumn?.let { cell(row, it) }?.takeIf { it.isNotEmpty() }
                val draft = TransactionDraft(
                    type = type,
                    walletId = mapping.walletId,
                    destinationWalletId = null,
                    categoryId = categoryId,
                    amount = kotlin.math.abs(amount),
                    transactionDate = JakartaTime.range(date, date).start.plusSeconds(IMPORT_HOUR_SECONDS),
                    description = description,
                    merchant = merchant,
                )
                val base = fingerprint(mapping.walletId, date, type, draft.amount, description, merchant)
                // The n-th identical row gets its own identity, so legitimately repeated rows (two
                // identical purchases on one day) stay importable and a re-import still matches.
                val occurrence = occurrences.merge(base, 1, Int::plus)!! - 1
                val print = if (occurrence == 0) base else "$base#$occurrence"
                when {
                    print in storedFingerprints -> PlannedRow.Duplicate(row.line, draft, print, DuplicateKind.ALREADY_IMPORTED)
                    occurrence > 0 -> PlannedRow.Duplicate(row.line, draft, print, DuplicateKind.REPEATED_IN_FILE)
                    ExistingTransactionKey(date, type, draft.amount) in existing -> PlannedRow.Duplicate(row.line, draft, print, DuplicateKind.POSSIBLE_EXISTING)
                    else -> PlannedRow.Ready(row.line, draft, print)
                }
            },
        )
    }

    /**
     * Stable identity of an imported row, stored as externalId so a re-import is recognized. Text
     * fields are normalized like merchant keys (trim, whitespace, case).
     */
    fun fingerprint(walletId: String, date: LocalDate, type: TransactionType, amount: Long, description: String?, merchant: String?): String {
        fun norm(text: String?) = text?.trim()?.replace(Regex("\\s+"), " ")?.lowercase(Locale.ROOT).orEmpty()
        val source = listOf(walletId, date.toString(), type.name, amount.toString(), norm(description), norm(merchant)).joinToString("\u001F")
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun cell(row: CsvRow, column: Int): String = row.cells.getOrNull(column)?.trim().orEmpty()

    /** 12:00 WIB keeps the transaction well inside its calendar day. */
    private const val IMPORT_HOUR_SECONDS = 12 * 3600L
}
