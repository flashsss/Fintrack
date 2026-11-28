package com.fintrack.app.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fintrack.app.core.ui.theme.LocalChartColors
import com.fintrack.app.core.ui.theme.LocalFinColors

/** Corner radii of the design (Phase 8.4): cards 24, hero 28, tiles 20, icon chips 14. */
object Corners {
    val card = 24.dp
    val hero = 28.dp
    val tile = 20.dp
    val chip = 14.dp
}

/** A white (light) / raised dark card holding one section. [onClick] makes the whole card a button. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Corners.card)
    val color = LocalFinColors.current.card
    val inner: @Composable () -> Unit = {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
    if (onClick != null) Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = color, content = inner)
    else Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = color, content = inner)
}

/** Card title with an optional trailing element (e.g. a "Lihat semua" link or a short note). */
@Composable
fun CardHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
        trailing()
    }
}

enum class TileTone { INCOME, EXPENSE }

/** Tinted income or expense tile with a bold amount (green and orange also differ in lightness). */
@Composable
fun StatTile(label: String, value: String, tone: TileTone, modifier: Modifier = Modifier) {
    val fin = LocalFinColors.current
    val (container, labelColor, valueColor) = when (tone) {
        TileTone.INCOME -> Triple(fin.incomeContainer, fin.incomeLabel, fin.onIncomeContainer)
        TileTone.EXPENSE -> Triple(fin.expenseContainer, fin.expenseLabel, fin.onExpenseContainer)
    }
    Column(
        modifier.clip(RoundedCornerShape(Corners.tile)).background(container).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = labelColor)
        MoneyText(value, style = MaterialTheme.typography.titleLarge, color = valueColor, maxLines = 1)
    }
}

data class PillOption<T>(val value: T, val label: String)

/**
 * Single-choice segmented pills (radio group semantics). Each pill is at least 44dp tall.
 * [onSelect] also fires for the already selected pill, so a "Pilih" pill can reopen its picker.
 */
@Composable
fun <T> PeriodPills(
    options: List<PillOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(scheme.primaryContainer)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val isSelected = option.value == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) scheme.primary else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option.value) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) scheme.onPrimary else scheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/**
 * Rounded square with the first letter of [label], tinted by a stable palette slot, so categories
 * and wallets are recognisable at a glance. Decorative: the label is always shown next to it.
 */
@Composable
fun LetterChip(label: String, modifier: Modifier = Modifier, colorIndex: Int = stableIndex(label), size: Dp = 40.dp) {
    val palette = LocalChartColors.current.categories
    val color = palette[Math.floorMod(colorIndex, palette.size)]
    Box(
        modifier.size(size).clip(RoundedCornerShape(Corners.chip)).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.trim().firstOrNull()?.uppercase() ?: "•",
            style = MaterialTheme.typography.titleMedium,
            color = color,
        )
    }
}

/** Same label → same color on every screen and device. */
fun stableIndex(label: String): Int = label.trim().lowercase().hashCode()

/** Rounded progress bar; [fraction] is clamped to 0..1, the label carries the exact figure. */
@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val track = LocalFinColors.current.track
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).clip(CircleShape).background(color))
    }
}
