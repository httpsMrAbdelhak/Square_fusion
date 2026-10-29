package dev.lelonio.square.fusion

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lelonio.square.R
import dev.lelonio.square.ui.components.CropShape
import dev.lelonio.square.ui.components.ImageCropDialog
import dev.lelonio.square.ui.glass.backdrop.backdrops.emptyBackdrop
import dev.lelonio.square.ui.settings.AppIconSection
import dev.lelonio.square.ui.settings.PerformanceSection
import dev.lelonio.square.ui.settings.SpotifyFreeSection
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Square Fusion's settings screens, drawn with Square's theme and components,
 * and driven the way a person would: by the text on screen.
 */
@RunWith(AndroidJUnit4::class)
class FusionScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun s(id: Int) = context.getString(id)

    private fun scrolling(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        SquareTheme { Column(Modifier.verticalScroll(rememberScrollState())) { content() } }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) {
        val count = onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size
        assertTrue("\"$text\" shown", count > 0)
    }

    @Test
    fun iconPickerSwitchesAndComesBackToSquare() {
        scrolling { AppIconSection() }
        compose.onNodeWithText("Pixel Glass").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(AppIcon.PixelGlass, AppIcon.current(context))
        compose.onNodeWithText("Square · " + s(R.string.fusion_default)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(AppIcon.Square, AppIcon.current(context))
    }

    @Test
    fun performanceIsOffByDefaultAndOffersRatesWhenOn() {
        scrolling { PerformanceSection(emptyBackdrop()) }
        assertFalse(Fusion.settings.chooseRefreshRate.value)
        compose.onNodeWithText(s(R.string.fusion_rate_applied)).assertExists()
        compose.onNodeWithText(s(R.string.fusion_choose_rate)).performClick()
        compose.waitForIdle()
        try {
            assertTrue(Fusion.settings.chooseRefreshRate.value)
            compose.onAllNodesWithTextCount("60 Hz")
        } finally {
            Fusion.settings.setChooseRefreshRate(false)
        }
    }

    @Test
    fun spotifySectionAsksToSignInWhenNotConnected() {
        scrolling { SpotifyFreeSection(onSignIn = {}) }
        compose.onNodeWithText(s(R.string.fusion_spotify_sign_in)).assertExists()
    }

    /** A wide picture, red on the left half and blue on the right, as a file. */
    private fun widePicture(): android.net.Uri {
        val bitmap = android.graphics.Bitmap.createBitmap(800, 400, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).apply {
            drawColor(android.graphics.Color.RED)
            drawRect(400f, 0f, 800f, 400f, android.graphics.Paint().apply { color = android.graphics.Color.BLUE })
        }
        val file = java.io.File(context.cacheDir, "crop-test-source.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return android.net.Uri.fromFile(file)
    }

    private fun crop(shape: CropShape, confirm: Boolean): Pair<android.net.Uri?, Boolean> {
        var result: android.net.Uri? = null
        var dismissed = false
        val source = widePicture()
        compose.setContent {
            SquareTheme {
                ImageCropDialog(source = source, shape = shape, onCropped = { result = it }, onDismiss = { dismissed = true })
            }
        }
        compose.waitForIdle()
        Thread.sleep(1500)
        compose.onNodeWithContentDescription(s(if (confirm) R.string.save else R.string.cancel)).performClick()
        compose.waitUntil(10_000) { result != null || dismissed }
        return result to dismissed
    }

    // One content per test: a compose rule takes setContent only once.
    @Test
    fun cropSquareGivesASquareImageWithoutDistortion() = confirmedCrop(CropShape.SQUARE)

    @Test
    fun cropCircleGivesASquareImageWithoutDistortion() = confirmedCrop(CropShape.CIRCLE)

    private fun confirmedCrop(shape: CropShape) {
        run {
            val (out, _) = crop(shape, confirm = true)
            assertTrue("$shape: a cropped file", out != null)
            val image = android.graphics.BitmapFactory.decodeFile(out!!.path)
            assertTrue("$shape: square output ${image.width}x${image.height}", image.width == image.height && image.width > 0)
            // Centred on a 2:1 picture: the square spans the middle, red then blue, not stretched.
            val left = image.getPixel(image.width / 8, image.height / 2)
            val right = image.getPixel(image.width * 7 / 8, image.height / 2)
            assertTrue("$shape: red on the left", android.graphics.Color.red(left) > 200 && android.graphics.Color.blue(left) < 60)
            assertTrue("$shape: blue on the right", android.graphics.Color.blue(right) > 200 && android.graphics.Color.red(right) < 60)
        }
    }

    @Test
    fun cropCancelKeepsThePreviousImage() {
        val (out, dismissed) = crop(CropShape.SQUARE, confirm = false)
        assertTrue(dismissed)
        assertEquals(null, out)
    }
}
