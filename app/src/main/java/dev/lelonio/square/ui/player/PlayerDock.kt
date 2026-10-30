// Adapted from BitChord (GPL-3.0), PlayerDock.kt and PlayerSheetMotion.kt:
// the player's cover flying between the now-playing bar and the player.
package dev.lelonio.square.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Square Fusion: the two ends of the cover's trip between the now-playing bar
 * and the player, the way BitChord docks its player.
 *
 * Opening and closing is one movement: the player slides as a sheet and fades
 * a little ahead of its cover, and the cover itself — one picture, never two —
 * travels from the bar's cover to its place on the player, growing as it goes,
 * and back. Each end is reported by the side that owns it, in window
 * coordinates; [NowPlayingSheet] draws the cover in between.
 *
 * Read in layout and draw only, never in composition: a frame of the trip moves
 * a picture and fades a layer, and that is all it costs.
 */
@Stable
class PlayerDock {
    /** 0 on the bar, 1 open: the same value the player's sheet runs on. */
    var progress: () -> Float = { 0f }

    // State, so an open can wait for both ends to be placed (see awaitReady);
    // reported again on every placement with the same object, which is no change.
    private var miniArt by mutableStateOf<LayoutCoordinates?>(null)
    private var miniCorner: Dp = 9.dp

    /** The player's picture slot; read live, since the sheet moves it every frame. */
    private var playerArt by mutableStateOf<LayoutCoordinates?>(null)

    /** How far the sheet travels from open to gone, in pixels. */
    var travel by mutableFloatStateOf(0f)

    /** The picture at the top of the player, if it shows one; null with a Canvas or a video there. */
    var playerUrl by mutableStateOf<String?>(null)

    /** The bar's cover. */
    var miniUrl by mutableStateOf<String?>(null)

    /**
     * Whether the player's own picture is drawn yet. It reads a larger copy of
     * its own, and until that arrives the travelling picture stays where it
     * landed, so the top of the player is never empty in between.
     */
    var playerPictureShown by mutableStateOf(true)

    /**
     * The player's picture itself, as it decoded and draws it, with the url it
     * is for and its enlargement. What flies is this very bitmap, never a copy
     * loaded for the trip: a picture loaded on the way is a hole for a frame or
     * two, which is the blink. Kept after the player closes, so the next open
     * of the same track flies it from its first frame.
     */
    var playerPicture by mutableStateOf<ImageBitmap?>(null)
        private set
    var playerPictureUrl: String? = null
        private set
    var playerPictureZoom = 1f
        private set

    /** The blurred foot and the shade the player draws over its picture; see drawPlayerPictureFinish. */
    var playerPictureBlur: ImageBitmap? = null
        private set
    var playerPictureScrim = androidx.compose.ui.graphics.Color.Black
        private set

    fun reportPlayerPicture(
        url: String,
        picture: ImageBitmap,
        blur: ImageBitmap?,
        zoom: Float,
        scrim: androidx.compose.ui.graphics.Color,
    ) {
        playerPictureUrl = url
        playerPictureZoom = zoom
        playerPictureBlur = blur
        playerPictureScrim = scrim
        playerPicture = picture
    }

    /** The player's picture, if it is the one the player is about to show. */
    fun picture(): ImageBitmap? = playerPicture?.takeIf { playerPictureUrl == playerUrl }

    /**
     * Before an open from the bar: until the player is laid out and its picture
     * is in hand, a slide started would happen to nothing, then jump (BitChord
     * waits for its sheet the same way). Never longer than [LAYOUT_WAIT_MS].
     */
    suspend fun awaitReady() {
        withTimeoutOrNull(LAYOUT_WAIT_MS) {
            snapshotFlow { ready() && picture() != null }.first { it }
        }
    }

    fun reportMiniArt(coordinates: LayoutCoordinates, corner: Dp) {
        miniArt = coordinates
        miniCorner = corner
    }

    fun releaseMiniArt(coordinates: LayoutCoordinates) {
        if (miniArt === coordinates) miniArt = null
    }

    /** The player's controls while the picture travels; see [dockControls]. */
    var controlsLayer: androidx.compose.ui.graphics.layer.GraphicsLayer? = null
    var controlsAnchor: Offset? = null

    /** From the player's picture slot whenever it is placed. */
    fun reportPlayerArt(coordinates: LayoutCoordinates) {
        playerArt = coordinates
    }

    fun releasePlayerArt(coordinates: LayoutCoordinates) {
        if (playerArt === coordinates) playerArt = null
    }

    /** How far below fully open the sheet stands this frame, in pixels. */
    fun offset(): Float = (1f - progress().coerceIn(0f, 1f)) * travel

    /**
     * Whether the player slides as a sheet over the bar, whatever is at its
     * top: a bar there to slide over is all it takes. Held at the bottom until
     * it is laid out, rather than faded in from next to nothing.
     */
    fun slides(): Boolean = travel > 0f && miniArt?.isAttached == true && miniUrl != null

    /** Both ends known: the trip can be flown rather than faded. */
    fun ready(): Boolean =
        travel > 0f && miniArt?.isAttached == true && playerArt?.isAttached == true &&
            playerUrl != null && miniUrl != null

    /** On the way, not at either end. */
    fun docking(): Boolean {
        val p = progress()
        return p > 0f && p < 1f && ready()
    }

    /** Whether the travelling picture is drawn: on the way, or landed while the player's own is not in yet. */
    fun pictureOnTop(): Boolean = docking() || (progress() >= 1f && ready() && !playerPictureShown)

    /**
     * The cover's rect this frame, in window pixels. It rides as much of the
     * sheet's travel as it can without passing the bar's cover, so under a
     * finger it follows the drag, and it lands on the bar with its speed run
     * down rather than overshooting it (BitChord's `dockRide`).
     */
    fun coverRect(): Rect? {
        val mini = miniArt?.windowRect() ?: return null
        // Where the slot would be with the player fully open: where it is this
        // frame, less how far down the sheet has it.
        val open = playerArt?.windowRect()?.translate(0f, -offset()) ?: return null
        val t = progress().coerceIn(0f, 1f)
        val ride = if (travel > 0f) ((mini.top - open.top) / travel).coerceIn(0f, 1f) else 0f
        return androidx.compose.ui.geometry.lerp(mini, open.translate(0f, offset() * ride), t)
    }

    /** The cover's corner this frame, the bar's rounding giving way to the player's square edge. */
    fun coverCorner(): Dp = miniCorner * (1f - progress().coerceIn(0f, 1f))

    /** The rest of the player gets out of the way a little ahead of the cover. */
    fun contentAlpha(): Float {
        val linear = ((progress() - DOCK_FADE_LEAD) / (1f - DOCK_FADE_LEAD)).coerceIn(0f, 1f)
        // Eased out rather than straight: the player stays as it is while a
        // finger holds it near open, where straight it was already a few
        // percent see-through, enough for a light page behind to pale the
        // top of the player and its buttons. Gone at the same point as before.
        val remaining = 1f - linear
        return (1f - remaining * remaining).coerceAtLeast(DOCK_FADE_FLOOR)
    }

    private fun LayoutCoordinates.windowRect(): Rect? {
        if (!isAttached) return null
        val size = size.toSize()
        if (size.width <= 0f) return null
        // Both corners through every transform above it: the bar draws its
        // player scaled, and the cover is scaled while paused.
        return Rect(localToWindow(Offset.Zero), localToWindow(Offset(size.width, size.height)))
    }

    companion object {
        /** BitChord's: how far into the trip the rest of the player starts to show. */
        const val DOCK_FADE_LEAD = 0.15f

        /** BitChord's: how long an open waits for the player to be laid out. */
        const val LAYOUT_WAIT_MS = 300L

        /** Where an open from the bar starts: the player composed, still out of sight. */
        const val OPEN_START = 0.002f

        /** Never quite zero, so the faded player keeps drawing. */
        const val DOCK_FADE_FLOOR = 1f / 255f

        /**
         * BitChord's slide: critically damped, about 0.45 s of response. It
         * leaves briskly and spends most of its time settling, which is where
         * the eye is following the cover home. Never bounces.
         */
        val Slide = spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 195f,
            visibilityThreshold = 0.0005f,
        )

        /** A pull up from the bar is a flick from this speed on (BitChord's). */
        val PullFlingVelocity = 400.dp

    }
}

/**
 * Square Fusion: the player's controls, drawn over the travelling picture
 * rather than under it.
 *
 * Open, the arrow, the line above the picture, the video button and the title
 * sit on top of the picture. The travelling picture is drawn over the whole
 * player (it must not fade with it), and covered them for the length of every
 * trip — the buttons at the top went the moment a drag began. So while the
 * picture travels, the controls are recorded rather than drawn in place, and
 * the dock draws them back over the picture, where the player stands and as
 * faded as it is: the same layers as open, all the way to the bar.
 * BitChord's sleeve, the other way round.
 */
@androidx.compose.runtime.Composable
fun Modifier.dockControls(): Modifier {
    val dock = LocalPlayerDock.current ?: return this
    val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    androidx.compose.runtime.DisposableEffect(dock, layer) {
        dock.controlsLayer = layer
        onDispose { if (dock.controlsLayer === layer) dock.controlsLayer = null }
    }
    // Flips twice a trip; read straight, every frame of it redrew the controls.
    val onTop by androidx.compose.runtime.remember(dock) {
        androidx.compose.runtime.derivedStateOf { dock.pictureOnTop() }
    }
    return this
        .onGloballyPositioned { coordinates ->
            // Where they stand with the player open: here less the sheet's
            // offset, which is what moves them while the sheet slides.
            if (dock.ready()) dock.controlsAnchor = coordinates.positionInWindow() - Offset(0f, dock.offset())
        }
        .drawWithContent {
            if (onTop && dock.controlsAnchor != null) {
                layer.record { this@drawWithContent.drawContent() }
            } else {
                drawContent()
            }
        }
}

/** The dock around the player, while there is a bar for it to dock into. */
val LocalPlayerDock = staticCompositionLocalOf<PlayerDock?> { null }
