/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 */
package dev.lelonio.square.ui.glass.backdrop.effects

import androidx.annotation.FloatRange
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorMatrixColorFilter
import dev.lelonio.square.ui.glass.backdrop.BackdropEffectScope
import dev.lelonio.square.ui.glass.backdrop.internal.ColorFilterEffect
import dev.lelonio.square.ui.glass.backdrop.isBackdropFallbackSupported
import dev.lelonio.square.ui.glass.backdrop.isRenderEffectSupported

fun BackdropEffectScope.colorFilter(colorFilter: ColorFilter) {
    if (!isRenderEffectSupported()) return

    renderEffect = ColorFilterEffect(renderEffect, colorFilter)
}

fun BackdropEffectScope.opacity(@FloatRange(from = 0.0, to = 1.0) alpha: Float) {
    val matrix = ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, alpha, 0f
        )
    )
    colorMatrix(matrix)
}

/**
 * Square Fusion: a colour matrix, as a ColorFilter where there is a
 * RenderEffect, and kept for the Android 11 drawing where there is not.
 */
internal fun BackdropEffectScope.colorMatrix(matrix: ColorMatrix) {
    if (!isRenderEffectSupported()) {
        if (isBackdropFallbackSupported()) {
            fallbackColorMatrix = fallbackColorMatrix?.let { thenApply(it, matrix) } ?: matrix
        }
        return
    }
    colorFilter(ColorMatrixColorFilter(matrix))
}

/** The matrix that applies [first], then [second] (4x5, the last column an offset). */
internal fun thenApply(first: ColorMatrix, second: ColorMatrix): ColorMatrix {
    val a = first.values
    val b = second.values
    val out = FloatArray(20)
    for (i in 0 until 4) {
        for (j in 0 until 5) {
            var v = if (j == 4) b[i * 5 + 4] else 0f
            for (k in 0 until 4) v += b[i * 5 + k] * a[k * 5 + j]
            out[i * 5 + j] = v
        }
    }
    return ColorMatrix(out)
}

fun BackdropEffectScope.colorControls(
    brightness: Float = 0f,
    contrast: Float = 1f,
    saturation: Float = 1f
) {
    if (brightness == 0f && contrast == 1f && saturation == 1f) {
        return
    }

    colorMatrix(colorControlsMatrix(brightness, contrast, saturation))
}

private val VibrantColorMatrix = colorControlsMatrix(saturation = 1.5f)

fun BackdropEffectScope.vibrancy() {
    colorMatrix(VibrantColorMatrix)
}

private fun colorControlsMatrix(
    brightness: Float = 0f,
    contrast: Float = 1f,
    saturation: Float = 1f
): ColorMatrix {
    val invSat = 1f - saturation
    val r = 0.213f * invSat
    val g = 0.715f * invSat
    val b = 0.072f * invSat

    val c = contrast
    val t = (0.5f - c * 0.5f + brightness) * 255f
    val s = saturation

    val cr = c * r
    val cg = c * g
    val cb = c * b
    val cs = c * s

    val colorMatrix = ColorMatrix(
        floatArrayOf(
            cr + cs, cg, cb, 0f, t,
            cr, cg + cs, cb, 0f, t,
            cr, cg, cb + cs, 0f, t,
            0f, 0f, 0f, 1f, 0f
        )
    )
    return colorMatrix
}
