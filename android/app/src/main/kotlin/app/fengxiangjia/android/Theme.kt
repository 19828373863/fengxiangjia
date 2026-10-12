package app.fengxiangjia.android

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Warm paper, forest ink and sage. Semantic roles keep all four screens coherent
// in daylight and at night; system fonts retain the user's Chinese font settings.
private val DayColors = lightColorScheme(
    primary = Color(0xFF28513E), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E8D9), onPrimaryContainer = Color(0xFF173725),
    secondary = Color(0xFF526657), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E8DD), onSecondaryContainer = Color(0xFF273D2C),
    tertiary = Color(0xFF825943), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF3DFD2), onTertiaryContainer = Color(0xFF442917),
    background = Color(0xFFFAF8F2), onBackground = Color(0xFF202B23),
    surface = Color(0xFFFAF8F2), onSurface = Color(0xFF202B23),
    surfaceVariant = Color(0xFFE5E9E0), onSurfaceVariant = Color(0xFF4E5D51),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF3F3EB),
    surfaceContainer = Color(0xFFEEEEE5), surfaceContainerHigh = Color(0xFFE8E9DF),
    surfaceContainerHighest = Color(0xFFE2E4D9),
    outline = Color(0xFF748074), outlineVariant = Color(0xFFCBD2C5),
    error = Color(0xFFAA3C2E), onError = Color.White,
    errorContainer = Color(0xFFFFDAD2), onErrorContainer = Color(0xFF64190F),
)

private val NightColors = darkColorScheme(
    primary = Color(0xFFADD1B7), onPrimary = Color(0xFF153A26),
    primaryContainer = Color(0xFF2D513C), onPrimaryContainer = Color(0xFFD4EAD7),
    secondary = Color(0xFFB9CCB8), onSecondary = Color(0xFF263C29),
    secondaryContainer = Color(0xFF3C5240), onSecondaryContainer = Color(0xFFDCE8D8),
    tertiary = Color(0xFFE7BA9F), onTertiary = Color(0xFF4B2D1A),
    tertiaryContainer = Color(0xFF68452F), onTertiaryContainer = Color(0xFFFFDCC5),
    background = Color(0xFF131C16), onBackground = Color(0xFFE3E9DF),
    surface = Color(0xFF131C16), onSurface = Color(0xFFE3E9DF),
    surfaceVariant = Color(0xFF3B483D), onSurfaceVariant = Color(0xFFC3CFC0),
    surfaceContainerLowest = Color(0xFF0D1510), surfaceContainerLow = Color(0xFF1B251D),
    surfaceContainer = Color(0xFF202A22), surfaceContainerHigh = Color(0xFF2B352D),
    surfaceContainerHighest = Color(0xFF354037),
    outline = Color(0xFF8E9C8C), outlineVariant = Color(0xFF435143),
    error = Color(0xFFFFB4A5), onError = Color(0xFF66190E),
    errorContainer = Color(0xFF853022), onErrorContainer = Color(0xFFFFDAD2),
)

private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight,
    fontSize = size.sp, lineHeight = line.sp, letterSpacing = 0.sp,
)

private val AppTypography = Typography(
    displayLarge = type(48, 56), displayMedium = type(40, 48), displaySmall = type(36, 44),
    headlineLarge = type(28, 36, FontWeight.Bold), headlineMedium = type(26, 34, FontWeight.Bold),
    headlineSmall = type(24, 32, FontWeight.Medium),
    titleLarge = type(21, 30, FontWeight.Medium), titleMedium = type(17, 26, FontWeight.Medium),
    titleSmall = type(15, 23, FontWeight.Medium),
    bodyLarge = type(16, 25), bodyMedium = type(14, 22), bodySmall = type(13, 20),
    labelLarge = type(14, 21, FontWeight.Medium), labelMedium = type(13, 20, FontWeight.Medium),
    labelSmall = type(12, 18, FontWeight.Medium),
)

@Composable
fun FengXiangTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) NightColors else DayColors,
        typography = AppTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
