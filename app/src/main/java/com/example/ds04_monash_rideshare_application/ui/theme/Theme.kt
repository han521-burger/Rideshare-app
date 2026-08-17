package com.example.ds04_monash_rideshare_application.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = MonashBlueLight,
    secondary = RideshareGreenLight,
    tertiary = Color(0xFFFFB74D),
    background = Color(0xFF081B2A),
    surface = DarkSurface,
    onPrimary = MonashBlueDark
)

private val LightColorScheme = lightColorScheme(
    primary = MonashBlue,
    secondary = RideshareGreen,
    tertiary = Color(0xFFF57C00),
    background = Cloud,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Slate,
    onSurface = Slate
)

@Composable
fun Ds04_monash_rideshare_applicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
