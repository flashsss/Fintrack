package com.fintrack.app.presentation.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.fintrack.app.R
import com.fintrack.app.analytics.insights.Insight
import com.fintrack.app.analytics.insights.Reason
import com.fintrack.app.analytics.insights.RuleOutcome
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.core.util.formatPercent
import com.fintrack.app.core.util.formatRupiah
import com.fintrack.app.core.util.formatRupiahSigned
import com.fintrack.app.presentation.common.NoteText
import com.fintrack.app.presentation.common.Spacing
import com.fintrack.app.presentation.common.categoryLabel

@Composable
internal fun InsightsTab(report: InsightReport) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.field),
    ) {
        NoteText(
            stringResource(
                R.string.insights_intro,
                JakartaTime.formatSpan(report.reported.first, report.reported.last),
                JakartaTime.formatSpan(report.previous.first, report.previous.last),
            ),
        )
        Text(stringResource(R.string.insights_found_title), style = MaterialTheme.typography.titleMedium)
        val increases = (report.categoryIncreases as? RuleOutcome.Fired)?.value.orEmpty()
        val drop = (report.savingRateDrop as? RuleOutcome.Fired)?.value
        val weekend = (report.weekendSpending as? RuleOutcome.Fired)?.value
        if (increases.isEmpty() && drop == null && weekend == null) NoteText(stringResource(R.string.insights_none_found))
        increases.forEach { insight -> CategoryIncreaseCard(insight, report) }
        drop?.let { SavingRateDropCard(it) }
        weekend?.let { WeekendCard(it) }

        HorizontalDivider()
        Text(stringResource(R.string.insights_rules_title), style = MaterialTheme.typography.titleMedium)
        RuleStatus(stringResource(R.string.insight_i1_rule), report.categoryIncreases)
        RuleStatus(stringResource(R.string.insight_i5_rule), report.savingRateDrop)
        RuleStatus(stringResource(R.string.insight_i2_rule), report.weekendSpending)
    }
}

@Composable
private fun CategoryIncreaseCard(insight: Insight.CategoryIncrease, report: InsightReport) {
    val label = report.categoriesById[insight.categoryId]?.let { categoryLabel(it, report.categoriesById) }
        ?: stringResource(R.string.analysis_category_unknown)
    InsightCard(
        text = stringResource(R.string.insight_i1_text, label, formatPercent(insight.percent), formatRupiahSigned(insight.current - insight.previous)),
        basis = pluralStringResource(R.plurals.insight_i1_basis, insight.count, formatRupiah(insight.current), insight.count, formatRupiah(insight.previous)),
    )
}

@Composable
private fun SavingRateDropCard(insight: Insight.SavingRateDrop) {
    InsightCard(
        text = stringResource(
            R.string.insight_i5_text,
            formatPercent(-insight.pointChange).removeSuffix("%"),
            formatPercent(insight.previousPercent),
            formatPercent(insight.currentPercent),
        ),
        basis = null,
    )
}

@Composable
private fun WeekendCard(insight: Insight.WeekendSpending) {
    val ratio = java.text.DecimalFormat("0.0", java.text.DecimalFormatSymbols(java.util.Locale.forLanguageTag("id-ID"))).format(insight.ratio)
    InsightCard(
        text = stringResource(
            if (insight.ratio >= 1) R.string.insight_i2_text_higher else R.string.insight_i2_text_lower,
            ratio,
            formatRupiah(kotlin.math.round(insight.weekendDailyAverage).toLong()),
            formatRupiah(kotlin.math.round(insight.weekdayDailyAverage).toLong()),
        ),
        basis = null,
    )
}

@Composable
private fun InsightCard(text: String, basis: String?) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            basis?.let { NoteText(it) }
        }
    }
}

@Composable
private fun RuleStatus(rule: String, outcome: RuleOutcome<*>) {
    Column {
        Text(rule, style = MaterialTheme.typography.bodyMedium)
        NoteText(
            when (outcome) {
                is RuleOutcome.Fired -> stringResource(R.string.insights_found_title)
                RuleOutcome.NoFinding -> stringResource(R.string.insight_status_no_finding)
                is RuleOutcome.NotEnoughData -> stringResource(outcome.reason.messageRes)
            },
        )
    }
}

private val Reason.messageRes: Int
    get() = when (this) {
        Reason.NO_ELIGIBLE_CATEGORY -> R.string.insight_reason_no_eligible_category
        Reason.NO_CURRENT_INCOME -> R.string.insight_reason_no_current_income
        Reason.LOW_PREVIOUS_INCOME -> R.string.insight_reason_low_previous_income
        Reason.SHORT_PERIOD -> R.string.insight_reason_short_period
        Reason.FEW_EXPENSES -> R.string.insight_reason_few_expenses
        Reason.ONE_SIDED_SPENDING -> R.string.insight_reason_one_sided
    }
