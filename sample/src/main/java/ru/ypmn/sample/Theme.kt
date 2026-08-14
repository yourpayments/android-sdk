package ru.ypmn.sample

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B4DFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E3FF),
    onPrimaryContainer = Color(0xFF160C4A),
    secondary = Color(0xFF00B5A5),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFF7F1),
    onSecondaryContainer = Color(0xFF00322D),
    background = Color(0xFFF5F6FB),
    onBackground = Color(0xFF1A1A22),
    surface = Color.White,
    onSurface = Color(0xFF1A1A22),
    surfaceVariant = Color(0xFFECEDF5),
    onSurfaceVariant = Color(0xFF4A4856),
    outline = Color(0xFFC9C7D2),
    outlineVariant = Color(0xFFE2E1EA),
    error = Color(0xFFD92626),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBBB4FF),
    onPrimary = Color(0xFF1B1149),
    primaryContainer = Color(0xFF382C84),
    onPrimaryContainer = Color(0xFFE6E3FF),
    secondary = Color(0xFF5FD9CC),
    onSecondary = Color(0xFF00322D),
    secondaryContainer = Color(0xFF005048),
    onSecondaryContainer = Color(0xFFCFF7F1),
    background = Color(0xFF121019),
    onBackground = Color(0xFFE7E6EE),
    surface = Color(0xFF1C1A24),
    onSurface = Color(0xFFE7E6EE),
    surfaceVariant = Color(0xFF2C2A36),
    onSurfaceVariant = Color(0xFFC7C5D2),
    outline = Color(0xFF615F6C),
    outlineVariant = Color(0xFF3A3845),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0A0A),
)

private val YpShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun YpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = YpShapes,
        content = content,
    )
}
