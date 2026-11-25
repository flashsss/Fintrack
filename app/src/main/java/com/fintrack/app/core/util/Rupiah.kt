package com.fintrack.app.core.util

/** Formats integer rupiah with Indonesian grouping: 1500000 -> "Rp1.500.000", -2500 -> "-Rp2.500". */
fun formatRupiah(amount: Long): String {
    val digits = amount.toString().removePrefix("-")
    val sign = if (amount < 0) "-" else ""
    return "${sign}Rp${groupThousands(digits)}"
}

/** Inserts '.' every three digits from the right. [digits] must contain only 0-9. */
fun groupThousands(digits: String): String =
    digits.reversed().chunked(3).joinToString(".").reversed()

/**
 * Parsing of non-negative rupiah amounts typed by the user. Accepts digits and '.' group
 * separators only. Signs are never typed: direction comes from the transaction type, and a
 * negative wallet initial balance is chosen with an explicit switch.
 */
object AmountInput {

    /** 15 digits (below Rp1.000.000.000.000.000) keeps every sum far from Long overflow. */
    const val MAX_DIGITS = 15

    sealed interface Result {
        data class Valid(val amount: Long) : Result
        data object Empty : Result
        data object Invalid : Result
        data object TooLarge : Result
    }

    fun parse(text: String): Result {
        val body = text.trim().replace(".", "")
        if (body.isEmpty()) return Result.Empty
        if (!body.all { it in '0'..'9' }) return Result.Invalid
        val significant = body.trimStart('0').ifEmpty { "0" }
        if (significant.length > MAX_DIGITS) return Result.TooLarge
        return Result.Valid(significant.toLong())
    }

    /** Normalizes keyboard input for display: digits only, no leading zeros, grouped with '.'. */
    fun formatForEditing(raw: String): String {
        val digits = raw.filter { it in '0'..'9' }.trimStart('0').take(MAX_DIGITS)
        return if (digits.isEmpty()) "" else groupThousands(digits)
    }

    /** Editing text for a stored amount; zero is shown as an empty field. */
    fun textFor(amount: Long): String = if (amount == 0L) "" else groupThousands(amount.toString())
}
