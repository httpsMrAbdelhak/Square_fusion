package dev.lelonio.square.ui.player

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/**
 * Drag down anywhere on the screen to close it.
 *
 * A gesture, not a transform: the screen does not follow the finger. It used to
 * — it slid, shrank and faded with the drag — but the player is already a sheet
 * that animates itself into the now-playing bar, so a second, different
 * displacement of the same content on the same axis meant a downward drag pulled
 * the whole screen around before anything committed. Once the drag passes the
 * threshold this hands over to that one animation, which is the only thing that
 * should be moving the player.
 *
 * Wired through [NestedScrollConnection] rather than a plain drag detector
 * because the content scrolls. A drag detector on the parent and a scrolling
 * child fight over the same vertical axis; nested scroll gives a defined order —
 * the list gets the drag first, and only what it cannot use reaches this. So a
 * downward drag scrolls the content until it hits the top, and from there the
 * same continuous movement used to close the screen; now only a drag that starts
 * at the top does, see onPostScroll. The pointer input below covers the
 * parts that do not scroll at all.
 */
@Composable
fun DismissibleScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Square Fusion: when set, the drag moves the player's own open-to-closed
     * animation with the finger, by this many pixels (down is positive), and
     * [onRelease] settles it with the finger's speed, closed or back open as
     * the gesture decided; see FollowingScreen. The one animation the
     * button runs too, so both end the same way; no second movement of the
     * screen is added.
     */
    onDragBy: ((Float) -> Unit)? = null,
    onRelease: ((velocity: Float, close: Boolean) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (onDragBy != null && onRelease != null) {
        FollowingScreen(onDragBy, onRelease, modifier, content)
        return
    }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    val threshold = with(density) { DISMISS_THRESHOLD.toPx() }

    // Plain vars in a remembered holder rather than state: nothing recomposes on
    // them, they only have to survive between drag events.
    val travel = remember { Travel() }
    val lock = remember { mutableStateOf(false) }

    fun reset() {
        travel.distance = 0f
        travel.fired = false
        travel.scrolled = null
    }

    fun drag(amount: Float) {
        if (travel.fired || lock.value) return
        // Upward movement gives the accumulated distance back instead of going
        // negative, so a drag that wanders up and down does not close the player
        // on its total rather than its extent.
        travel.distance = (travel.distance + amount).coerceAtLeast(0f)
        if (travel.distance >= threshold) {
            travel.fired = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onDismiss()
        }
    }

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0) travel.distance = 0f
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Only what the content could not use: at the top of the scroll
                // this is the whole drag, anywhere else it is nothing.
                // Decided on the gesture's first movement: if that moved the
                // content, the gesture is a scroll to the end, and reaching the
                // top does not turn it into a close. Scrolling up the queue to
                // what was already played closed the player as soon as the list
                // ran out, in the same movement. Only a drag that starts with
                // the content already at the top closes it.
                if (travel.scrolled == null) travel.scrolled = consumed.y != 0f
                if (available.y > 0 && !lock.value && travel.scrolled == false) {
                    drag(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                reset()
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier
            .nestedScroll(connection)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { reset() },
                    onDragEnd = { reset() },
                    onDragCancel = { reset() },
                ) { change, amount ->
                    change.consume()
                    drag(amount)
                }
            },
    ) {
        CompositionLocalProvider(LocalDismissLock provides lock) { content() }
    }
}

/**
 * Set by whatever inside takes a downward drag for its own, such as a queue row
 * being moved by its handle: while it holds, dragging down does not close.
 */
val LocalDismissLock = staticCompositionLocalOf<MutableState<Boolean>> { mutableStateOf(false) }

/**
 * Square Fusion: [DismissibleScreen] with the player following the finger.
 *
 * The same gesture as the one above, the one this app always had: nothing is
 * decided before the finger has come [DISMISS_THRESHOLD] down, the haptic tick
 * says so the moment it has, and wandering back up gives the distance back.
 * What changed is that the player is held by the finger on the way, rather
 * than standing still until the threshold and then leaving on its own: it
 * gives a little at first — a few dp of nothing, then a third of the finger's
 * travel, which is the resistance — and follows one for one once past the
 * threshold. Let go short of it and the player settles back open; let go past
 * it and it carries on to the bar with the finger's speed.
 */
@Composable
private fun FollowingScreen(
    onDragBy: (Float) -> Unit,
    onRelease: (velocity: Float, close: Boolean) -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val threshold = with(density) { DISMISS_THRESHOLD.toPx() }
    val slack = with(density) { FOLLOW_SLACK.toPx() }
    val quickClose = with(density) { QUICK_CLOSE_VELOCITY.toPx() }
    val lock = remember { mutableStateOf(false) }
    val travel = remember { Travel() }
    val tracker = remember { androidx.compose.ui.input.pointer.util.VelocityTracker() }

    // How far the player stands from open for a finger [d] down.
    fun shown(d: Float): Float = when {
        d <= slack -> 0f
        d < threshold -> (d - slack) * FOLLOW_RESISTANCE
        else -> (threshold - slack) * FOLLOW_RESISTANCE + (d - threshold)
    }

    fun drag(amount: Float) {
        val before = travel.distance
        travel.distance = (travel.distance + amount).coerceAtLeast(0f)
        if (!travel.armed && travel.distance >= threshold) {
            travel.armed = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        } else if (travel.armed && travel.distance < threshold) {
            travel.armed = false
        }
        val moved = shown(travel.distance) - shown(before)
        if (moved != 0f) onDragBy(moved)
    }

    fun release(velocity: Float) {
        // Past the threshold, or thrown down hard from most of the way there;
        // anything else is not a close.
        val close = travel.distance >= threshold ||
            (velocity >= quickClose && travel.distance >= threshold * QUICK_CLOSE_SHARE)
        if (travel.distance > 0f || close) onRelease(velocity, close)
        travel.distance = 0f
        travel.armed = false
        travel.fired = false
    }

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Back up while closing: the player follows, the list stays put.
                if (travel.fired && available.y < 0 && source == NestedScrollSource.UserInput) {
                    drag(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                // As before: only a drag that starts with the content at its top.
                if (travel.scrolled == null) travel.scrolled = consumed.y != 0f
                if (available.y > 0 && !lock.value && travel.scrolled == false) {
                    travel.fired = true
                    drag(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val closing = travel.fired
                travel.scrolled = null
                if (closing) {
                    release(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier
            .nestedScroll(connection)
            .pointerInput(Unit) {
                var sideways = false
                var judged = false
                var start = Offset.Zero
                detectVerticalDragGestures(
                    onDragStart = { at ->
                        start = at
                        judged = false
                        tracker.resetTracking()
                        travel.distance = 0f
                        travel.armed = false
                        travel.fired = false
                        travel.finger = 0f
                        sideways = false
                    },
                    onDragEnd = {
                        if (travel.fired) release(tracker.calculateVelocity().y)
                        travel.fired = false
                    },
                    onDragCancel = {
                        if (travel.fired) release(0f)
                        travel.fired = false
                    },
                ) { change, amount ->
                    // A movement more across than down, from its start, is
                    // someone sliding along the switcher or the cover: not
                    // this gesture, whatever its vertical part adds up to.
                    if (!judged) {
                        judged = true
                        val across = kotlin.math.abs(change.position.x - start.x)
                        val down = kotlin.math.abs(change.position.y - start.y)
                        sideways = across > down
                    }
                    if (sideways || lock.value) return@detectVerticalDragGestures
                    // The finger's own path, not its place on a screen that
                    // moves under it as the player closes.
                    travel.finger += amount
                    tracker.addPosition(change.uptimeMillis, Offset(0f, travel.finger))
                    change.consume()
                    travel.fired = true
                    drag(amount)
                }
            },
    ) {
        CompositionLocalProvider(LocalDismissLock provides lock) { content() }
    }
}

private class Travel {
    var distance = 0f
    var fired = false
    /** Square Fusion: past the threshold, its haptic tick given. */
    var armed = false
    /** Square Fusion: the finger's own travel this gesture, for its speed. */
    var finger = 0f
    /** Whether this gesture began by scrolling the content; null before it moves. */
    var scrolled: Boolean? = null
}

private val DISMISS_THRESHOLD = 110.dp

/** Square Fusion: how far the finger goes before the player moves at all; see FollowingScreen. */
private val FOLLOW_SLACK = 12.dp

/** Square Fusion: how much of the finger's travel the player takes before the threshold. */
private const val FOLLOW_RESISTANCE = 0.35f

/** Square Fusion: a throw down this fast closes from [QUICK_CLOSE_SHARE] of the threshold. */
private val QUICK_CLOSE_VELOCITY = 1500.dp
private const val QUICK_CLOSE_SHARE = 0.6f
