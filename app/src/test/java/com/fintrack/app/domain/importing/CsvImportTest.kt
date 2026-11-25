package com.fintrack.app.domain.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvImportTest {

    private fun table(text: String) = (CsvImport.parse(text) as CsvParseResult.Success).table

    @Test
    fun `comma file with header`() {
        val t = table("Tanggal,Jumlah,Keterangan\n01/11/2026,-25000,Kopi\n02/11/2026,5000000,Gaji\n")
        assertEquals(listOf("Tanggal", "Jumlah", "Keterangan"), t.header)
        assertEquals(listOf("01/11/2026", "-25000", "Kopi"), t.rows[0].cells)
        assertEquals(2, t.rows.size)
    }

    @Test
    fun `semicolon delimiter is detected so Indonesian amounts keep their commas`() {
        val t = table("Tanggal;Jumlah;Keterangan\n01/11/2026;-1.500.000,00;Sewa, November\n")
        assertEquals(listOf("01/11/2026", "-1.500.000,00", "Sewa, November"), t.rows.single().cells)
    }

    @Test
    fun `quoted fields keep delimiters and escaped quotes`() {
        val t = table("a,b\n\"1.500.000,00\",\"Toko \"\"Maju\"\", Bandung\"\n")
        assertEquals(listOf("1.500.000,00", "Toko \"Maju\", Bandung"), t.rows.single().cells)
    }

    @Test
    fun `byte order mark is removed from the first header`() {
        assertEquals("Tanggal", table("﻿Tanggal,Jumlah\n01/11/2026,1\n").header.first())
    }

    @Test
    fun `blank lines are skipped and line numbers follow records`() {
        val t = table("a,b\n\n1,2\n\n3,4\n")
        assertEquals(listOf(listOf("1", "2"), listOf("3", "4")), t.rows.map { it.cells })
        assertTrue(t.rows[0].line < t.rows[1].line)
    }

    @Test
    fun `empty file and header only are empty`() {
        assertEquals(CsvParseResult.Empty, CsvImport.parse(""))
        assertEquals(CsvParseResult.Empty, CsvImport.parse("\n  \n"))
        assertEquals(CsvParseResult.Empty, CsvImport.parse("Tanggal,Jumlah\n"))
    }

    @Test
    fun `files over the row limit are rejected, not truncated`() {
        val text = buildString {
            append("a,b\n")
            repeat(CsvImport.MAX_ROWS + 1) { append("$it,1\n") }
        }
        assertEquals(CsvParseResult.TooManyRows(CsvImport.MAX_ROWS), CsvImport.parse(text))
        val atLimit = text.lines().take(CsvImport.MAX_ROWS + 1).joinToString("\n")
        assertEquals(CsvImport.MAX_ROWS, table(atLimit).rows.size)
    }

    @Test
    fun `unterminated quote is malformed`() {
        assertTrue(CsvImport.parse("a,b\n\"1,2\n") is CsvParseResult.Malformed)
    }
}
