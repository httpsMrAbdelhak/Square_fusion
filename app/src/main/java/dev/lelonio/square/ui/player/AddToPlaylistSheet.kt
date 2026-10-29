package dev.lelonio.square.ui.player

import androidx.compose.foundation.background
import dev.lelonio.square.ui.glass.backdrop.effects.blur
import dev.lelonio.square.ui.glass.backdrop.drawBackdrop
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.regular.Check
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.components.Artwork

/**
 * Which playlist the playing track should go into.
 *
 * The plus in the player opens this. It stands where a heart used to, and the
 * heart is what it fixes: Liked Songs is one playlist out of the account's
 * several, and a button that could only ever add to that one spent its whole
 * width answering a question nobody asked.
 *
 * The rows do not close the sheet. Adding one track to two playlists is a
 * perfectly ordinary thing to want, and a sheet that dismissed itself on the
 * first tap would make it two round trips through the player.
 */
@Composable
fun AddToPlaylistSheet(
    state: MainViewModel.AddToPlaylistState,
    backdrop: Backdrop,
    onSelect: (CatalogPlaylist) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
    // The page out of focus, like the track sheet: this covers the player as
    // often as it covers a list, and a scrim alone left both perfectly readable
    // underneath.
    AnimatedVisibility(
        visible = state.open,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(180)),
        modifier = Modifier.fillMaxSize(),
    ) {
        dev.lelonio.square.ui.components.SheetScrim(backdrop, onDismiss)
    }

    AnimatedVisibility(
        visible = state.open,
        // Up from the bottom edge, since that is where it sits.
        enter = slideInVertically(tween(240)) { it / 6 } + fadeIn(tween(180)),
        exit = slideOutVertically(tween(180)) { it / 6 } + fadeOut(tween(150)),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        dev.lelonio.square.ui.components.SheetPanel(
            backdrop = backdrop,
            shape = RoundedCornerShape(28.dp),
            surfaceColor = GlassFilm,
            // Heavier than the page behind it: the panel samples the unblurred
            // layer, so at the usual radius the rows underneath came through
            // sharper inside the sheet than outside it.
            blurScale = 23.0f,
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                // Swallows taps so one inside the sheet does not dismiss it.
                .clickable(interactionSource = null, indication = null) {},
        ) {
            Column(Modifier.padding(vertical = 18.dp)) {
                Text(
                    stringResource(R.string.add_to_playlist),
                    style = MaterialTheme.typography.titleLarge,
                    color = GlassInk,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp),
                )
                Text(
                    // Says what is being added: the sheet can outlive the track
                    // that opened it, since playback carries on underneath.
                    state.trackTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlassInkDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 12.dp),
                )

                if (state.error != null) {
                    Text(
                        state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                    )
                }

                if (state.playlists.isEmpty()) {
                    Text(
                        stringResource(R.string.no_playlists_in_account),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlassInkDim,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    )
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        steadyOrder(state).forEach { playlist ->
                            PlaylistRow(
                                playlist = playlist,
                                busy = state.busy == playlist.uri,
                                added = state.done == playlist.name,
                                // Liked Songs is the one row that knows where
                                // the track already is, and the one a second
                                // press takes it out of.
                                liked = state.liked && playlist.uri.endsWith(":collection"),
                                inIt = playlist.uri in state.containing,
                                enabled = state.trackUri != null && state.busy == null,
                                onClick = { onSelect(playlist) },
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

/**
 * The picker itself, without a sheet around it.
 *
 * The player puts this in the panel that shows the lyrics and the queue instead
 * of opening a modal over the transport; everywhere else still gets the sheet.
 */
@Composable
fun PlaylistPicker(
    state: MainViewModel.AddToPlaylistState,
    onSelect: (CatalogPlaylist) -> Unit,
) {
            Column(Modifier.padding(vertical = 18.dp)) {
                Text(
                    stringResource(R.string.add_to_playlist),
                    style = MaterialTheme.typography.titleLarge,
                    color = GlassInk,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp),
                )
                Text(
                    // Says what is being added: the sheet can outlive the track
                    // that opened it, since playback carries on underneath.
                    state.trackTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlassInkDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 12.dp),
                )

                if (state.error != null) {
                    Text(
                        state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                    )
                }

                if (state.playlists.isEmpty()) {
                    Text(
                        stringResource(R.string.no_playlists_in_account),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlassInkDim,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    )
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        steadyOrder(state).forEach { playlist ->
                            PlaylistRow(
                                playlist = playlist,
                                busy = state.busy == playlist.uri,
                                added = state.done == playlist.name,
                                // Liked Songs is the one row that knows where
                                // the track already is, and the one a second
                                // press takes it out of.
                                liked = state.liked && playlist.uri.endsWith(":collection"),
                                inIt = playlist.uri in state.containing,
                                enabled = state.trackUri != null && state.busy == null,
                                onClick = { onSelect(playlist) },
                            )
                        }
                    }
                }
            }
}

@Composable
private fun PlaylistRow(
    playlist: CatalogPlaylist,
    busy: Boolean,
    added: Boolean,
    /** In Liked Songs already, so this row removes rather than adds. */
    liked: Boolean = false,
    /** In this playlist already, so this row removes rather than adds. */
    inIt: Boolean = false,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = playlist.artworkUrl,
            title = playlist.name,
            modifier = Modifier.size(44.dp),
            corner = 10.dp,
            decodeSize = 44.dp,
        )
        Text(
            playlist.name,
            style = MaterialTheme.typography.titleMedium,
            color = GlassInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 16.dp)
                .weight(1f),
        )
        // Square Fusion: the mark eases in and out in place, so a tap answers
        // at once without anything in the list moving.
        val mark = when {
            busy -> 1
            liked -> 2
            inIt -> 3
            added -> 4
            else -> 0
        }
        androidx.compose.animation.Crossfade(
            targetState = mark,
            animationSpec = tween(160),
            label = "mark",
            modifier = Modifier.size(20.dp),
        ) { shown ->
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        when (shown) {
            1 -> CircularProgressIndicator(
                color = GlassInkDim,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )

            2 -> Icon(
                PhosphorIcons.Fill.Heart,
                contentDescription = stringResource(R.string.remove_from_liked),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )

            3 -> Icon(
                PhosphorIcons.Regular.Check,
                contentDescription = stringResource(R.string.remove_from_playlist),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )

            4 -> Icon(
                PhosphorIcons.Regular.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        }
        }
    }
}

/**
 * Square Fusion: the rows in [ticksFirst] order while the lists arrive, then
 * held still from the first tap on, so picking a playlist never moves it, or
 * the ones around it, out from under the finger. Lists that arrive later go
 * below.
 */
@Composable
private fun steadyOrder(state: MainViewModel.AddToPlaylistState): List<CatalogPlaylist> {
    val held = androidx.compose.runtime.remember(state.trackUri, state.batch) {
        androidx.compose.runtime.mutableStateOf<List<String>?>(null)
    }
    val touched = state.busy != null || state.done != null || state.removed != null
    val ranked = state.playlists.ticksFirst(state)
    val fixed = held.value
    if (!touched || fixed == null) {
        // The order shown is the one a tap will keep.
        val uris = ranked.map { it.uri }
        if (fixed != uris) androidx.compose.runtime.SideEffect { held.value = uris }
        return ranked
    }
    val byUri = state.playlists.associateBy { it.uri }
    val fresh = ranked.filter { it.uri !in fixed }
    if (fresh.isNotEmpty()) androidx.compose.runtime.SideEffect { held.value = fixed + fresh.map { it.uri } }
    return fixed.mapNotNull(byUri::get) + fresh
}

/**
 * The lists the track is already in, at the top: they are the ones this panel
 * is opened to find when the song is ticked, and further down a long list of
 * playlists they were a scroll away. Otherwise in the order they came, which
 * is the library's.
 */
private fun List<CatalogPlaylist>.ticksFirst(state: MainViewModel.AddToPlaylistState) =
    sortedByDescending { playlist ->
        playlist.uri in state.containing || (state.liked && playlist.uri.endsWith(":collection"))
    }
