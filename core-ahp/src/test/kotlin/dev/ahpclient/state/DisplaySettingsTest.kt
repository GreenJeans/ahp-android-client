package dev.ahpclient.state

import kotlin.test.Test
import kotlin.test.assertEquals

class DisplaySettingsTest {
    @Test
    fun `a text size is kept inside the range the screens are laid out for`() {
        assertEquals(TextScale.MIN, TextScale.clamp(0.1f))
        assertEquals(TextScale.MAX, TextScale.clamp(9f))
        assertEquals(1.25f, TextScale.clamp(1.25f))
        assertEquals(TextScale.DEFAULT, TextScale.clamp(Float.NaN))
        assertEquals(TextScale.MAX, TextScale.clamp(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `a pinch is rounded to even steps`() {
        assertEquals(1.1f, TextScale.snap(1.12f), 0.0001f)
        assertEquals(1.15f, TextScale.snap(1.13f), 0.0001f)
        assertEquals(TextScale.MIN, TextScale.snap(0.5f))
        assertEquals(TextScale.MAX, TextScale.snap(3f))
        assertEquals(TextScale.DEFAULT, TextScale.snap(1.01f), 0.0001f)
    }

    @Test
    fun `a size is written as a percentage`() {
        assertEquals("100%", TextScale.percent(1f))
        assertEquals("115%", TextScale.percent(1.15f))
        assertEquals("160%", TextScale.percent(5f))
    }

    @Test
    fun `a saved theme is read back, and anything else is the system's`() {
        assertEquals(ThemeMode.DARK, ThemeMode.of("DARK"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.of("LIGHT"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.of(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.of("SEPIA"))
    }
}