package com.fintrack.app.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.fintrack.app.R

/**
 * Plus Jakarta Sans (SIL OFL 1.1, license in assets/licenses), an Indonesian typeface. One
 * variable font file bundled in the APK, so it works offline; one entry per weight used.
 */
@OptIn(ExperimentalTextApi::class)
private fun jakarta(weight: FontWeight) =
    Font(R.font.plus_jakarta_sans, weight = weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

val JakartaSans = FontFamily(
    jakarta(FontWeight.Normal),
    jakarta(FontWeight.Medium),
    jakarta(FontWeight.SemiBold),
    jakarta(FontWeight.Bold),
    jakarta(FontWeight.ExtraBold),
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = JakartaSans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

/** Bold, tight display and headline styles for amounts; calm regular body text. */
val FinTrackTypography = Typography(
    displayLarge = style(52, 60, FontWeight.ExtraBold, -0.02),
    displayMedium = style(42, 50, FontWeight.ExtraBold, -0.02),
    displaySmall = style(34, 42, FontWeight.ExtraBold, -0.02),
    headlineLarge = style(30, 38, FontWeight.ExtraBold, -0.015),
    headlineMedium = style(26, 34, FontWeight.ExtraBold, -0.01),
    headlineSmall = style(22, 30, FontWeight.ExtraBold, -0.01),
    titleLarge = style(20, 28, FontWeight.Bold, -0.005),
    titleMedium = style(16, 24, FontWeight.Bold),
    titleSmall = style(14, 20, FontWeight.Bold),
    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(12, 16, FontWeight.Normal),
    labelLarge = style(14, 20, FontWeight.SemiBold),
    labelMedium = style(12, 16, FontWeight.SemiBold),
    labelSmall = style(11, 16, FontWeight.SemiBold),
)
