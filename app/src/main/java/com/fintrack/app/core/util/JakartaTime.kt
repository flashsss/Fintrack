package com.fintrack.app.core.util

import com.fintrack.app.domain.model.DateRange
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reporting and display time rules: instants are stored in UTC and interpreted in Asia/Jakarta.
 * All local-date boundaries are computed here, never with SQLite 'localtime'.
 */
object JakartaTime {
    val ZONE: ZoneId = ZoneId.of("Asia/Jakarta")
    private val LOCALE: Locale = Locale.forLanguageTag("id-ID")
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy", LOCALE)
    private val DAY_HEADER_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", LOCALE)

    fun localDate(instant: Instant): LocalDate = instant.atZone(ZONE).toLocalDate()

    fun today(clock: Clock): LocalDate = LocalDate.now(clock.withZone(ZONE))

    /** All instants of the Jakarta calendar days from [first] to [last], both inclusive. */
    fun range(first: LocalDate, last: LocalDate): DateRange = DateRange(
        start = first.atStartOfDay(ZONE).toInstant(),
        endExclusive = last.plusDays(1).atStartOfDay(ZONE).toInstant(),
    )

    /**
     * True when every Jakarta day from [first] to [last] lasts exactly 24 hours. SQL that groups by
     * `(date - start) / 86_400_000` is only valid then. Asia/Jakarta has had no offset change since
     * 1964; earlier ranges (e.g. across 1 Jan 1964) are rejected instead of being miscounted.
     */
    fun hasUniformDays(first: LocalDate, last: LocalDate): Boolean {
        val range = range(first, last)
        val days = last.toEpochDay() - first.toEpochDay() + 1
        return range.endExclusive.toEpochMilli() - range.start.toEpochMilli() == days * DAY_MILLIS
    }

    const val DAY_MILLIS = 86_400_000L

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH.mm", LOCALE)

    /** "Kamis, 27 November 2026 10.15 WIB" (Indonesian time notation uses a dot). */
    fun formatDateTime(instant: Instant): String = "${formatDate(instant)} ${TIME_FORMAT.format(instant.atZone(ZONE))} WIB"

    private val DAY_MONTH_SHORT = DateTimeFormatter.ofPattern("d MMM", LOCALE)
    private val MONTH_YEAR_SHORT = DateTimeFormatter.ofPattern("MMM yy", LOCALE)

    /** "5 Okt" for chart labels. */
    fun formatDayMonth(date: LocalDate): String = DAY_MONTH_SHORT.format(date)

    /** "Okt 26" for chart labels. */
    fun formatMonthShort(date: LocalDate): String = MONTH_YEAR_SHORT.format(date)

    fun month(month: YearMonth): DateRange = range(month.atDay(1), month.atEndOfMonth())

    /** Moves [instant] to [date] in Jakarta while keeping its Jakarta time of day. */
    fun withDate(instant: Instant, date: LocalDate): Instant =
        instant.atZone(ZONE).with(date).toInstant()

    /** Material date pickers report the selected day as UTC midnight milliseconds. */
    fun pickerMillisToDate(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

    fun dateToPickerMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun formatDate(instant: Instant): String = DATE_FORMAT.format(instant.atZone(ZONE))

    fun formatDate(date: LocalDate): String = DATE_FORMAT.format(date)

    private val DAY_FORMAT = DateTimeFormatter.ofPattern("d", LOCALE)
    private val DAY_MONTH_FORMAT = DateTimeFormatter.ofPattern("d MMM", LOCALE)
    private val MONTH_YEAR_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy", LOCALE)

    /** Compact inclusive span: "1–25 Nov 2026", "28 Nov – 5 Des 2026", "28 Des 2025 – 3 Jan 2026". */
    fun formatSpan(first: LocalDate, last: LocalDate): String = when {
        first == last -> formatDate(first)
        first.year != last.year -> "${formatDate(first)} – ${formatDate(last)}"
        first.month != last.month -> "${DAY_MONTH_FORMAT.format(first)} – ${formatDate(last)}"
        else -> "${DAY_FORMAT.format(first)}–${formatDate(last)}"
    }

    /** "November 2026". */
    fun formatMonth(date: LocalDate): String =
        MONTH_YEAR_FORMAT.format(date).replaceFirstChar { it.titlecase(LOCALE) }

    fun formatDayHeader(date: LocalDate): String =
        DAY_HEADER_FORMAT.format(date).replaceFirstChar { it.titlecase(LOCALE) }
}
