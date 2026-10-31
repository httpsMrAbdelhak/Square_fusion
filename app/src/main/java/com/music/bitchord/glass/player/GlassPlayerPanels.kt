/*
 * BitChord Glass: Square's player view switcher (Square, GPL-3.0) and the two
 * views BitChord did not have — Square's effects, and the credits drawn from
 * what BitChord knows about the track. Reached through BitChord's PlayerHost.
 */
package com.music.bitchord.glass.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Info
import com.adamglin.phosphoricons.fill.Queue
import com.adamglin.phosphoricons.fill.SlidersHorizontal
import com.adamglin.phosphoricons.fill.TextAa
import com.adamglin.phosphoricons.fill.VinylRecord
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.Queue
import com.adamglin.phosphoricons.regular.SlidersHorizontal
import com.adamglin.phosphoricons.regular.TextAa
import com.adamglin.phosphoricons.regular.VinylRecord
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.glass.effects.AudioEffects
import com.music.bitchord.glass.effects.BuiltInPresets
import com.music.bitchord.glass.effects.EffectPresetStore
import com.music.bitchord.glass.effects.EffectsPanel
import com.music.bitchord.glass.effects.GlassEffects
import com.music.bitchord.glass.theme.LocalInkOverride
import com.music.bitchord.glass.ui.LiquidBottomTab
import com.music.bitchord.glass.ui.LiquidBottomTabs
import com.music.bitchord.glass.ui.backdrop.backdrops.rememberCanvasBackdrop
import com.music.bitchord.ui.player.GlassPanels

/** Square's player ink: the player is drawn over its artwork, dark, always. */
private val PlayerInk = Color.White
private val PlayerInkDim = Color.White.copy(alpha = 0.68f)

/** Square's switcher; BitChord's player keeps every other control it had. */
@Composable
fun GlassPanelBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier): Boolean {
    val speed by AppSettings.playbackSpeed.collectAsStateWithLifecycle()
    val pitch by AudioEffects.pitch.collectAsStateWithLifecycle()
    val reverb by AudioEffects.reverb.collectAsStateWithLifecycle()
    val effectsOn = speed != 1f || pitch != 1f || reverb > 0f
    val selectedState = rememberUpdatedState(selected)
    val selectedTabIndex = remember { { selectedState.value } }
    // The player's glass has no recording of its own to bend; the capsule is
    // Square's film over the artwork, the indicator still bends the tabs.
    val backdrop = rememberCanvasBackdrop { }
    Box(modifier, contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalInkOverride provides PlayerInk) {
            LiquidBottomTabs(
                selectedTabIndex = selectedTabIndex,
                onTabSelected = onSelect,
                backdrop = backdrop,
                tabsCount = 5,
                accentColor = PlayerInk,
                containerColor = Color.White.copy(alpha = 0.12f),
                indicatorColor = PlayerInk.copy(alpha = 0.18f),
                height = 42.dp,
                lensDepth = 12.dp,
                modifier = Modifier.fillMaxWidth(0.84f),
            ) {
                PanelTab(PhosphorIcons.Regular.VinylRecord, PhosphorIcons.Fill.VinylRecord, stringResource(R.string.glass_panel_cover), selected == GlassPanels.COVER) { onSelect(GlassPanels.COVER) }
                PanelTab(PhosphorIcons.Regular.TextAa, PhosphorIcons.Fill.TextAa, stringResource(R.string.glass_panel_lyrics), selected == GlassPanels.LYRICS) { onSelect(GlassPanels.LYRICS) }
                PanelTab(PhosphorIcons.Regular.Queue, PhosphorIcons.Fill.Queue, stringResource(R.string.glass_panel_queue), selected == GlassPanels.QUEUE) { onSelect(GlassPanels.QUEUE) }
                PanelTab(PhosphorIcons.Regular.SlidersHorizontal, PhosphorIcons.Fill.SlidersHorizontal, stringResource(R.string.glass_panel_effects), selected == GlassPanels.EFFECTS, marked = effectsOn) { onSelect(GlassPanels.EFFECTS) }
                PanelTab(PhosphorIcons.Regular.Info, PhosphorIcons.Fill.Info, stringResource(R.string.glass_panel_credits), selected == GlassPanels.CREDITS) { onSelect(GlassPanels.CREDITS) }
            }
        }
    }
    return true
}

@Composable
private fun RowScope.PanelTab(
    icon: ImageVector,
    activeIcon: ImageVector,
    label: String,
    selected: Boolean,
    marked: Boolean = false,
    onClick: () -> Unit,
) {
    LiquidBottomTab(onClick = onClick) {
        Box(contentAlignment = Alignment.Center) {
            if (marked) {
                Box(
                    Modifier
                        .size(30.dp)
                        .background(Brush.radialGradient(listOf(PlayerInk.copy(alpha = 0.28f), Color.Transparent)), CircleShape),
                )
            }
            Icon(
                imageVector = if (selected) activeIcon else icon,
                contentDescription = label,
                tint = when {
                    selected -> PlayerInk
                    marked -> PlayerInk.copy(alpha = 0.85f)
                    else -> PlayerInkDim
                },
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
fun GlassPanelContent(panel: Int, song: Song, modifier: Modifier) {
    CompositionLocalProvider(LocalInkOverride provides PlayerInk) {
        Box(modifier.fillMaxWidth()) {
            when (panel) {
                GlassPanels.EFFECTS -> Effects()
                GlassPanels.CREDITS -> Credits(song)
            }
        }
    }
}

@Composable
private fun Effects() {
    val context = LocalContext.current
    val store = remember(context) { EffectPresetStore(context) }
    remember(context) { AudioEffects.load(context) }
    val speed by AppSettings.playbackSpeed.collectAsStateWithLifecycle()
    val pitch by AudioEffects.pitch.collectAsStateWithLifecycle()
    val reverb by AudioEffects.reverb.collectAsStateWithLifecycle()
    val custom by store.presets.collectAsStateWithLifecycle()
    EffectsPanel(
        speed = speed,
        pitch = pitch,
        reverb = reverb,
        onSpeed = GlassEffects::setSpeed,
        onPitch = GlassEffects::setPitch,
        onReverb = GlassEffects::setReverb,
        presets = BuiltInPresets + custom,
        onApplyPreset = GlassEffects::apply,
        onSavePreset = { name -> store.save(name, speed, pitch, reverb) },
        onDeletePreset = store::delete,
        backdrop = rememberCanvasBackdrop { },
    )
}

/** Who made the track, as far as BitChord knows it: its artists, record and source. */
@Composable
private fun Credits(song: Song) {
    val artists = song.artists.map { it.name }.filter { it.isNotBlank() }.ifEmpty { listOf(song.artist) }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        CreditGroup(stringResource(R.string.glass_credits_performed), artists)
        song.albumName?.takeIf { it.isNotBlank() }?.let {
            CreditGroup(stringResource(R.string.glass_credits_album), listOf(listOfNotNull(it, song.trackNumber?.let { n -> "#$n" }).joinToString("  ·  ")))
        }
        val source = listOfNotNull(song.playbackSource, song.sourceQuality, song.downloadFormat).filter { it.isNotBlank() }
        if (source.isNotEmpty()) CreditGroup(stringResource(R.string.glass_credits_source), listOf(source.joinToString("  ·  ")))
    }
}

@Composable
private fun CreditGroup(title: String, lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = PlayerInkDim)
        lines.forEach {
            Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = PlayerInk)
        }
    }
}
