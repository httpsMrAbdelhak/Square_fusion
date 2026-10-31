package com.music.bitchord.glass.integration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.glass.data.GlassPrefs
import com.music.bitchord.glass.ui.LocalAppBackdrop
import com.music.bitchord.glass.ui.LocalGlassEffectConfig
import com.music.bitchord.glass.ui.backdrop.Backdrop
import com.music.bitchord.ui.components.backdrop.Backdrop as BitChordBackdrop

/**
 * BitChord Glass: where Square's glass meets BitChord's.
 *
 * BitChord already records its page once for its own glass. Square's surfaces
 * sample the very same recording through [bridge] — the two vendored copies of
 * Kyant's backdrop library declare the same interface — so nothing is recorded
 * twice. The material is the listener's, from Square's glass settings.
 */
@Composable
fun ProvideSquareGlass(appBackdrop: BitChordBackdrop, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { GlassPrefs.of(context) }
    val config by store.config.collectAsStateWithLifecycle()
    val backdrop = remember(appBackdrop) { bridge(appBackdrop) }
    CompositionLocalProvider(
        LocalAppBackdrop provides backdrop,
        LocalGlassEffectConfig provides config,
        content = content,
    )
}

/** BitChord's backdrop seen as Square's. */
fun bridge(inner: BitChordBackdrop): Backdrop = object : Backdrop {
    override val isCoordinatesDependent: Boolean get() = inner.isCoordinatesDependent

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        with(inner) { drawBackdrop(density, coordinates, layerBlock) }
    }
}

/**
 * Square's glass settings inside BitChord's settings. The sliders' own glass
 * samples a plain backdrop of the page colour: the page itself is inside the
 * recording the app's glass samples, and a pane may not sample itself.
 */
@Composable
fun GlassSettingsPage(contentPadding: androidx.compose.foundation.layout.PaddingValues) {
    val page = androidx.compose.material3.MaterialTheme.colorScheme.background
    val backdrop = com.music.bitchord.glass.ui.backdrop.backdrops.rememberCanvasBackdrop { drawRect(page) }
    com.music.bitchord.glass.settings.GlassPage(
        backdrop = backdrop,
        contentPadding = contentPadding,
        header = {
            com.music.bitchord.ui.screens.SettingsPageTitle(
                androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.glass_settings_title),
            )
        },
    )
}
