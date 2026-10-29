// Adapted from BitChord (GPL-3.0), PlayerDock.kt and PlayerSheetMotion.kt:
// the player's cover flying between the now-playing bar and the player.
package dev.lelonio.square.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize

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

    private var miniArt: LayoutCoordinates? = null
    private var miniCorner: Dp = 9.dp

    /** The player's picture slot; read live, since the sheet moves it every frame. */
    private var playerArt: LayoutCoordinates? = null

    /** How far the sheet travels from open to gone, in pixels. */
    var travel = 0f

    /** The picture at the top of the player, if it shows one; null with a Canvas or a video there. */
    var playerUrl by mutableStateOf<String?>(null)

    /** The bar's cover. */
    var miniUrl by mutableStateOf<String?>(null)

    fun reportMiniArt(coordinates: LayoutCoordinates, corner: Dp) {
        miniArt = coordinates
        miniCorner = corner
    }

    fun releaseMiniArt(coordinates: LayoutCoordinates) {
        if (miniArt === coordinates) miniArt = null
    }

    /** From the player's picture slot whenever it is placed. */
    fun reportPlayerArt(coordinates: LayoutCoordinates) {
        playerArt = coordinates
    }

    fun releasePlayerArt(coordinates: LayoutCoordinates) {
        if (playerArt === coordinates) playerArt = null
    }

    /** How far below fully open the sheet stands this frame, in pixels. */
    fun offset(): Float = (1f - progress().coerceIn(0f, 1f)) * travel

    /** Both ends known: the trip can be flown rather than faded. */
    fun ready(): Boolean =
        travel > 0f && miniArt?.isAttached == true && playerArt?.isAttached == true &&
            playerUrl != null && miniUrl != null

    /** On the way, not at either end. */
    fun docking(): Boolean {
        val p = progress()
        return p > 0f && p < 1f && ready()
    }

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
    fun contentAlpha(): Float =
        ((progress() - DOCK_FADE_LEAD) / (1f - DOCK_FADE_LEAD)).coerceIn(DOCK_FADE_FLOOR, 1f)

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

        /** A drag down on the player closes it past this far, or this fast (Material's sheet, as BitChord's). */
        val CloseDistance = 56.dp
        val CloseVelocity = 125.dp
    }
}

/** The dock around the player, while there is a bar for it to dock into. */
val LocalPlayerDock = staticCompositionLocalOf<PlayerDock?> { null }
