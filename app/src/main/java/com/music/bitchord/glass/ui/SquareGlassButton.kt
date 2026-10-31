package com.music.bitchord.glass.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import com.music.bitchord.glass.ui.backdrop.Backdrop
import com.music.bitchord.glass.ui.shapes.ContinuousCapsule
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.shape.CornerBasedShape
import kotlin.math.tanh

/**
 * BitChord Glass: the surface of Square's [LiquidButton] on its own — the
 * listener's glass, and the press that swells it and the drag that pulls and
 * stretches it after the finger — for BitChord's existing buttons, which keep
 * their own content, size and click handling.
 */
@Composable
fun Modifier.squareGlassButton(
    shape: CornerBasedShape,
    /**
     * Square's `flat`: the material without sampling what is behind. Required
     * for a button inside the page recording itself — a pane may not sample the
     * layer it is drawn in.
     */
    flat: Boolean = false,
): Modifier {
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) { InteractiveHighlight(animationScope = animationScope) }
    val configured = LocalGlassEffectConfig.current
    val config = if (flat) configured.copy(style = GlassStyle.TRANSPARENT) else configured
    return this
        .liquidGlass(
            config = config,
            shape = shape,
            backdropScale = 0.4f,
            highlightAlpha = 0.3f,
            layerBlock = {
                val width = size.width
                val height = size.height
                val progress = interactiveHighlight.pressProgress
                val scale = lerp(1f, 1f + 4f.dp.toPx() / size.height, progress)
                val maxOffset = size.minDimension
                val initialDerivative = 0.05f
                val offset = interactiveHighlight.offset
                translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
                translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)
                val maxDragScale = 4f.dp.toPx() / size.height
                val offsetAngle = atan2(offset.y, offset.x)
                scaleX = scale + maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
                    (width / height).fastCoerceAtMost(1f)
                scaleY = scale + maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
                    (height / width).fastCoerceAtMost(1f)
            },
        )
        .then(interactiveHighlight.modifier)
        .then(interactiveHighlight.gestureModifier)
}
