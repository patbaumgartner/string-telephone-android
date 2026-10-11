package com.patbaumgartner.android.stringtelephone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

data class StringTelephoneAccents(
    val brand: Color,
    val presence: Color,
    val warning: Color,
    val textMuted: Color,
)

val LocalStringTelephoneAccents = staticCompositionLocalOf {
    StringTelephoneAccents(BrandCyanDeep, BrandCyanDeep, Coral, InkMuted)
}

internal val LightScheme = lightColorScheme(
    primary = BrandCyan,
    onPrimary = OnBrandCyan,
    primaryContainer = CyanContainer,
    onPrimaryContainer = OnBrandCyan,
    inversePrimary = BrandCyanLight,
    background = Porcelain,
    onBackground = Ink,
    surface = Porcelain,
    onSurface = Ink,
    surfaceVariant = PorcelainRaised,
    onSurfaceVariant = InkMuted,
    secondary = BrandCyan,
    onSecondary = OnBrandCyan,
    secondaryContainer = CyanContainer,
    onSecondaryContainer = OnBrandCyan,
    tertiary = BrandCyanDeep,
    onTertiary = Color.White,
    tertiaryContainer = CyanContainer,
    onTertiaryContainer = OnBrandCyan,
    error = Coral,
    onError = Color.White,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    outline = Outline,
    outlineVariant = OutlineVariant,
    surfaceTint = BrandCyanDeep,
    inverseSurface = Ink,
    inverseOnSurface = Porcelain,
    surfaceDim = Color(0xFFE2E9EB),
    surfaceBright = PorcelainRaised,
    surfaceContainerLowest = PorcelainRaised,
    surfaceContainerLow = Color(0xFFF3F8FA),
    surfaceContainer = Color(0xFFEBF3F6),
    surfaceContainerHigh = Color(0xFFE3EEF2),
    surfaceContainerHighest = CyanContainer,
)

internal val DarkScheme = darkColorScheme(
    primary = BrandCyan,
    onPrimary = OnBrandCyan,
    primaryContainer = CyanContainerDark,
    onPrimaryContainer = BrandCyanLight,
    inversePrimary = BrandCyanDeep,
    background = Graphite,
    onBackground = Bone,
    surface = Graphite,
    onSurface = Bone,
    surfaceVariant = GraphiteRaised,
    onSurfaceVariant = BoneMuted,
    secondary = BrandCyanLight,
    onSecondary = OnBrandCyan,
    secondaryContainer = CyanContainerDark,
    onSecondaryContainer = BrandCyanLight,
    tertiary = BrandCyanLight,
    onTertiary = OnBrandCyan,
    tertiaryContainer = CyanContainerDark,
    onTertiaryContainer = BrandCyanLight,
    error = CoralLight,
    onError = OnErrorContainer,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    surfaceTint = BrandCyanLight,
    inverseSurface = Bone,
    inverseOnSurface = Graphite,
    surfaceDim = Graphite,
    surfaceBright = Color(0xFF29383E),
    surfaceContainerLowest = Color(0xFF090E10),
    surfaceContainerLow = GraphiteRaised,
    surfaceContainer = Color(0xFF19262C),
    surfaceContainerHigh = Color(0xFF223139),
    surfaceContainerHighest = CyanContainerDark,
)

private val Trim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun typography(family: FontFamily) = Typography(
    displayLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Light,
        fontSize = 44.sp,
        lineHeight = 50.sp,
        letterSpacing = (-0.8).sp,
        lineHeightStyle = Trim,
    ),
    headlineSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
)

@Composable
fun StringTelephoneTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val accents = if (darkTheme) {
        StringTelephoneAccents(BrandCyanLight, BrandCyanLight, AmberLight, BoneMuted)
    } else {
        StringTelephoneAccents(BrandCyanDeep, BrandCyanDeep, Amber, InkMuted)
    }
    CompositionLocalProvider(LocalStringTelephoneAccents provides accents) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = typography(StringTelephoneFontFamily),
            content = content,
        )
    }
}

object StringTelephoneTheme {
    val accents: StringTelephoneAccents
        @Composable @ReadOnlyComposable get() = LocalStringTelephoneAccents.current
}
