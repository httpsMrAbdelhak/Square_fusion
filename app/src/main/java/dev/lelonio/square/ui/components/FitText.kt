package dev.lelonio.square.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * Square Fusion: one line of text, whole, in the style it is given; its size
 * comes down a step at a time only while it would not fit, never below
 * [minScale]. Drawn once it fits, so the steps are not seen.
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    minScale: Float = 0.6f,
) {
    BoxWithConstraints(modifier) {
        key(text, style, maxWidth) {
            var scale by remember { mutableFloatStateOf(1f) }
            var ready by remember { mutableStateOf(false) }
            Text(
                text,
                style = style.copy(fontSize = style.fontSize * scale),
                color = color,
                fontWeight = fontWeight,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.drawWithContent { if (ready) drawContent() },
                onTextLayout = { layout ->
                    if (layout.didOverflowWidth && scale > minScale) {
                        scale = (scale * 0.94f).coerceAtLeast(minScale)
                    } else {
                        ready = true
                    }
                },
            )
        }
    }
}
