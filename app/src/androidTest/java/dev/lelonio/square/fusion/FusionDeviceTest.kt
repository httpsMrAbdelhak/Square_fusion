package dev.lelonio.square.fusion

import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lelonio.square.R
import dev.lelonio.square.fusion.display.RefreshRates
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
        assertFalse(Fusion.settings.chooseRefreshRate.value)
        // YouTube Music is the only backend, whatever was saved before.
        val app = context.applicationContext as dev.lelonio.square.SquareApplication
        assertEquals(dev.lelonio.square.backend.BackendId.YOUTUBE_MUSIC, app.preferences.backend.value)
        app.preferences.setBackend(dev.lelonio.square.backend.BackendId.SPOTIFY)
        assertEquals(dev.lelonio.square.backend.BackendId.YOUTUBE_MUSIC, app.preferences.backend.value)
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
    fun playlistCoverIsKeptApartFromTheSource() {
        PlaylistCustomizations.init(context)
        val uri = "spotifree:playlist:testCover"
        val source = dev.lelonio.square.data.CatalogPlaylist(uri = uri, name = "From Spotify", artworkUrl = "https://i.scdn.co/image/original")
        val jpeg = java.io.ByteArrayOutputStream().use { out ->
            android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
                .compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
        PlaylistCustomizations.setCover(uri, jpeg)
        PlaylistCustomizations.setName(uri, "Mine")
        val shown = PlaylistCustomizations.apply(source)
        assertEquals("Mine", shown.name)
        assertTrue(shown.artworkUrl!!.startsWith("file://"))
        assertTrue(java.io.File(android.net.Uri.parse(shown.artworkUrl).path!!).isFile)
        // The source's own data is not touched: a sync brings a new cover and it stays under the choice.
        assertEquals("https://i.scdn.co/image/original", source.artworkUrl)
        val synced = source.copy(artworkUrl = "https://i.scdn.co/image/new")
        assertEquals(shown.artworkUrl, PlaylistCustomizations.apply(synced).artworkUrl)
        // Back to the source's cover.
        PlaylistCustomizations.clearCover(uri)
        assertEquals("https://i.scdn.co/image/new", PlaylistCustomizations.apply(synced).artworkUrl)
        PlaylistCustomizations.forget(uri)
        assertEquals("From Spotify", PlaylistCustomizations.apply(source).name)
    }

    @Test
    fun profilePhotoIsChosenAndCleared() = kotlinx.coroutines.runBlocking {
        ProfilePhoto.init(context)
        val picture = java.io.File(context.cacheDir, "picked.jpg")
        picture.outputStream().use {
            android.graphics.Bitmap.createBitmap(900, 600, android.graphics.Bitmap.Config.ARGB_8888)
                .compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
        }
        assertTrue(ProfilePhoto.set(context, android.net.Uri.fromFile(picture)))
        val kept = ProfilePhoto.custom.value
        assertNotNull(kept)
        val bitmap = android.graphics.BitmapFactory.decodeFile(android.net.Uri.parse(kept).path)
        // Square, cut from the middle.
        assertEquals(bitmap.width, bitmap.height)
        ProfilePhoto.clear()
        assertNull(ProfilePhoto.custom.value)
    }

    @Test
    fun notificationIconFollowsTheChosenIcon() {
        for (icon in AppIcon.entries + AppIcon.Square) {
            val before = AppIcon.changed.value
            AppIcon.apply(context, icon)
            assertEquals(icon, AppIcon.current(context))
            assertTrue("$icon: change announced", AppIcon.changed.value > before)
            val drawable = context.getDrawable(icon.notificationIcon)
            assertNotNull("$icon: notification icon loads", drawable)
            assertTrue("$icon: notification icon has a size", drawable!!.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
        }
        assertEquals(R.drawable.ic_notification, AppIcon.Square.notificationIcon)
        assertTrue(AppIcon.LiquidGlass.notificationIcon != AppIcon.Square.notificationIcon)
    }
}
