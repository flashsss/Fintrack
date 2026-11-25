package com.fintrack.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberFormatsTest {

    @Test
    fun `percent uses one decimal and Indonesian comma`() {
        assertEquals("25,0%", formatPercent(25.0))
        assertEquals("33,3%", formatPercent(100.0 / 3))
        assertEquals("-12,5%", formatPercent(-12.5))
        assertEquals("+7,1%", formatPercent(7.05, signed = true))
        assertEquals("0,0%", formatPercent(-0.04, signed = true))
    }

    @Test
    fun `signed rupiah for differences`() {
        assertEquals("+Rp1.000", formatRupiahSigned(1_000))
        assertEquals("-Rp1.000", formatRupiahSigned(-1_000))
        assertEquals("Rp0", formatRupiahSigned(0))
    }

    @Test
    fun `compact rupiah for chart axes`() {
        assertEquals("0", formatRupiahCompact(0))
        assertEquals("950", formatRupiahCompact(950))
        assertEquals("25 rb", formatRupiahCompact(25_000))
        assertEquals("1,5 jt", formatRupiahCompact(1_500_000))
        assertEquals("10 jt", formatRupiahCompact(10_000_000))
        assertEquals("2,3 M", formatRupiahCompact(2_250_000_000))
        assertEquals("-1,2 jt", formatRupiahCompact(-1_200_000))
        assertEquals("1 T", formatRupiahCompact(1_000_000_000_000))
    }
}
