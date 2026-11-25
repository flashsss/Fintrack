package com.fintrack.app.domain.importing

import org.apache.commons.csv.CSVFormat
import java.io.Reader
import java.io.StringReader

/** A parsed CSV file: header names and data rows (1-based [CsvRow.line] as shown to the user). */
data class CsvTable(val header: List<String>, val rows: List<CsvRow>)

data class CsvRow(val line: Int, val cells: List<String>)

sealed interface CsvParseResult {
    data class Success(val table: CsvTable) : CsvParseResult
    data object Empty : CsvParseResult
    data class TooManyRows(val limit: Int) : CsvParseResult
    data class Malformed(val message: String) : CsvParseResult
}

object CsvImport {
    /** Bounds memory and review effort; larger files are rejected, never silently truncated. */
    const val MAX_ROWS = 10_000

    /** Detects ';' (common in Indonesian bank exports) or ',' from the header line. */
    fun detectDelimiter(firstLine: String): Char =
        if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','

    fun parse(text: String): CsvParseResult {
        val content = text.removePrefix("\uFEFF") // UTF-8 BOM written by spreadsheet apps
        val firstLine = content.lineSequence().firstOrNull { it.isNotBlank() } ?: return CsvParseResult.Empty
        return parse(StringReader(content), detectDelimiter(firstLine))
    }

    private fun parse(reader: Reader, delimiter: Char): CsvParseResult {
        val format = CSVFormat.RFC4180.builder().setDelimiter(delimiter).setIgnoreEmptyLines(true).setTrim(true).get()
        return try {
            format.parse(reader).use { parser ->
                val records = parser.iterator()
                if (!records.hasNext()) return CsvParseResult.Empty
                val header = records.next().toList()
                val rows = mutableListOf<CsvRow>()
                while (records.hasNext()) {
                    val record = records.next()
                    if (rows.size == MAX_ROWS) return CsvParseResult.TooManyRows(MAX_ROWS)
                    rows += CsvRow(line = record.recordNumber.toInt(), cells = record.toList())
                }
                if (rows.isEmpty()) CsvParseResult.Empty else CsvParseResult.Success(CsvTable(header, rows))
            }
        } catch (error: java.io.UncheckedIOException) {
            CsvParseResult.Malformed(error.cause?.message ?: error.message.orEmpty())
        } catch (error: java.io.IOException) {
            CsvParseResult.Malformed(error.message.orEmpty())
        }
    }
}
