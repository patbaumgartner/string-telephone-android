package com.patbaumgartner.android.stringtelephone.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Test
import kotlin.test.assertTrue

class ThemeContrastTest {
    @Test
    fun `light theme text and controls have readable contrast`() {
        verifyTextContrast(LightScheme)
        assertContrast(BrandCyanDeep, LightScheme.background)
    }

    @Test
    fun `dark theme text and controls have readable contrast`() {
        verifyTextContrast(DarkScheme)
        assertContrast(BrandCyanLight, DarkScheme.background)
    }

    private fun verifyTextContrast(scheme: ColorScheme) {
        with(scheme) {
            assertContrast(onPrimary, primary)
            assertContrast(onPrimaryContainer, primaryContainer)
            assertContrast(onSecondaryContainer, secondaryContainer)
            assertContrast(onSurface, surface)
            assertContrast(onSurfaceVariant, surfaceVariant)
            assertContrast(onError, error)
            assertContrast(error, background)
            assertContrast(onErrorContainer, errorContainer)
            assertContrast(onSurface, surfaceContainerHigh)
        }
    }

    private fun assertContrast(foreground: Color, background: Color) {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        val ratio = (lighter + 0.05f) / (darker + 0.05f)
        assertTrue(ratio >= 4.5f, "$foreground on $background has contrast $ratio, below 4.5:1")
    }
}
