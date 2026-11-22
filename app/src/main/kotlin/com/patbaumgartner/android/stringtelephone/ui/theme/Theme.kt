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

/** Semantic colours that Material's scheme has no slot for. */
data class StringTelephoneAccents(
    val brand: Color,
    val presence: Color,
    val warning: Color,
    val textMuted: Color,
)

/** Messaging colours: a tinted wallpaper with cyan outgoing and neutral incoming bubbles. */
data class ChatColors(
    val wallpaper: Color,
    val outgoing: Color,
    val incoming: Color,
    val onBubble: Color,
    val meta: Color,
)

internal val LightChatColors = ChatColors(ChatWallpaper, BubbleOutgoing, PorcelainRaised, Ink, InkMuted)
internal val DarkChatColors = ChatColors(ChatWallpaperDark, BubbleOutgoingDark, BubbleIncomingDark, Bone, BoneMuted)

val LocalStringTelephoneAccents = staticCompositionLocalOf {
    StringTelephoneAccents(BrandCyanDeep, BrandCyanDeep, Coral, InkMuted)
}

val LocalChatColors = staticCompositionLocalOf { LightChatColors }

internal val LightScheme = lightColorScheme(
    primary = BrandCyan,
    onPrimary = OnBrandCyan,
    primaryContainer = BrandCyanLight,
    onPrimaryContainer = OnBrandCyan,
    secondary = BrandCyanDeep,
    onSecondary = OnBrandCyan,
    secondaryContainer = Color(0xFFD7F3FC),
    onSecondaryContainer = OnBrandCyan,
    tertiaryContainer = Color(0xFFD7F3FC),
    onTertiaryContainer = OnBrandCyan,
    outline = Color(0xFF6C797E),
    outlineVariant = Color(0xFFCEDDE2),
    background = Porcelain,
    onBackground = Ink,
    surface = Porcelain,
    onSurface = Ink,
    surfaceContainerHighest = Color(0xFFE2E8E8),
    surfaceVariant = PorcelainRaised,
    onSurfaceVariant = InkMuted,
    error = CoralDeep,
    // Messaging-specific slots beyond the shared scheme.
    tertiary = BrandCyanDeep,
    onTertiary = Color.White,
    onError = Color.White,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    surfaceTint = BrandCyanDeep,
    inversePrimary = BrandCyanLight,
    inverseSurface = Ink,
    inverseOnSurface = Porcelain,
    surfaceDim = Color(0xFFE2E9EB),
    surfaceBright = PorcelainRaised,
    surfaceContainerLowest = PorcelainRaised,
    surfaceContainerLow = Color(0xFFF3F8FA),
    surfaceContainer = Color(0xFFEBF3F6),
    surfaceContainerHigh = Color(0xFFE3EEF2),
)

internal val DarkScheme = darkColorScheme(
    primary = BrandCyan,
    onPrimary = OnBrandCyan,
    primaryContainer = BrandCyanDeep,
    onPrimaryContainer = Color.White,
    secondary = BrandCyanLight,
    onSecondary = OnBrandCyan,
    secondaryContainer = Color(0xFF123642),
    onSecondaryContainer = BrandCyanLight,
    tertiaryContainer = Color(0xFF123642),
    onTertiaryContainer = BrandCyanLight,
    outline = Color(0xFF8A9BA1),
    outlineVariant = Color(0xFF3B4B51),
    background = Graphite,
    onBackground = Bone,
    surface = Graphite,
    onSurface = Bone,
    surfaceContainerHighest = Color(0xFF3B4B51),
    surfaceVariant = GraphiteRaised,
    onSurfaceVariant = BoneMuted,
    error = CoralLight,
    // Messaging-specific slots beyond the shared scheme.
    tertiary = BrandCyanLight,
    onTertiary = OnBrandCyan,
    onError = OnErrorContainer,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    surfaceTint = BrandCyanLight,
    inversePrimary = BrandCyanDeep,
    inverseSurface = Bone,
    inverseOnSurface = Graphite,
    surfaceDim = Graphite,
    surfaceBright = Color(0xFF29383E),
    surfaceContainerLowest = Color(0xFF090E10),
    surfaceContainerLow = GraphiteRaised,
    surfaceContainer = Color(0xFF19262C),
    surfaceContainerHigh = Color(0xFF223139),
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
    CompositionLocalProvider(
        LocalStringTelephoneAccents provides accents,
        LocalChatColors provides if (darkTheme) DarkChatColors else LightChatColors,
    ) {
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

    val chat: ChatColors
        @Composable @ReadOnlyComposable get() = LocalChatColors.current
}
