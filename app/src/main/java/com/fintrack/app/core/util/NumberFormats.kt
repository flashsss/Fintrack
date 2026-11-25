package com.fintrack.app.core.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

private val INDONESIAN = DecimalFormatSymbols.getInstance(Locale.forLanguageTag("id-ID"))

/** One decimal, Indonesian comma: 25.04 -> "25,0%". With [signed], positive values get "+". */
fun formatPercent(value: Double, signed: Boolean = false): String {
    val rounded = BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP)
    val text = DecimalFormat("0.0", INDONESIAN).format(rounded.abs())
    val sign = when {
        rounded.signum() < 0 -> "-"
        signed && rounded.signum() > 0 -> "+"
        else -> ""
    }
    return "$sign$text%"
}

/** Signed rupiah for differences: +Rp1.000 / -Rp1.000 / Rp0. */
fun formatRupiahSigned(amount: Long): String = if (amount > 0) "+${formatRupiah(amount)}" else formatRupiah(amount)

/**
 * Short rupiah for chart axes, e.g. 1_500_000 -> "1,5 jt", 25_000 -> "25 rb", 2_000_000_000 -> "2 M".
 * Units: rb = ribu (10^3), jt = juta (10^6), M = miliar (10^9), T = triliun (10^12).
 */
fun formatRupiahCompact(amount: Long): String {
    val abs = BigDecimal.valueOf(amount).abs()
    val (divisor, unit) = when {
        abs >= BigDecimal("1000000000000") -> BigDecimal("1000000000000") to " T"
        abs >= BigDecimal("1000000000") -> BigDecimal("1000000000") to " M"
        abs >= BigDecimal("1000000") -> BigDecimal("1000000") to " jt"
        abs >= BigDecimal("1000") -> BigDecimal("1000") to " rb"
        else -> BigDecimal.ONE to ""
    }
    val scaled = abs.divide(divisor, 1, RoundingMode.HALF_UP).stripTrailingZeros()
    val text = DecimalFormat("0.#", INDONESIAN).format(scaled)
    return (if (amount < 0) "-" else "") + text + unit
}
