package dev.ahpclient.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import dev.ahpclient.state.ThemeMode

@Composable
fun AhpTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val darkTheme = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    AhpTheme(darkTheme, content)
}

@Composable
fun AhpTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val colors = if (darkTheme) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors, content = content)
}
