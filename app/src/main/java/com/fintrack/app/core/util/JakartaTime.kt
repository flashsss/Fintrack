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

    fun formatDayHeader(date: LocalDate): String =
        DAY_HEADER_FORMAT.format(date).replaceFirstChar { it.titlecase(LOCALE) }
}
