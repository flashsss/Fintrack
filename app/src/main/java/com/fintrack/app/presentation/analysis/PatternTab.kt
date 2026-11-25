package com.fintrack.app.presentation.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.fintrack.app.R
import com.fintrack.app.core.util.formatRupiah
import com.fintrack.app.presentation.common.MoneyText
import com.fintrack.app.presentation.common.NoteText
import com.fintrack.app.presentation.common.ProportionBar
import com.fintrack.app.presentation.common.Spacing
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToLong

private val INDONESIAN = Locale.forLanguageTag("id-ID")

@Composable
internal fun PatternTab(report: PatternReport) {
    val pattern = report.weekdays
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Text(stringResource(R.string.pattern_weekday_title), style = MaterialTheme.typography.titleMedium)
        if (pattern.expenseCount == 0) {
            NoteText(stringResource(R.string.pattern_empty))
            return@Column
        }
        val max = pattern.days.maxOf { it.dailyAverage ?: 0.0 }.takeIf { it > 0 } ?: 1.0
        pattern.days.forEach { stat ->
            val average = stat.dailyAverage ?: 0.0
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()) {
                    Text(stat.day.getDisplayName(TextStyle.FULL, INDONESIAN).replaceFirstChar { it.titlecase(INDONESIAN) }, Modifier.weight(1f))
                    MoneyText(formatRupiah(stat.total), style = MaterialTheme.typography.bodyMedium)
                }
                ProportionBar(average * 100 / max)
                NoteText(
                    pluralStringResource(
                        R.plurals.pattern_weekday_detail, stat.count,
                        formatRupiah(average.roundToLong()), stat.count, stat.occurrences,
                    ),
                )
            }
        }
        NoteText(stringResource(R.string.pattern_weekday_note))

        HorizontalDivider()
        Text(stringResource(R.string.pattern_weekend_vs_weekday), style = MaterialTheme.typography.titleMedium)
        DailyAverageRow(stringResource(R.string.pattern_weekend), pattern.weekendDailyAverage)
        DailyAverageRow(stringResource(R.string.pattern_weekday), pattern.weekdayDailyAverage)
    }
}

@Composable
private fun DailyAverageRow(label: String, average: Double?) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        MoneyText(average?.let { stringResource(R.string.pattern_per_day, formatRupiah(it.roundToLong())) } ?: "—", style = MaterialTheme.typography.bodyMedium)
    }
}
