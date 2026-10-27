package dev.lelonio.square.fusion

import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lelonio.square.fusion.audio.DeviceCodecs
import dev.lelonio.square.fusion.audio.LosslessOutput
import dev.lelonio.square.fusion.audio.Normalization
import dev.lelonio.square.fusion.display.RefreshRates
import dev.lelonio.square.fusion.sources.SourceKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Square Fusion's additions against a real Android: the package manager, the
 * display, the audio service, the codec list and the app's own initialisation.
 */
@RunWith(AndroidJUnit4::class)
class FusionDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun backToSquareIcon() {
        AppIcon.apply(context, AppIcon.Square)
    }

    private fun launcherEntries() = context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),
        0,
    )

    @Test
    fun squareIconIsTheDefault() {
        val entries = launcherEntries()
        assertEquals(1, entries.size)
        assertEquals("dev.lelonio.square.LauncherSquare", entries.single().activityInfo.name)
        assertEquals(AppIcon.Square, AppIcon.current(context))
    }

    @Test
    fun everyIconSwitchesTheLauncherEntryAndStillOpensMainActivity() {
        for (icon in AppIcon.entries + AppIcon.Square) {
            AppIcon.apply(context, icon)
            val entries = launcherEntries()
            assertEquals("$icon: one launcher entry", 1, entries.size)
            val entry = entries.single().activityInfo
            assertEquals("dev.lelonio.square.${icon.alias}", entry.name)
            assertEquals("dev.lelonio.square.ui.MainActivity", entry.targetActivity)
            assertEquals(icon, AppIcon.current(context))
            // Explicit intents, notifications and links still reach MainActivity.
            val explicit = context.packageManager.resolveActivity(
                Intent().setClassName(context.packageName, "dev.lelonio.square.ui.MainActivity"),
                0,
            )
            assertNotNull("$icon: MainActivity resolvable", explicit)
            val link = context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse("spotify:track:abc")).setPackage(context.packageName),
                0,
            )
            assertEquals("dev.lelonio.square.ui.MainActivity", link.single().activityInfo.name)
        }
    }

    @Test
    fun fusionIsInitialisedWithSquareDefaults() {
        assertTrue(Fusion.initialised)
        assertFalse(Fusion.settings.normalization.value)
        assertFalse(Fusion.settings.chooseRefreshRate.value)
        assertFalse(Fusion.registry.configs.value.any { it.kind == SourceKind.JIOSAAVN && it.enabled })
        // With nothing configured, a song is never offered to a source.
        assertNull(Fusion.substituteBlocking("ytmusic:track:dQw4w9WgXcQ", "dQw4w9WgXcQ"))
    }

    @Test
    fun registryPersistsInSharedPreferences() {
        val added = Fusion.registry.add(SourceKind.ADDON, "https://addon.invalid", "Test")
        try {
            val prefs = context.getSharedPreferences("square_fusion_sources", 0).getString("sources", "")!!
            assertTrue(prefs.contains("addon.invalid"))
            Fusion.registry.setEnabled(added.id, false)
            assertFalse(Fusion.registry.config(added.id)!!.enabled)
        } finally {
            Fusion.registry.remove(added.id)
        }
        assertNull(Fusion.registry.config(added.id))
    }

    @Test
    fun refreshRatesReadTheRealDisplay() {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val supported = RefreshRates.supported(display)
        assertTrue("supported rates: $supported", supported.isNotEmpty())
        assertNotNull(RefreshRates.applied(display))
        // Off by default: Square's own behaviour, no chosen mode.
        assertNull(RefreshRates.chosenMode(display))
        // A rate the screen does not have is reported as such.
        assertFalse(RefreshRates.isSupported(display, 1000))
    }

    @Test
    fun audioOutputAndCodecsAreReadWithoutPermission() {
        LosslessOutput.init(context)
        LosslessOutput.refresh()
        val state = LosslessOutput.state.value
        // Whatever the emulator has, an unknown Bluetooth codec is never counted.
        if (state.bluetoothCodecUnknown) assertFalse(state.capable)
        // The codec list answers, with or without a Dolby decoder.
        assertNotNull(DeviceCodecs.atmosDecoders)
    }

    @Test
    fun normalisationOffLeavesTheLevelAlone() {
        assertEquals(1f, Normalization.levelFor("dQw4w9WgXcQ"), 0f)
    }
}
