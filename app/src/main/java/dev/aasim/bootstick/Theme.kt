package dev.aasim.bootstick

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * BootStick's brand: electric teal on ink navy, with amber for warnings and the boot arrow in the logo.
 * A fixed palette rather than wallpaper colors, so the app looks like itself on every phone.
 * The window background behind it is in res/values(-night)/themes.xml.
 */

private val Dark = darkColorScheme(
    primary = Color(0xFF00BFA6), onPrimary = Color(0xFF00201B),
    primaryContainer = Color(0xFF00463D), onPrimaryContainer = Color(0xFF7FF8E2),
    secondary = Color(0xFF9DB4C8), onSecondary = Color(0xFF1B2B38),
    secondaryContainer = Color(0xFF22324A), onSecondaryContainer = Color(0xFFD3E3F5),
    tertiary = Color(0xFFFFB300), onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF4A3600), onTertiaryContainer = Color(0xFFFFDEA0),
    background = Color(0xFF0B1220), onBackground = Color(0xFFE3E8F0),
    surface = Color(0xFF0B1220), onSurface = Color(0xFFE3E8F0),
    surfaceVariant = Color(0xFF1E2A3D), onSurfaceVariant = Color(0xFF9AA7BA),
    surfaceContainerLowest = Color(0xFF070C16), surfaceContainerLow = Color(0xFF111A2B),
    surfaceContainer = Color(0xFF141E31), surfaceContainerHigh = Color(0xFF1A2438),
    surfaceContainerHighest = Color(0xFF212C42),
    outline = Color(0xFF5B6B82), outlineVariant = Color(0xFF2A3850),
)

// Light mode uses a deeper teal, so white text on buttons stays readable.
private val Light = lightColorScheme(
    primary = Color(0xFF00796B), onPrimary = Color.White,
    primaryContainer = Color(0xFFB2F5EA), onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A6572), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E4EC), onSecondaryContainer = Color(0xFF0F1D26),
    tertiary = Color(0xFF7A5900), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDEA0), onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFF4F7FA), onBackground = Color(0xFF0B1220),
    surface = Color(0xFFF4F7FA), onSurface = Color(0xFF0B1220),
    surfaceVariant = Color(0xFFDDE4EC), onSurfaceVariant = Color(0xFF4A5568),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEDF2F6),
    surfaceContainer = Color(0xFFE7EDF2), surfaceContainerHigh = Color(0xFFE1E8EE),
    surfaceContainerHighest = Color(0xFFDAE2EA),
    outline = Color(0xFF8A94A6), outlineVariant = Color(0xFFCBD3DD),
)

private val shapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

// The system font, with bolder, tighter headings for a more confident look.
private val type = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

// The Surface at the root sets the default text color for every screen. Without it, text outside a Card or
// Scaffold (the splash, dialogs' parents) falls back to black, which disappears in dark mode.
@Composable
fun BootStickTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, shapes = shapes, typography = type) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
