package com.fintrack.app.presentation.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fintrack.app.R
import com.fintrack.app.presentation.common.AppCard
import com.fintrack.app.analytics.metrics.CategoryAnalysis
import com.fintrack.app.analytics.metrics.CategoryChange
import com.fintrack.app.core.ui.theme.LocalChartColors
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.core.util.formatPercent
import com.fintrack.app.core.util.formatRupiah
import com.fintrack.app.core.util.formatRupiahSigned
import com.fintrack.app.presentation.common.ArchivedLabel
import com.fintrack.app.presentation.common.MoneyText
import com.fintrack.app.presentation.common.NoteText
import com.fintrack.app.presentation.common.ProportionBar
import com.fintrack.app.presentation.common.Spacing
import com.fintrack.app.presentation.common.categoryLabel

@Composable
internal fun CategoryTab(report: CategoryReport, onSelect: (String) -> Unit) {
    var byParent by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (report.totalExpense == 0L) {
            AppCard { NoteText(stringResource(R.string.analysis_category_empty)) }
            return@Column
        }
        val byId = report.categoriesById
        AppCard {
            val views = listOf(false, true)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                views.forEachIndexed { index, parentView ->
                    SegmentedButton(
                        selected = byParent == parentView,
                        onClick = { byParent = parentView },
                        shape = SegmentedButtonDefaults.itemShape(index, views.size),
                    ) { Text(stringResource(if (parentView) R.string.analysis_category_view_parent else R.string.analysis_category_view_flat)) }
                }
            }
            Text(stringResource(R.string.analysis_category_total), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MoneyText(formatRupiah(report.totalExpense), style = MaterialTheme.typography.titleLarge)
            NoteText(
                stringResource(
                    R.string.analysis_category_vs_previous,
                    JakartaTime.formatSpan(report.reported.first, report.reported.last),
                    JakartaTime.formatSpan(report.previous.first, report.previous.last),
                ) + ". " + stringResource(R.string.analysis_category_tap_hint),
            )

            if (byParent) {
                report.parents.forEach { node ->
                    val category = byId.getValue(node.categoryId)
                    val childrenTotal = node.rolledUp - node.direct
                    CategoryRow(
                        label = category.name,
                        isArchived = category.isArchived,
                        amount = node.rolledUp,
                        total = report.totalExpense,
                        change = CategoryChange(node.categoryId, node.rolledUp, report.previousByParent[node.categoryId] ?: 0L),
                        note = if (childrenTotal > 0) stringResource(R.string.analysis_category_includes_children, formatRupiah(childrenTotal)) else null,
                        selected = report.selectedCategoryId == node.categoryId,
                        onClick = { onSelect(node.categoryId) },
                    )
                }
            } else {
                report.flat.filter { it.categoryId in byId }.forEach { item ->
                    val category = byId.getValue(item.categoryId)
                    CategoryRow(
                        label = categoryLabel(category, byId),
                        isArchived = category.isArchived,
                        amount = item.total,
                        total = report.totalExpense,
                        change = CategoryChange(item.categoryId, item.total, report.previousById[item.categoryId] ?: 0L),
                        note = null,
                        selected = report.selectedCategoryId == item.categoryId,
                        onClick = { onSelect(item.categoryId) },
                    )
                }
            }
            // Outside the hierarchy (addendum B4): never merged into a parent.
            if (report.outside.uncategorized > 0) {
                CategoryRow(stringResource(R.string.dashboard_breakdown_uncategorized), false, report.outside.uncategorized, report.totalExpense, null, null, false, null)
            }
            report.outside.unknown.forEach {
                CategoryRow(stringResource(R.string.analysis_category_unknown), false, it.total, report.totalExpense, null, null, false, null)
            }
            NoteText(stringResource(R.string.analysis_category_percent_note))
        }
        if (report.selectedCategoryId != null && report.selectedCategoryId in byId) {
            AppCard { MonthlyTrend(report) }
        }
        AppCard {
            Movers(report)
        }
    }
}

@Composable
private fun CategoryRow(
    label: String,
    isArchived: Boolean,
    amount: Long,
    total: Long,
    change: CategoryChange?,
    note: String?,
    selected: Boolean,
    onClick: (() -> Unit)?,
) {
    val share = amount * 100.0 / total
    val clickModifier = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Column(
        Modifier
            .fillMaxWidth()
            .then(clickModifier)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(vertical = Spacing.small, horizontal = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                if (isArchived) ArchivedLabel()
            }
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(formatRupiah(amount), style = MaterialTheme.typography.bodyLarge)
                Text(formatPercent(share), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ProportionBar(share)
        if (change != null) {
            val percent = change.percent(CategoryAnalysis.PERCENT_BASELINE)
            val text = formatRupiahSigned(change.absolute) + (percent?.let { " (${formatPercent(it, signed = true)})" } ?: "")
            NoteText(text)
        }
        note?.let { NoteText(it) }
    }
}

@Composable
private fun MonthlyTrend(report: CategoryReport) {
    val category = report.categoriesById.getValue(report.selectedCategoryId!!)
    Text(
        stringResource(R.string.analysis_category_monthly_title, categoryLabel(category, report.categoriesById)),
        style = MaterialTheme.typography.titleMedium,
    )
    val max = report.selectedMonthly.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1L
    report.selectedMonthly.forEachIndexed { index, (month, amount) ->
        val partial = index == report.selectedMonthly.lastIndex && report.lastMonthIsPartial || index == 0 && report.reported.first.dayOfMonth != 1
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(JakartaTime.formatMonthShort(month.atDay(1)) + if (partial) "*" else "", Modifier.padding(end = 4.dp), style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.weight(1f)) { ProportionBar(amount * 100.0 / max) }
            MoneyText(formatRupiah(amount), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (report.lastMonthIsPartial || report.reported.first.dayOfMonth != 1) NoteText(stringResource(R.string.analysis_partial_note))
}

@Composable
private fun Movers(report: CategoryReport) {
    val known = report.movers.filter { it.categoryId in report.categoriesById }
    val increases = known.filter { it.absolute > 0 }.take(3)
    val decreases = known.filter { it.absolute < 0 }.take(3)
    if (increases.isEmpty() && decreases.isEmpty()) {
        NoteText(stringResource(R.string.analysis_category_no_movers))
        return
    }
    listOf(R.string.analysis_category_increases to increases, R.string.analysis_category_decreases to decreases).forEach { (title, items) ->
        if (items.isEmpty()) return@forEach
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        items.forEach { change ->
            val category = report.categoriesById.getValue(change.categoryId)
            val percent = change.percent(CategoryAnalysis.PERCENT_BASELINE)
            Row(Modifier.fillMaxWidth()) {
                Text(categoryLabel(category, report.categoriesById), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                MoneyText(
                    formatRupiahSigned(change.absolute) + (percent?.let { " (${formatPercent(it, signed = true)})" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
