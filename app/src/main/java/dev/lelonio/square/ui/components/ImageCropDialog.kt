package dev.lelonio.square.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.X
import dev.lelonio.square.R
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.backdrop.backdrops.layerBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Square Fusion: the frame an image is cropped to. */
enum class CropShape { SQUARE, CIRCLE }

/**
 * Square Fusion: framing a picture before it is kept — a playlist's cover or
 * the profile picture.
 *
 * The picture fills the screen under a frame of the shape it will be shown
 * in; it is moved with a finger, zoomed with two, and put back with a double
 * tap. What is inside the frame is the result. Confirm keeps that part, as a
 * square image (the frame is always square; the circle is how the profile
 * picture is shown); cancel leaves everything as it was. The two controls are
 * the app's glass, over the picture itself.
 */
@Composable
fun ImageCropDialog(
    source: Uri,
    shape: CropShape,
    onCropped: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember(source) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(source) { mutableStateOf(false) }
    LaunchedEffect(source) {
        bitmap = withContext(Dispatchers.IO) { runCatching { decode(context, source) }.getOrNull() }
        if (bitmap == null) failed = true
    }
    LaunchedEffect(failed) { if (failed) onDismiss() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val stage = rememberLayerBackdrop()
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val density = LocalDensity.current
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()
            val margin = with(density) { 28.dp.toPx() }
            val controls = with(density) { 140.dp.toPx() }
            val side = min(width - 2 * margin, height - 2 * margin - controls).coerceAtLeast(1f)
            val frame = Rect(Offset((width - side) / 2, (height - controls - side) / 2 + margin / 2), Size(side, side))

            val image = bitmap
            if (image == null) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White, modifier = Modifier.align(Alignment.Center).size(26.dp))
                return@BoxWithConstraints
            }
            // The smallest zoom still covers the frame; the largest is six times that.
            val minScale = side / min(image.width, image.height)
            var scale by remember(image, side) { mutableFloatStateOf(minScale) }
            var offset by remember(image, side) { mutableStateOf(Offset.Zero) }
            fun clamp(o: Offset, s: Float): Offset {
                val maxX = (image.width * s - side) / 2
                val maxY = (image.height * s - side) / 2
                return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
            }
            val picture = remember(image) { image.asImageBitmap() }

            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(stage)
                    .pointerInput(image, side) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val next = (scale * zoom).coerceIn(minScale, minScale * 6f)
                            scale = next
                            offset = clamp(offset + pan, next)
                        }
                    }
                    .pointerInput(image, side) {
                        detectTapGestures(onDoubleTap = {
                            scale = minScale
                            offset = Offset.Zero
                        })
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = image.width * scale
                    val h = image.height * scale
                    val left = frame.center.x + offset.x - w / 2
                    val top = frame.center.y + offset.y - h / 2
                    drawImage(
                        picture,
                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                    )
                    // Everything outside the frame, dimmed; the frame's edge, lit.
                    val hole = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(Offset.Zero, size))
                        if (shape == CropShape.CIRCLE) addOval(frame)
                        else addRoundRect(RoundRect(frame, CornerRadius(14.dp.toPx())))
                    }
                    drawPath(hole, Color.Black.copy(alpha = 0.58f))
                    val edge = Stroke(1.dp.toPx())
                    if (shape == CropShape.CIRCLE) {
                        drawOval(Color.White.copy(alpha = 0.75f), frame.topLeft, frame.size, style = edge)
                    } else {
                        drawRoundRect(Color.White.copy(alpha = 0.75f), frame.topLeft, frame.size, CornerRadius(14.dp.toPx()), style = edge)
                    }
                }
            }

            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LiquidButton(
                    onClick = onDismiss,
                    backdrop = stage,
                    modifier = Modifier.size(58.dp),
                    contentHeight = 58.dp,
                    contentPadding = 0.dp,
                ) {
                    Icon(PhosphorIcons.Regular.X, contentDescription = stringResource(R.string.cancel), tint = Color.White, modifier = Modifier.size(24.dp))
                }
                LiquidButton(
                    onClick = {
                        val crop = cropRect(image.width, image.height, scale, offset, side)
                        scope.launch {
                            val out = withContext(Dispatchers.IO) { runCatching { save(context, image, crop) }.getOrNull() }
                            if (out != null) onCropped(out) else onDismiss()
                        }
                    },
                    backdrop = stage,
                    modifier = Modifier.size(58.dp),
                    contentHeight = 58.dp,
                    contentPadding = 0.dp,
                ) {
                    Icon(PhosphorIcons.Regular.Check, contentDescription = stringResource(R.string.save), tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

/**
 * The part of a [width] x [height] picture inside a frame of [side] pixels,
 * the picture drawn at [scale] and moved by [offset] from the frame's centre.
 * Pure, for tests.
 */
internal fun cropRect(width: Int, height: Int, scale: Float, offset: Offset, side: Float): android.graphics.Rect {
    val size = (side / scale).roundToInt().coerceIn(1, min(width, height))
    val centreX = width / 2f - offset.x / scale
    val centreY = height / 2f - offset.y / scale
    val left = (centreX - size / 2f).roundToInt().coerceIn(0, width - size)
    val top = (centreY - size / 2f).roundToInt().coerceIn(0, height - size)
    return android.graphics.Rect(left, top, left + size, top + size)
}

private const val OUTPUT_SIDE = 1080
private const val MAX_DECODE_SIDE = 2560

private fun decode(context: android.content.Context, source: Uri): Bitmap {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        val decoderSource = android.graphics.ImageDecoder.createSource(context.contentResolver, source)
        return android.graphics.ImageDecoder.decodeBitmap(decoderSource) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_DECODE_SIDE) decoder.setTargetSampleSize((longest + MAX_DECODE_SIDE - 1) / MAX_DECODE_SIDE)
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(source)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE_SIDE) sample *= 2
    val decoded = context.contentResolver.openInputStream(source)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: error("cannot read $source")
    // Turned as the camera says it was held.
    val degrees = context.contentResolver.openInputStream(source)?.use {
        when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } ?: 0f
    if (degrees == 0f) return decoded
    val turn = android.graphics.Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, turn, true)
}

private fun save(context: android.content.Context, image: Bitmap, crop: android.graphics.Rect): Uri {
    val part = Bitmap.createBitmap(image, crop.left, crop.top, crop.width(), crop.height())
    val out = if (part.width > OUTPUT_SIDE) Bitmap.createScaledBitmap(part, OUTPUT_SIDE, OUTPUT_SIDE, true) else part
    val dir = File(context.cacheDir, "crops").apply { mkdirs() }
    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000L }?.forEach { it.delete() }
    val file = File(dir, "crop-${System.currentTimeMillis()}.jpg")
    file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    return Uri.fromFile(file)
}
