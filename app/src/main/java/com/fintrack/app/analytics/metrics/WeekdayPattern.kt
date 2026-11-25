package com.fintrack.app.analytics.metrics

import java.time.DayOfWeek
import java.time.LocalDate

data class WeekdayTotal(val day: DayOfWeek, val total: Long, val count: Int)

/** One weekday in a span: expense, transactions and how many such calendar days the span has. */
data class WeekdayStat(val day: DayOfWeek, val total: Long, val count: Int, val occurrences: Int) {
    /** Ē_dow = expense / number of these weekdays in the span (zero days included). */
    val dailyAverage: Double? get() = if (occurrences == 0) null else total.toDouble() / occurrences

    /** Average transaction amount on this weekday. */
    val averageTransaction: Double? get() = if (count == 0) null else total.toDouble() / count
}

/** Day-of-week spending normalized by calendar occurrences (docs §5.4). */
data class WeekdayPattern(val days: List<WeekdayStat>) {
    private val weekend get() = days.filter { it.day == DayOfWeek.SATURDAY || it.day == DayOfWeek.SUNDAY }
    private val weekdays get() = days.filter { it.day != DayOfWeek.SATURDAY && it.day != DayOfWeek.SUNDAY }

    val weekendDays: Int get() = weekend.sumOf { it.occurrences }
    val weekdayDays: Int get() = weekdays.sumOf { it.occurrences }
    val expenseCount: Int get() = days.sumOf { it.count }

    val weekendDailyAverage: Double? get() = average(weekend)
    val weekdayDailyAverage: Double? get() = average(weekdays)

    private fun average(group: List<WeekdayStat>): Double? {
        val occurrences = group.sumOf { it.occurrences }
        return if (occurrences == 0) null else group.sumOf { it.total }.toDouble() / occurrences
    }

    companion object {
        /** Combines SQL totals with calendar occurrences of each weekday in [first]..[last]. */
        fun of(totals: List<WeekdayTotal>, first: LocalDate, last: LocalDate): WeekdayPattern {
            val byDay = totals.associateBy { it.day }
            val occurrences = occurrences(first, last)
            return WeekdayPattern(
                DayOfWeek.entries.map { day ->
                    WeekdayStat(day, byDay[day]?.total ?: 0L, byDay[day]?.count ?: 0, occurrences.getValue(day))
                },
            )
        }

        /** Number of each weekday in [first]..[last] inclusive. */
        fun occurrences(first: LocalDate, last: LocalDate): Map<DayOfWeek, Int> {
            val days = (last.toEpochDay() - first.toEpochDay() + 1).toInt().coerceAtLeast(0)
            return DayOfWeek.entries.associateWith { day ->
                val offset = (day.value - first.dayOfWeek.value + 7) % 7
                if (offset >= days) 0 else (days - offset + 6) / 7
            }
        }
    }
}
