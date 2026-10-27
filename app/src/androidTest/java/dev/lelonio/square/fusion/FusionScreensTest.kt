package dev.lelonio.square.fusion

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lelonio.square.R
import dev.lelonio.square.fusion.sources.SourceKind
import dev.lelonio.square.ui.glass.backdrop.backdrops.emptyBackdrop
import dev.lelonio.square.ui.settings.AppIconSection
import dev.lelonio.square.ui.settings.FusionQualitySection
import dev.lelonio.square.ui.settings.FusionSourcesPage
import dev.lelonio.square.ui.settings.FusionStreamInfoPage
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

    @Test
    fun sourcesPageAddsJioSaavnSwitchedOffAndRemovesIt() {
        compose.setContent {
            SquareTheme { FusionSourcesPage(PaddingValues(), header = { Text("Sources") }) }
        }
        compose.onNodeWithText(s(R.string.fusion_add_addon), ignoreCase = true).assertExists()
        compose.onNodeWithText(s(R.string.fusion_no_sources)).assertExists()
        compose.onNode(androidx.compose.ui.test.hasTestTag("fusion-sources-list")).performScrollToNode(hasText(s(R.string.fusion_jiosaavn_add)))
        compose.onNodeWithText(s(R.string.fusion_jiosaavn_add)).performClick()
        compose.waitForIdle()
        val saavn = Fusion.registry.configs.value.single { it.kind == SourceKind.JIOSAAVN }
        assertFalse("JioSaavn is added switched off", saavn.enabled)
        Fusion.registry.remove(saavn.id)
        compose.waitForIdle()
        assertTrue(Fusion.registry.configs.value.none { it.kind == SourceKind.JIOSAAVN })
    }

    @Test
    fun streamInfoSaysUnknownWhenNothingPlays() {
        compose.setContent {
            SquareTheme { FusionStreamInfoPage(PaddingValues(), header = { Text("Info") }) }
        }
        compose.onNodeWithText(s(R.string.fusion_announced), ignoreCase = true).assertExists()
        compose.onAllNodesWithTextCount(s(R.string.fusion_unknown))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) {
        val count = onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size
        assertTrue("\"$text\" shown", count > 0)
    }

    @Test
    fun qualitySectionOffersLosslessOnlyWithAnAddon() {
        scrolling { FusionQualitySection(emptyBackdrop(), onOpenSources = {}, onOpenStreamInfo = {}) }
        compose.onNodeWithText(s(R.string.fusion_on_wifi)).assertExists()
        val locked = s(R.string.fusion_tier_lossless) + " — " + s(R.string.fusion_lossless_needs_addon)
        compose.onAllNodesWithTextCount(locked)
        // Choosing it does nothing without an addon.
        val before = Fusion.settings.wifiTier.value
        compose.onAllNodes(hasText(locked))[0].performScrollTo().performClick()
        assertEquals(before, Fusion.settings.wifiTier.value)
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
        scrolling { SpotifyFreeSection(onSignIn = {}, onOpenLibrary = {}) }
        compose.onNodeWithText(s(R.string.fusion_spotify_sign_in)).assertExists()
    }
}
