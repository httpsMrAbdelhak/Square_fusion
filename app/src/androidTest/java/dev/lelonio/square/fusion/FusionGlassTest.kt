package dev.lelonio.square.fusion

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lelonio.square.ui.glass.backdrop.backdrops.layerBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import dev.lelonio.square.ui.glass.backdrop.drawBackdrop
import dev.lelonio.square.ui.glass.backdrop.effects.blur
import dev.lelonio.square.ui.glass.backdrop.effects.colorControls
import dev.lelonio.square.ui.glass.isGlassSupported
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

/**
 * The glass's blur drawn for real on the device the tests run on (Android 11
 * on the CI's emulator, which has no RenderEffect): black and white stripes
 * under a pane must come out as an even grey.
 */
@RunWith(AndroidJUnit4::class)
class FusionGlassTest {

    @get:Rule
    val rule = createComposeRule()

    private fun spread(bitmap: android.graphics.Bitmap): Double {
        val y = bitmap.height / 2
        val values = (bitmap.width / 4 until bitmap.width * 3 / 4).map { x ->
            val c = bitmap.getPixel(x, y)
            0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c) + 0.114 * android.graphics.Color.blue(c)
        }
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    @Test
    fun glassBlursWhatIsBehindIt() {
        assertTrue("glass is offered on API ${Build.VERSION.SDK_INT}", isGlassSupported() || Build.VERSION.SDK_INT < 30)
        rule.setContent {
            val stage = rememberLayerBackdrop()
            Box(Modifier.size(240.dp)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("stripes")
                        .layerBackdrop(stage)
                        .drawBehind {
                            var x = 0f
                            var black = true
                            while (x < size.width) {
                                drawRect(if (black) Color.Black else Color.White, Offset(x, 0f), Size(6f, size.height))
                                x += 6f
                                black = !black
                            }
                        },
                )
                Box(
                    Modifier
                        .size(140.dp)
                        .align(Alignment.Center)
                        .testTag("glass")
                        .drawBackdrop(
                            backdrop = stage,
                            shape = { RectangleShape },
                            effects = {
                                colorControls(saturation = 1.5f)
                                blur(24.dp.toPx())
                            },
                            highlight = null,
                            shadow = null,
                        ),
                )
            }
        }
        rule.waitForIdle()
        val stripes = spread(rule.onNodeWithTag("stripes").captureToImage().asAndroidBitmap())
        val glass = spread(rule.onNodeWithTag("glass").captureToImage().asAndroidBitmap())
        android.util.Log.i("SquareFusion", "glass test on API ${Build.VERSION.SDK_INT}: stripes spread $stripes, under the glass $glass")
        assertTrue("the stripes are there ($stripes)", stripes > 80.0)
        assertTrue("the glass blurs them ($glass against $stripes)", glass < 20.0)
    }
}
