package com.music.bitchord.glass.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * BitChord Glass: the few theme values Square's glass components read, taken
 * from BitChord's own theme rather than from Square's. Kept to exactly what the
 * imported code names, so those files stay as Square wrote them.
 */

/** Square's ink override; null leaves the theme's own. */
val LocalInkOverride = compositionLocalOf<Color?> { null }

/** Square reads `LocalLightTheme.current`; here it is BitChord's theme, by its background. */
object LocalLightTheme {
    val current: Boolean
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.background.luminance() > 0.5f
}

@Composable
fun lightPage(): Boolean = LocalLightTheme.current

val Ink: Color
    @Composable get() = LocalInkOverride.current ?: MaterialTheme.colorScheme.onSurface

val InkDim: Color
    @Composable get() = Ink.copy(alpha = 0.66f)

fun Modifier.softShadow(
    shape: Shape,
    elevation: Dp = 18.dp,
    ambient: Float = 0.10f,
    spot: Float = 0.13f,
): Modifier = shadow(
    elevation = elevation,
    shape = shape,
    clip = false,
    ambientColor = Color.Black.copy(alpha = ambient),
    spotColor = Color.Black.copy(alpha = spot),
)
