package com.grka.xray.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.grka.xray.AppConfig

// --- Aurora: deep night blue with neon violet/cyan accents (default) ---
private val AuroraScheme = darkColorScheme(
    primary = Color(0xFF8B7CFF),
    onPrimary = Color(0xFF0B1020),
    primaryContainer = Color(0xFF2C2A5E),
    onPrimaryContainer = Color(0xFFDCD6FF),
    secondary = Color(0xFF22D3EE),
    onSecondary = Color(0xFF06202A),
    secondaryContainer = Color(0xFF123A46),
    onSecondaryContainer = Color(0xFFBDEFFB),
    tertiary = Color(0xFFF471B5),
    onTertiary = Color(0xFF33101F),
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFE7EAF6),
    surface = Color(0xFF131A31),
    onSurface = Color(0xFFE7EAF6),
    surfaceVariant = Color(0xFF1C2444),
    onSurfaceVariant = Color(0xFFA6ADCE),
    outline = Color(0xFF39406B),
    error = Color(0xFFFF5C7A),
    onError = Color(0xFF2B0812),
)

// --- Ocean: dark teal / sea depths ---
private val OceanScheme = darkColorScheme(
    primary = Color(0xFF2DD4BF),
    onPrimary = Color(0xFF042F2A),
    primaryContainer = Color(0xFF115E56),
    onPrimaryContainer = Color(0xFFB5F5EC),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF082B3D),
    secondaryContainer = Color(0xFF0D3A52),
    onSecondaryContainer = Color(0xFFC2E9FC),
    tertiary = Color(0xFFA3E635),
    onTertiary = Color(0xFF1A2604),
    background = Color(0xFF06131C),
    onBackground = Color(0xFFDFF0F6),
    surface = Color(0xFF0C1F2C),
    onSurface = Color(0xFFDFF0F6),
    surfaceVariant = Color(0xFF133043),
    onSurfaceVariant = Color(0xFF93B4C4),
    outline = Color(0xFF29506A),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2E0707),
)

// --- Pearl: light minimal with indigo accent ---
private val PearlScheme = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2E0FF),
    onPrimaryContainer = Color(0xFF191064),
    secondary = Color(0xFF0891B2),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3F1FA),
    onSecondaryContainer = Color(0xFF083A46),
    tertiary = Color(0xFFDB2777),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F6FB),
    onBackground = Color(0xFF171B2C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171B2C),
    surfaceVariant = Color(0xFFE9EDF6),
    onSurfaceVariant = Color(0xFF5A617A),
    outline = Color(0xFFC4CBDE),
    error = Color(0xFFDC2640),
    onError = Color(0xFFFFFFFF),
)

@Composable
fun GrKaXTheme(theme: String, content: @Composable () -> Unit) {
    val scheme = when (theme) {
        AppConfig.THEME_OCEAN -> OceanScheme
        AppConfig.THEME_PEARL -> PearlScheme
        AppConfig.THEME_AURORA -> AuroraScheme
        else -> if (isSystemInDarkTheme()) AuroraScheme else PearlScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        content = content
    )
}
