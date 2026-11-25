package com.fintrack.app.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentDecodeTest {
    @Test
    fun `valid UTF-8 is kept`() {
        assertEquals("Café Rp", ContentDocumentTextReader.decode("Café Rp".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `invalid UTF-8 falls back to Windows-1252`() {
        val bytes = byteArrayOf('C'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte())
        assertEquals("Café", ContentDocumentTextReader.decode(bytes))
    }
}
