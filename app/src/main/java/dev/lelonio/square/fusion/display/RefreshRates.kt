// Inspired by BitChord (GPL-3.0), ui/performance/DisplayRefreshRates.kt.
package dev.lelonio.square.fusion.display

import android.view.Display
import dev.lelonio.square.fusion.Fusion
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The refresh rates the Performance settings offer, from what the screen
 * reports. Only modes at the resolution in use count: another resolution would
 * be a resolution change, not a rate change.
 *
 * A mode named on the window is a *preference*: Android and the phone's own
 * power policy may still run the screen at another rate. What is shown as
 * applied is read back from [Display.getMode], and only that.
 */
object RefreshRates {

    /** The two rates the setting offers. */
    val OFFERED = listOf(60, 120)

    /** Rounded rates of the modes at the current resolution, ascending. */
    fun supported(display: Display?): List<Int> {
        display ?: return emptyList()
        val current = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .map { it.refreshRate.roundToInt() }
            .distinct()
            .sorted()
    }

    fun isSupported(display: Display?, hz: Int): Boolean = supported(display).any { abs(it - hz) <= 1 }

    /** The mode for the chosen rate, or null for Square's own behaviour. */
    fun chosenMode(display: Display?): Int? {
        if (!Fusion.initialised || !Fusion.settings.chooseRefreshRate.value) return null
        display ?: return null
        val hz = Fusion.settings.refreshRateHz.value
        val current = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .filter { abs(it.refreshRate - hz) <= 1f }
            .minByOrNull { abs(it.refreshRate - hz) }
            ?.modeId
    }

    /** The rate of the mode the display is in now, as the system reports it. */
    fun applied(display: Display?): Float? = display?.mode?.refreshRate
}
