// Adapted from BitChord (GPL-3.0), AppIcon.kt. The icon files are BitChord's
// originals, copied byte for byte; only the default is Square's own icon.
package dev.lelonio.square.fusion

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dev.lelonio.square.R

/**
 * The launcher icon, switched by enabling one activity-alias and disabling the
 * others (AndroidManifest.xml). MainActivity itself is never touched, so
 * explicit intents, notifications, widgets and links keep reaching it.
 *
 * Launcher limits: a pinned shortcut or home-screen icon made from an alias
 * that gets disabled is removed or greyed by most launchers, and some
 * launchers take a few seconds (or a restart) to show the new icon.
 */
enum class AppIcon(val alias: String, val iconRes: Int, val label: String) {
    Square("LauncherSquare", R.mipmap.ic_launcher, "Square"),
    BitChord("LauncherBitChord", R.mipmap.bitchord_ic_launcher, "BitChord"),
    LiquidGlass("LauncherGlassLight", R.mipmap.ic_liquid_glass, "Liquid Glass"),
    LiquidGlassDark("LauncherGlassDark", R.mipmap.ic_liquid_glass_dark, "Liquid Glass Dark"),
    LiquidGlassClear("LauncherGlassClear", R.mipmap.ic_liquid_glass_clear, "Liquid Glass Clear"),
    LiquidGlassClearDark("LauncherGlassClearDark", R.mipmap.ic_liquid_glass_clear_dark, "Liquid Glass Clear Dark"),
    SamsungGlass("LauncherSamsungGlass", R.mipmap.ic_samsung_glass, "Samsung Glass"),
    SamsungGlassDark("LauncherSamsungGlassDark", R.mipmap.ic_samsung_glass_dark, "Samsung Glass Dark"),
    SamsungGlassClear("LauncherSamsungGlassClear", R.mipmap.ic_samsung_glass_clear, "Samsung Glass Clear"),
    SamsungGlassClearDark("LauncherSamsungGlassClearDark", R.mipmap.ic_samsung_glass_clear_dark, "Samsung Glass Clear Dark"),
    PixelGlass("LauncherPixelGlass", R.mipmap.ic_pixel_glass, "Pixel Glass"),
    PixelGlassDark("LauncherPixelGlassDark", R.mipmap.ic_pixel_glass_dark, "Pixel Glass Dark"),
    PixelGlassClear("LauncherPixelGlassClear", R.mipmap.ic_pixel_glass_clear, "Pixel Glass Clear"),
    PixelGlassClearDark("LauncherPixelGlassClearDark", R.mipmap.ic_pixel_glass_clear_dark, "Pixel Glass Clear Dark");

    /**
     * The media notification's small icon for this choice: the icon's own
     * monochrome layer, the silhouette Android draws that icon from.
     */
    val notificationIcon: Int
        get() = when (this) {
            Square -> R.drawable.ic_notification
            BitChord -> R.drawable.ic_notification_bitchord
            else -> R.drawable.ic_notification_glass
        }

    private fun component(context: Context) =
        ComponentName(context.packageName, "dev.lelonio.square.$alias")

    companion object {
        fun current(context: Context): AppIcon {
            val pm = context.packageManager
            return entries.firstOrNull {
                pm.getComponentEnabledSetting(it.component(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } ?: Square
        }

        private val _changed = kotlinx.coroutines.flow.MutableStateFlow(0)

        /** Bumped each time another icon is chosen; the playback notification follows it. */
        val changed: kotlinx.coroutines.flow.StateFlow<Int> = _changed

        /** Enables [icon] first, then disables the rest, so there is never a moment with no launcher entry. */
        fun apply(context: Context, icon: AppIcon) {
            applyComponents(context, icon)
            _changed.value++
        }

        private fun applyComponents(context: Context, icon: AppIcon) {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                icon.component(context),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            entries.filter { it != icon }.forEach {
                pm.setComponentEnabledSetting(
                    it.component(context),
                    // Square's alias is enabled in the manifest: it has to be
                    // switched off explicitly. The others go back to their
                    // manifest default, which is off.
                    if (it == Square) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
