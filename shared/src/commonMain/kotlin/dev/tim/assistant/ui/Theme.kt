package dev.tim.assistant.ui

// Forest green, light mode only (SPEC section 8 tokens). This file holds every colour value of the app.

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

val WallyLight = lightColorScheme(
    primary = Color(0xFF1F6F4A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCEFE3),
    onPrimaryContainer = Color(0xFF0F3D27),
    secondary = Color(0xFF4E9A73),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCEFE3),
    onSecondaryContainer = Color(0xFF0F3D27),
    tertiary = Color(0xFF4E9A73),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCEFE3),
    onTertiaryContainer = Color(0xFF0F3D27),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF6F7F4),
    onBackground = Color(0xFF16201A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16201A),
    surfaceVariant = Color(0xFFEFF2EE),
    onSurfaceVariant = Color(0xFF55605A),
    outline = Color(0xFFD5DBD3),
    outlineVariant = Color(0xFFE4E7E1),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2B352F),
    inverseOnSurface = Color(0xFFF6F7F4),
    inversePrimary = Color(0xFF9CCBAE),
    surfaceDim = Color(0xFFE4E7E1),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEFF2EE),
    surfaceContainerHighest = Color(0xFFEFF2EE),
)

/** The non-scheme colours: empty dots, soft fills, the overdue red and the chart series. */
object WallyColors {
    val emptyDot = Color(0xFFDCE3DA)
    val softFill = Color(0xFFEFF2EE)
    val overdue = Color(0xFFB3261E)
    /** The chat-list drawer's scrim, rgba(22,32,26,0.45); the global scrim token is untouched. */
    val drawerScrim = Color(0xFF16201A).copy(alpha = 0.45f)
    /** Phone chat offline banner (#FDF0D5 on #5B3D00) and the Memory note body text (#33403A). */
    val warnBanner = Color(0xFFFDF0D5)
    val onWarnBanner = Color(0xFF5B3D00)
    val noteBody = Color(0xFF33403A)
    /** Fully transparent, for rows that have no fill. */
    val clear = Color.Transparent
    val chartSeries: List<Color> = listOf(Color(0xFF0F3D27), Color(0xFF1F6F4A), Color(0xFF4E9A73), Color(0xFF9CCBAE))
}

/** A solid brush of one colour, for text-field cursors. */
fun cursorBrush(c: Color): Brush = SolidColor(c)

/** Wally's Forest green theme; always light, whatever the system setting. */
@Composable
fun WallyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = WallyLight, content = content)
}

/** Every animation duration in the app, in milliseconds. */
object Motion {
    const val DefaultMs = 150
    const val CheckMs = 150
    const val CrossfadeMs = 150
    const val SwipeMs = 150
    /** How long a completed/deleted row waits for Undo before it is written. */
    const val UndoMs = 5_000L
}

/** A full-size surface painted with the theme's background and its paired content colour. */
@Composable
fun WallyRoot(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        content = content,
    )
}
