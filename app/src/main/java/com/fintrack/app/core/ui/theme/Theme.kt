package com.fintrack.app.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Calm teal palette with neutral surfaces. Every role is set explicitly so no baseline (purple)
 * tones leak into containers. Text/background pairs follow Material tonal contrast (≥ 4.5:1).
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF006A60),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9EF2E4),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E2),
    onSecondaryContainer = Color(0xFF05201C),
    tertiary = Color(0xFF456179),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCCE5FF),
    onTertiaryContainer = Color(0xFF001E31),
    background = Color(0xFFF7FAF9),
    onBackground = Color(0xFF171D1C),
    surface = Color(0xFFF7FAF9),
    onSurface = Color(0xFF171D1C),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4947),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFBEC9C6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5F3),
    surfaceContainer = Color(0xFFEBEFED),
    surfaceContainerHigh = Color(0xFFE5EAE8),
    surfaceContainerHighest = Color(0xFFE0E4E2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF82D5C8),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFF9EF2E4),
    secondary = Color(0xFFB1CCC6),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B47),
    onSecondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFFADCAE6),
    onTertiary = Color(0xFF153349),
    tertiaryContainer = Color(0xFF2D4961),
    onTertiaryContainer = Color(0xFFCCE5FF),
    background = Color(0xFF0F1514),
    onBackground = Color(0xFFDEE4E1),
    surface = Color(0xFF0F1514),
    onSurface = Color(0xFFDEE4E1),
    surfaceVariant = Color(0xFF3F4947),
    onSurfaceVariant = Color(0xFFBEC9C6),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4947),
    surfaceContainerLowest = Color(0xFF0A0F0E),
    surfaceContainerLow = Color(0xFF171D1C),
    surfaceContainer = Color(0xFF1B2120),
    surfaceContainerHigh = Color(0xFF252B2A),
    surfaceContainerHighest = Color(0xFF303635),
)

/**
 * Data-visualization colors. Each light color has at least 3:1 contrast on the light surface and
 * each dark color on the dark surface, and adjacent categorical colors differ in hue.
 */
@Immutable
data class ChartColors(
    val income: Color,
    val expense: Color,
    /** Categorical palette for the top five categories; [others] is for the merged remainder. */
    val categories: List<Color>,
    val others: Color,
)

private val LightChartColors = ChartColors(
    income = Color(0xFF00796B),
    expense = Color(0xFFC0503E),
    categories = listOf(
        Color(0xFF00796B),
        Color(0xFF3D6DB5),
        Color(0xFFB86E00),
        Color(0xFF8A4FA6),
        Color(0xFFB8475E),
    ),
    others = Color(0xFF7A8582),
)

private val DarkChartColors = ChartColors(
    income = Color(0xFF4FD1C0),
    expense = Color(0xFFF2937F),
    categories = listOf(
        Color(0xFF4FD1C0),
        Color(0xFF8AB4F8),
        Color(0xFFF0B05A),
        Color(0xFFCE9BE6),
        Color(0xFFF08CA0),
    ),
    others = Color(0xFFA3ADAA),
)

val LocalChartColors = staticCompositionLocalOf { LightChartColors }

@Composable
fun FinTrackTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalChartColors provides if (darkTheme) DarkChartColors else LightChartColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}
