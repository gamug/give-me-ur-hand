package com.givemeurhand.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CalmGreen = Color(0xFF4E9E8E)
val CalmGreenLight = Color(0xFFB7E4D8)
val WarmWhite = Color(0xFFFAF7F2)
val CalmTextDark = Color(0xFF2E3A3A)

// Text-on-color choices below were verified against WCAG AA's 4.5:1 minimum contrast ratio
// for normal text:
//   - White on CalmGreen        ~3.2:1  (fails)
//   - CalmTextDark on CalmGreen ~3.7:1  (fails)
//   - Black on CalmGreen        ~6.6:1  (passes) -> used for text on CalmGreen
//   - CalmTextDark on CalmGreenLight ~8.5:1 (passes) -> used for text on CalmGreenLight
//   - White on CalmGreenLight   ~1.4:1  (fails badly; this was the original bug)
private val LightColors = lightColorScheme(
    primary = CalmGreen,
    secondary = CalmGreenLight,
    background = WarmWhite,
    surface = WarmWhite,
    onPrimary = Color.Black,
    onSecondary = CalmTextDark,
    onBackground = CalmTextDark,
    onSurface = CalmTextDark
)

private val DarkColors = darkColorScheme(
    primary = CalmGreenLight,
    secondary = CalmGreen,
    background = Color(0xFF1B2422),
    surface = Color(0xFF1B2422),
    onPrimary = CalmTextDark,
    onSecondary = Color.Black
)

@Composable
fun GiveMeUrHandTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
