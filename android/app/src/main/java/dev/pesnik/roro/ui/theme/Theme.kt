package dev.pesnik.roro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// A deliberate warm "cozy pet companion" palette sampled from the calico
// sprite itself (see Color.kt) — not Material's dynamic-color-from-wallpaper
// default, which is what made the app look flat and grayed-out before.
private val DarkColors = darkColorScheme(
    primary = CalicoOrange,
    onPrimary = CalicoTextOnOrange,
    primaryContainer = CalicoOrangeDeep,
    onPrimaryContainer = CalicoParchment,
    secondary = CalicoPink,
    onSecondary = CalicoTextOnOrange,
    background = CalicoBrownDark,
    onBackground = CalicoCream,
    surface = CalicoBrownSurface,
    onSurface = CalicoCream,
    surfaceVariant = CalicoBrownContainer,
    onSurfaceVariant = CalicoParchment,
    surfaceContainer = CalicoBrownSurface,
    surfaceContainerHigh = CalicoBrownContainer,
    surfaceContainerHighest = CalicoOrangeDeep,
    outline = CalicoBrownOutline,
    outlineVariant = CalicoBrownContainer,
    error = CalicoPink,
)

private val LightColors = lightColorScheme(
    primary = CalicoOrange,
    onPrimary = CalicoTextOnOrange,
    primaryContainer = CalicoParchment,
    onPrimaryContainer = CalicoTextOnOrange,
    secondary = CalicoPink,
    background = CalicoParchment,
    onBackground = CalicoTextOnOrange,
    surface = CalicoCream,
    onSurface = CalicoTextOnOrange,
    surfaceVariant = CalicoParchment,
)

@Composable
fun RoroTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
