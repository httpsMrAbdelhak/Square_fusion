package dev.lelonio.square.ui.glass.backdrop.internal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorMatrixColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.unit.IntSize
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Square Fusion: the glass's blur on Android 11, which has no RenderEffect.
 *
 * The backdrop is recorded as usual, then halved several times into
 * offscreen layers and brought back up the same way. Each step draws a
 * layer's texture at half or double its size, which the GPU samples
 * bilinearly, so every halving averages each 2x2 block: the classic
 * "dual filter" blur, close to a Gaussian, on the GPU, with nothing but
 * GraphicsLayer (Android 10+). The backdrop is captured with a margin around
 * the pane so the edges average real content, as the Clamp edge of the
 * RenderEffect blur does, instead of fading. The colour matrices of the
 * effects (saturation, opacity…) go on the last layer as a ColorFilter.
 *
 * The result is recorded into the pane's own layer, so everything that draws
 * that layer afterwards is unchanged.
 */
internal class FallbackBlur {

    private val layers = ArrayList<GraphicsLayer>()

    private fun layer(index: Int, node: DelegatableNode): GraphicsLayer {
        while (layers.size <= index) {
            layers += node.requireGraphicsContext().createGraphicsLayer().apply {
                compositingStrategy = CompositingStrategy.Offscreen
            }
        }
        return layers[index]
    }

    fun DrawScope.record(
        node: DelegatableNode,
        target: GraphicsLayer,
        size: IntSize,
        radius: Float,
        matrix: ColorMatrix?,
        block: DrawScope.() -> Unit,
    ) {
        val levels = levelsFor(radius, size)
        val margin = if (levels == 0) 0 else (1 shl levels) * 2
        val rawSize = IntSize(size.width + 2 * margin, size.height + 2 * margin)
        val raw = layer(0, node)
        recordLayer(node, raw, size = rawSize) {
            translate(margin.toFloat(), margin.toFloat()) { block() }
        }

        // Down: each level half the one before.
        val sizes = ArrayList<IntSize>(levels + 1).apply { add(rawSize) }
        var source = raw
        var sourceSize = rawSize
        for (level in 1..levels) {
            val nextSize = IntSize((sourceSize.width / 2).coerceAtLeast(1), (sourceSize.height / 2).coerceAtLeast(1))
            val next = layer(level, node)
            source.colorFilter = null
            draw(node, next, nextSize, source, sourceSize)
            sizes += nextSize
            source = next
            sourceSize = nextSize
        }
        // Up again, to the first level; the pane's layer takes the last doubling.
        for (level in levels - 1 downTo 1) {
            val nextSize = sizes[level]
            val next = layer(levels + (levels - level), node)
            source.colorFilter = null
            draw(node, next, nextSize, source, sourceSize)
            source = next
            sourceSize = nextSize
        }

        source.colorFilter = matrix?.let { ColorMatrixColorFilter(it) }
        val last = source
        val lastSize = sourceSize
        recordLayer(node, target, size = size) {
            translate(-margin.toFloat(), -margin.toFloat()) {
                scale(
                    rawSize.width / lastSize.width.toFloat(),
                    rawSize.height / lastSize.height.toFloat(),
                    pivot = Offset.Zero,
                ) { drawLayer(last) }
            }
        }
    }

    /**
     * [from] drawn into [into] at its size, as the mean of four draws half a
     * source pixel apart: a 2x2 box when halving, a tent when doubling,
     * whatever filtering the GPU applies to a scaled layer (the emulator's
     * samples the nearest pixel, which alone would only skip every other one).
     * The mean is a running one: each draw over the ones before at 1/n.
     */
    private fun DrawScope.draw(node: DelegatableNode, into: GraphicsLayer, intoSize: IntSize, from: GraphicsLayer, fromSize: IntSize) {
        recordLayer(node, into, size = intoSize) {
            val bounds = androidx.compose.ui.geometry.Rect(0f, 0f, intoSize.width.toFloat(), intoSize.height.toFloat())
            TAPS.forEachIndexed { index, (dx, dy) ->
                val paint = androidx.compose.ui.graphics.Paint().apply { alpha = 1f / (index + 1) }
                drawContext.canvas.saveLayer(bounds, paint)
                scale(
                    intoSize.width / fromSize.width.toFloat(),
                    intoSize.height / fromSize.height.toFloat(),
                    pivot = Offset.Zero,
                ) {
                    translate(dx, dy) { drawLayer(from) }
                }
                drawContext.canvas.restore()
            }
        }
    }

    fun release(node: DelegatableNode) {
        val context = node.requireGraphicsContext()
        layers.forEach(context::releaseGraphicsLayer)
        layers.clear()
    }

    companion object {
        /**
         * How many halvings for a blur of [radius] pixels. A RenderEffect blur
         * of radius r has a sigma of about 0.58 r, and n levels of this
         * pyramid spread a pixel over about 2^n: n is the nearest power.
         */
        fun levelsFor(radius: Float, size: IntSize): Int {
            if (radius <= 0f) return 0
            val sigma = radius * 0.58f
            val wanted = log2(sigma.coerceAtLeast(1f)).roundToInt().coerceIn(1, MAX_LEVELS)
            // Never below a few pixels on the short side.
            val fits = log2((minOf(size.width, size.height) / 4f).coerceAtLeast(1f)).toInt().coerceAtLeast(1)
            return minOf(wanted, fits)
        }

        private const val MAX_LEVELS = 6

        /** Half a source pixel each way, in the source's own pixels. */
        private val TAPS = listOf(-0.5f to -0.5f, 0.5f to -0.5f, -0.5f to 0.5f, 0.5f to 0.5f)
    }
}
