package com.samford.patient.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Teal = Color(0xFF0B5D73)
val TealLight = Color(0xFFB3E5E0)
val Coral = Color(0xFFE0533D)
val SampleRed = Color(0xFFC62828)
val Amber = Color(0xFFB26A00)
val OkGreen = Color(0xFF2E7D32)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBF2),
    onPrimaryContainer = Color(0xFF00313D),
    secondary = Color(0xFF4A6370),
    secondaryContainer = Color(0xFFD9E8EF),
    tertiary = Coral,
    background = Color(0xFFF6FAFB),
    surface = Color(0xFFF6FAFB),
    surfaceVariant = Color(0xFFE1EBEF),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD0E4),
    onPrimary = Color(0xFF003642),
    primaryContainer = Color(0xFF004E5F),
    onPrimaryContainer = Color(0xFFCDEBF2),
    secondary = Color(0xFFB2CBD6),
    tertiary = Color(0xFFFFB4A6),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    )
}

@Composable
fun SamfordTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
