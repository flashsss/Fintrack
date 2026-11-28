package com.fintrack.app.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * Design direction (Phase 8.4): light = "Tenang" (calm teal on a soft neutral ground, white
 * cards); dark = "Gelap premium" (deep green-black ground, mint accent). Every role is set so no
 * baseline tones leak in. Text pairs are ≥ 4.5:1; see FinColors for the app's own roles.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEDE6),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDEDE6),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF1F4FA8),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCE8FF),
    onTertiaryContainer = Color(0xFF001A43),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF3F6F5),
    onBackground = Color(0xFF14201E),
    surface = Color(0xFFF3F6F5),
    onSurface = Color(0xFF14201E),
    surfaceVariant = Color(0xFFDDE5E2),
    onSurfaceVariant = Color(0xFF52605D),
    outline = Color(0xFF6F7C79),
    outlineVariant = Color(0xFFD5DEDB),
    inverseSurface = Color(0xFF14201E),
    inverseOnSurface = Color(0xFFEFF3F2),
    inversePrimary = Color(0xFF5EE6C0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE6ECEA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5EE6C0),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF1F3A33),
    onPrimaryContainer = Color(0xFFB4F5E2),
    secondary = Color(0xFFA9C9C1),
    onSecondary = Color(0xFF15332C),
    secondaryContainer = Color(0xFF1F3A33),
    onSecondaryContainer = Color(0xFFB4F5E2),
    tertiary = Color(0xFF8AB4FF),
    onTertiary = Color(0xFF002E6B),
    tertiaryContainer = Color(0xFF2A2540),
    onTertiaryContainer = Color(0xFFDDD6FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1513),
    onBackground = Color(0xFFE8F0EE),
    surface = Color(0xFF0E1513),
    onSurface = Color(0xFFE8F0EE),
    surfaceVariant = Color(0xFF2A3633),
    onSurfaceVariant = Color(0xFF9AABA6),
    outline = Color(0xFF6F807B),
    outlineVariant = Color(0xFF2A3633),
    inverseSurface = Color(0xFFE8F0EE),
    inverseOnSurface = Color(0xFF14201E),
    inversePrimary = Color(0xFF00695C),
    surfaceContainerLowest = Color(0xFF0A100E),
    surfaceContainerLow = Color(0xFF17201E),
    surfaceContainer = Color(0xFF17201E),
    surfaceContainerHigh = Color(0xFF1F2A27),
    surfaceContainerHighest = Color(0xFF2A3633),
)

/** Large, soft corners: cards 24dp, hero 28dp, chips and fields 12–16dp. */
val FinTrackShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * App-specific color roles on top of the Material scheme: cards, the hero balance card, tinted
 * income/expense tiles and the add button. Labels on tinted tiles are ≥ 4.5:1 on their tile.
 */
@Immutable
data class FinColors(
    val card: Color,
    val hero: Color,
    val onHero: Color,
    val heroInner: Color,
    val incomeContainer: Color,
    val onIncomeContainer: Color,
    val incomeLabel: Color,
    val expenseContainer: Color,
    val onExpenseContainer: Color,
    val expenseLabel: Color,
    val income: Color,
    val expense: Color,
    val fab: Color,
    val onFab: Color,
    val track: Color,
)

private val LightFinColors = FinColors(
    card = Color(0xFFFFFFFF),
    hero = Color(0xFF00695C),
    onHero = Color(0xFFFFFFFF),
    heroInner = Color(0x24FFFFFF),
    incomeContainer = Color(0xFFDDF5E6),
    onIncomeContainer = Color(0xFF0B3D25),
    incomeLabel = Color(0xFF11603A),
    expenseContainer = Color(0xFFFFE3D6),
    onExpenseContainer = Color(0xFF5A200A),
    expenseLabel = Color(0xFF8C3512),
    income = Color(0xFF00695C),
    expense = Color(0xFF9A3B12),
    fab = Color(0xFF14201E),
    onFab = Color(0xFFFFFFFF),
    track = Color(0xFFE3EBE9),
)

private val DarkFinColors = FinColors(
    card = Color(0xFF17201E),
    hero = Color(0xFF1F3A33),
    onHero = Color(0xFFE8F0EE),
    heroInner = Color(0x1FFFFFFF),
    incomeContainer = Color(0xFF173A2C),
    onIncomeContainer = Color(0xFFC9F2DA),
    incomeLabel = Color(0xFF8FE0B5),
    expenseContainer = Color(0xFF3A2A20),
    onExpenseContainer = Color(0xFFFFDCCB),
    expenseLabel = Color(0xFFFFB08A),
    income = Color(0xFF5EE6C0),
    expense = Color(0xFFFFB08A),
    fab = Color(0xFF5EE6C0),
    onFab = Color(0xFF0E1513),
    track = Color(0xFF2A3633),
)

val LocalFinColors = staticCompositionLocalOf { LightFinColors }

/**
 * Data-visualization colors. Each light color has at least 3:1 contrast on the light card and
 * each dark color on the dark card, and adjacent categorical colors differ in hue and lightness.
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
    expense = Color(0xFFC2551F),
    categories = listOf(
        Color(0xFF00796B),
        Color(0xFF2F66CC),
        Color(0xFFB86E00),
        Color(0xFF8A4FA6),
        Color(0xFFB8475E),
    ),
    others = Color(0xFF7A8582),
)

private val DarkChartColors = ChartColors(
    income = Color(0xFF5EE6C0),
    expense = Color(0xFFFFB08A),
    categories = listOf(
        Color(0xFF5EE6C0),
        Color(0xFF8AB4FF),
        Color(0xFFF2C14E),
        Color(0xFFC9A7FF),
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
    CompositionLocalProvider(
        LocalChartColors provides if (darkTheme) DarkChartColors else LightChartColors,
        LocalFinColors provides if (darkTheme) DarkFinColors else LightFinColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = FinTrackTypography,
            shapes = FinTrackShapes,
            content = content,
        )
    }
}
