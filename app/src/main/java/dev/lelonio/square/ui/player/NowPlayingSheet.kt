package dev.lelonio.square.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The now-playing bar and the player, as one surface.
 *
 * The previous version made the player a navigation destination and tried to
 * animate between the two. That can only ever cross-fade: they are separate
 * screens, both on stage at once, and no amount of shared-element work hides
 * that the bar and the player are two different things pretending to be one.
 *
 * They still are, in the sense that matters: one `progress` value owns both, so
 * the bar and the player are two views of a single state rather than two screens
 * taking turns.
 *
 * How that value is spent went through a growing container — the bar's height
 * stretched up to the window's, with the player already laid out full size
 * inside it. It looked right and it was never quite smooth: resizing that
 * container re-ran a layout pass over a screen holding a video surface and
 * several pieces of live glass, sixty times a second, and deferring every state
 * read only moved the cost around.
 *
 * So the travel is now a transform and nothing else. The player is full size
 * from the first frame, rises a little way into place and fades in as the bar
 * fades out under it. One layer, scaled and faded on the render thread, with no
 * measure or layout anywhere in the frame.
 *
 * The same value is what a drag writes to, which is why dragging down works at
 * any point and can be released anywhere: expansion is a position, not an event.
 *
 * There is no collapsed half any more. There used to be one — a bar the width of
 * the screen, carrying what was playing and the drag that opened the player — and
 * once the tab bar grew its own now-playing pill it stayed behind as an empty
 * strip: invisible, sixty-odd dp tall, sitting across the bottom of every page
 * and still listening for a vertical drag. An upward swipe over the last of a
 * list opened the player, from a thing nobody could see. What opens it now is the
 * pill itself, which is where what is playing actually is.
 *
 * @param progress 0 while collapsed, 1 while expanded. Hoisted because the tab
 *   bar has to fade out against it.
 */
@Composable
fun NowPlayingSheet(
    progress: Animatable<Float, *>,
    /**
     * Drawn under the player, fading in with it.
     *
     * The player used to be a destination stacked over the app's own backdrop
     * and so had no background of its own. As a sheet it sits *above* the page,
     * so without this the home screen showed through every track that has no
     * Canvas to cover it.
     */
    background: @Composable () -> Unit,
    /**
     * Square Fusion: the bar's cover and the player's picture, when both are on
     * screen. With them the player slides up as a sheet and its picture flies
     * from the bar's cover to its place (BitChord's docking, see [PlayerDock]);
     * without them — a Canvas or a video at the top — it rises and fades as before.
     */
    dock: PlayerDock? = null,
    expandedContent: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // Nothing here reads `progress.value` during composition, and that is the
    // whole performance story of this file.
    //
    // Reading it in the composable body recomposed the sheet — and with it the
    // entire player and the bar — on every frame of the travel, which measured
    // at a 97ms median frame and a 250ms worst case. Read from inside a layout
    // or a graphicsLayer block instead, the same value only re-measures or
    // re-draws, which is two phases cheaper.
    //
    // These two are derived rather than read directly so a recomposition
    // happens when the answer *changes*, not when the number does.
    val expandedVisible by remember { derivedStateOf { progress.value > 0.001f } }

    // True only at the two ends of the travel; see LocalGlassEnabled.
    val settled by remember {
        derivedStateOf { progress.value <= 0.001f || progress.value >= 0.999f }
    }

    Box(Modifier.fillMaxSize()) {
        // One spec for both directions, and the same one the tap uses: closing
        // is meant to be the opening played backwards, so nothing about the
        // motion may depend on which way it is going.
        fun settle(target: Float) {
            scope.launch {
                // Square Fusion: BitChord's slide, the one every open and
                // close of the player runs on; see PlayerDock.Slide.
                progress.animateTo(target, PlayerDock.Slide)
            }
        }

        BackHandler(enabled = expandedVisible) { settle(0f) }

        // The player, full size from the first frame to the last. Nothing about
        // the travel touches measure or layout any more: the growing container
        // that used to drive it re-ran a layout pass on the whole player every
        // frame, and no amount of deferring state reads makes that free on a
        // screen with a video surface and a stack of glass in it. What is left
        // is one layer being scaled and faded, which the render thread does
        // without waking the UI thread at all.
        if (expandedVisible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { dock?.travel = it.height.toFloat() }
                    .graphicsLayer {
                        val f = progress.value.coerceIn(0f, 1f)
                        if (dock != null && dock.ready()) {
                            // A sheet: up from below its own height, the
                            // picture travelling separately on top.
                            translationY = dock.offset()
                            alpha = if (f < 1f) dock.contentAlpha() else 1f
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                            return@graphicsLayer
                        }
                        alpha = f
                        // Rises into place from just below, slightly smaller.
                        // Small numbers on purpose — this is the player arriving
                        // where it already is, not flying in from off-screen.
                        val scale = ENTER_SCALE + (1f - ENTER_SCALE) * f
                        scaleX = scale
                        scaleY = scale
                        translationY = (1f - f) * size.height * ENTER_TRAVEL
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        // Rounded while it is on its way in and square once it
                        // has arrived, so it reads as a sheet rather than as a
                        // screen appearing.
                        shape = RoundedCornerShape(SHEET_CORNER * (1f - f))
                        clip = true
                        // Modulated rather than composited: the default draws
                        // the whole subtree into an offscreen buffer the size of
                        // the window before applying alpha, every frame.
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
            ) {
                background()
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalGlassEnabled provides settled,
                    LocalPlayerSettled provides settled,
                ) {
                    expandedContent()
                }
            }
            if (dock != null) DockedPicture(dock)
        }
    }
}

/**
 * Square Fusion: the one picture that travels between the bar and the player,
 * drawn over both while it is on its way and nowhere at either end. Laid out
 * afresh each frame at the dock's rect, so the crop follows the shape — the
 * bar's square becoming the player's three by four — rather than a fixed image
 * being scaled. The bar's picture and the player's are both drawn, the player's
 * coming up as it arrives, so a track whose player shows a taller picture of
 * its own changes over on the way rather than at either end.
 */
@Composable
private fun DockedPicture(dock: PlayerDock) {
    val miniUrl = dock.miniUrl
    val playerUrl = dock.playerUrl
    if (miniUrl == null || playerUrl == null) return
    val origin = remember { floatArrayOf(0f, 0f) }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                val at = it.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                origin[0] = at.x
                origin[1] = at.y
            },
    ) {
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val rect = if (dock.pictureOnTop()) dock.coverRect() else null
                    if (rect == null || rect.width < 1f || rect.height < 1f) {
                        val placeable = measurable.measure(Constraints.fixed(0, 0))
                        return@layout layout(0, 0) { placeable.place(0, 0) }
                    }
                    val placeable = measurable.measure(
                        Constraints.fixed(rect.width.roundToInt(), rect.height.roundToInt()),
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place((rect.left - origin[0]).roundToInt(), (rect.top - origin[1]).roundToInt())
                    }
                }
                .graphicsLayer {
                    alpha = if (dock.pictureOnTop()) 1f else 0f
                    shape = RoundedCornerShape(dock.coverCorner())
                    clip = true
                },
        ) {
            dev.lelonio.square.ui.components.Artwork(
                url = miniUrl,
                title = "",
                modifier = Modifier.fillMaxSize(),
                corner = 0.dp,
                // The bar's own decode, already in memory: the trip starts on
                // the very picture the bar shows.
                decodeSize = MiniArtDecode,
                crossfadeMs = 0,
                fallback = false,
            )
            dev.lelonio.square.ui.components.Artwork(
                url = playerUrl,
                title = "",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = dock.progress().coerceIn(0f, 1f) },
                corner = 0.dp,
                crossfadeMs = 0,
                fallback = false,
            )
        }
    }
}

/** The bar's cover decode size; see FloatingMiniPlayer's ArtSize. */
internal val MiniArtDecode = 40.dp

/** How small the player starts, growing to its own size as it arrives. */
private const val ENTER_SCALE = 0.92f

/** How far below its place it starts, as a fraction of its height. */
private const val ENTER_TRAVEL = 0.06f

private val SHEET_CORNER = 22.dp
