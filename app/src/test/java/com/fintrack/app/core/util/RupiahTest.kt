package com.fintrack.app.core.util

import com.fintrack.app.core.util.AmountInput.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class RupiahTest {

    @Test
    fun `formats with Indonesian grouping and no space`() {
        assertEquals("Rp0", formatRupiah(0))
        assertEquals("Rp999", formatRupiah(999))
        assertEquals("Rp1.000", formatRupiah(1_000))
        assertEquals("Rp1.500.000", formatRupiah(1_500_000))
        assertEquals("Rp10.000.000", formatRupiah(10_000_000))
    }

    @Test
    fun `formats negative values with leading minus`() {
        assertEquals("-Rp2.500", formatRupiah(-2_500))
        assertEquals("-Rp9.223.372.036.854.775.808", formatRupiah(Long.MIN_VALUE))
    }

    @Test
    fun `parses grouped and plain digits`() {
        assertEquals(Result.Valid(1_500_000), AmountInput.parse("1.500.000"))
        assertEquals(Result.Valid(1_500_000), AmountInput.parse("1500000"))
        assertEquals(Result.Valid(0), AmountInput.parse("0"))
    }

    @Test
    fun `rejects empty, non-numeric and oversized input`() {
        assertEquals(Result.Empty, AmountInput.parse("  "))
        assertEquals(Result.Invalid, AmountInput.parse("12a"))
        assertEquals(Result.Invalid, AmountInput.parse("1,5"))
        assertEquals(Result.Invalid, AmountInput.parse("-100"))
        assertEquals(Result.Invalid, AmountInput.parse("-"))
        assertEquals(Result.TooLarge, AmountInput.parse("1" + "0".repeat(15)))
        assertEquals(Result.Valid(999_999_999_999_999), AmountInput.parse("9".repeat(15)))
    }

    @Test
    fun `editing format groups digits and strips other characters`() {
        assertEquals("1.500.000", AmountInput.formatForEditing("1500000"))
        assertEquals("1.500.000", AmountInput.formatForEditing("1.500.0.00"))
        assertEquals("25.000", AmountInput.formatForEditing("0025000"))
        assertEquals("", AmountInput.formatForEditing("abc"))
        assertEquals("25.000", AmountInput.formatForEditing("-25000"))
        assertEquals(AmountInput.MAX_DIGITS, AmountInput.formatForEditing("9".repeat(20)).count { it == '9' })
    }

    @Test
    fun `stored amount converts to editing text`() {
        assertEquals("", AmountInput.textFor(0))
        assertEquals("1.500.000", AmountInput.textFor(1_500_000))
    }
}
