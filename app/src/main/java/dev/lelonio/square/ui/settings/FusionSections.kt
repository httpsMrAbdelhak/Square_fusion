package dev.lelonio.square.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowClockwise
import com.adamglin.phosphoricons.regular.BatteryCharging
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretUp
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.HardDrives
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.Waveform
import dev.lelonio.square.R
import dev.lelonio.square.fusion.AppIcon
import dev.lelonio.square.fusion.Fusion
import dev.lelonio.square.fusion.SourceTier
import dev.lelonio.square.fusion.audio.DeviceCodecs
import dev.lelonio.square.fusion.audio.LosslessOutput
import dev.lelonio.square.fusion.audio.Normalization
import dev.lelonio.square.fusion.audio.StreamInspector
import dev.lelonio.square.fusion.display.RefreshRates
import dev.lelonio.square.fusion.sources.SourceConfig
import dev.lelonio.square.fusion.sources.SourceHealth
import dev.lelonio.square.fusion.sources.SourceKind
import dev.lelonio.square.fusion.sources.addon.DetectedFormat
import dev.lelonio.square.fusion.sources.addon.SourceFormats
import dev.lelonio.square.fusion.spotify.SpotifyFreeAccount
import dev.lelonio.square.fusion.spotify.SpotifyFreeLibrary
import dev.lelonio.square.fusion.spotify.SpotifyFreeLoginScreen
import dev.lelonio.square.fusion.spotify.SpotifyPlaylist
import dev.lelonio.square.fusion.spotify.SpotifyTrack
import dev.lelonio.square.ui.TouchRefreshRate
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import kotlinx.coroutines.launch
import java.util.Locale

/*
 * Square Fusion's settings, drawn with Square's own settings rows (Section,
 * ChoiceRow, ActionRow, InfoRow, DownloadSwitch) so they look like the rest
 * of the screen. Nothing here replaces a Square setting.
 */

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = InkDim,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 12.dp),
    )
}

@Composable
private fun SubTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = InkDim,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun tierLabel(tier: SourceTier): String = stringResource(
    when (tier) {
        SourceTier.LOW -> R.string.fusion_tier_low
        SourceTier.MEDIUM -> R.string.fusion_tier_medium
        SourceTier.HIGH -> R.string.fusion_tier_high
        SourceTier.LOSSLESS -> R.string.fusion_tier_lossless
    },
)

// ── Playback › Sound ─────────────────────────────────────────────────────

/** Source quality per network, normalisation, and the ways into Sources and Stream info. */
@Composable
internal fun FusionQualitySection(
    backdrop: dev.lelonio.square.ui.glass.backdrop.Backdrop,
    onOpenSources: () -> Unit,
    onOpenStreamInfo: () -> Unit,
) {
    if (!Fusion.initialised) return
    val settings = Fusion.settings
    val wifi by settings.wifiTier.collectAsStateWithLifecycle()
    val mobile by settings.mobileTier.collectAsStateWithLifecycle()
    val normalization by settings.normalization.collectAsStateWithLifecycle()
    val configs by Fusion.registry.configs.collectAsStateWithLifecycle()
    // Lossless only means something when a source that can answer it is on.
    val addonsOn = configs.any { it.enabled && it.kind == SourceKind.ADDON }

    Section(stringResource(R.string.fusion_sources_quality)) {
        SubTitle(stringResource(R.string.fusion_on_wifi))
        SourceTier.entries.forEach { tier ->
            val allowed = tier != SourceTier.LOSSLESS || addonsOn
            ChoiceRow(
                label = tierLabel(tier) + if (!allowed) " — " + stringResource(R.string.fusion_lossless_needs_addon) else "",
                selected = wifi == tier,
            ) { if (allowed) settings.setWifiTier(tier) }
        }
        RowDivider()
        SubTitle(stringResource(R.string.fusion_on_mobile))
        SourceTier.entries.forEach { tier ->
            val allowed = tier != SourceTier.LOSSLESS || addonsOn
            ChoiceRow(
                label = tierLabel(tier) + if (!allowed) " — " + stringResource(R.string.fusion_lossless_needs_addon) else "",
                selected = mobile == tier,
            ) { if (allowed) settings.setMobileTier(tier) }
        }
        Note(stringResource(R.string.fusion_sources_quality_note))
        RowDivider()
        DownloadSwitch(
            label = stringResource(R.string.fusion_normalization),
            checked = normalization,
            backdrop = backdrop,
            icon = PhosphorIcons.Regular.Waveform,
            onChange = settings::setNormalization,
        )
        Note(stringResource(R.string.fusion_normalization_note))
        RowDivider()
        ActionRow(
            stringResource(R.string.fusion_sources_title) + " (" + configs.count { it.enabled } + "/" + configs.size + ")",
            destructive = false,
            icon = PhosphorIcons.Regular.HardDrives,
            onClick = onOpenSources,
        )
        RowDivider()
        ActionRow(
            stringResource(R.string.fusion_stream_info_title),
            destructive = false,
            icon = PhosphorIcons.Regular.Info,
            onClick = onOpenStreamInfo,
        )
    }
}

// ── Sources page ─────────────────────────────────────────────────────────

@Composable
internal fun FusionSourcesPage(contentPadding: PaddingValues, header: @Composable () -> Unit) {
    if (!Fusion.initialised) return
    val registry = Fusion.registry
    val configs by registry.configs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var verdict by remember { mutableStateOf<String?>(null) }
    var detected by remember { mutableStateOf<DetectedFormat.Addon?>(null) }
    val health = remember { mutableStateMapOf<String, String>() }
    val checking = stringResource(R.string.fusion_checking)
    val unreachable = stringResource(R.string.fusion_unreachable)
    val rejected = stringResource(R.string.fusion_rejected)
    val ok = stringResource(R.string.fusion_ok)

    fun describe(result: SourceHealth): String = when (result) {
        is SourceHealth.Ok -> ok + (result.detail?.let { " · $it" } ?: "")
        is SourceHealth.Unreachable -> "$unreachable · ${result.reason}"
        is SourceHealth.Rejected -> "$rejected · ${result.reason}"
    }

    fun test(config: SourceConfig) {
        health[config.id] = checking
        scope.launch {
            val result = runCatching { registry.sourceFor(config).health() }
                .getOrElse { SourceHealth.Unreachable(it.message ?: it.javaClass.simpleName) }
            health[config.id] = describe(result)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item("top") { header() }

        item("add") {
            Section(stringResource(R.string.fusion_add_addon)) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; verdict = null; detected = null },
                        label = { Text(stringResource(R.string.fusion_addon_url)) },
                        singleLine = true,
                        enabled = !busy,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        TextButton(
                            enabled = !busy && url.isNotBlank(),
                            onClick = {
                                busy = true
                                verdict = checking
                                scope.launch {
                                    val found = SourceFormats.identify(url)
                                    busy = false
                                    found.fold(
                                        onSuccess = { format ->
                                            when (format) {
                                                is DetectedFormat.Addon -> {
                                                    detected = format
                                                    verdict = "$ok · ${format.manifest.displayName}" +
                                                        (format.manifest.version.takeIf { it.isNotBlank() }?.let { " v$it" } ?: "") +
                                                        (if (!format.manifest.downloadsAllowed) " · no downloads" else "") +
                                                        (if (format.manifest.requiresLosslessOutput) " · checkValidLossless" else "")
                                                }
                                                is DetectedFormat.Unsupported -> verdict = "$rejected · ${format.reason}"
                                            }
                                        },
                                        onFailure = { verdict = "$unreachable · ${it.message}" },
                                    )
                                }
                            },
                        ) { Text(stringResource(R.string.fusion_test_connection)) }
                        TextButton(
                            enabled = !busy && detected != null,
                            onClick = {
                                val found = detected
                                if (found == null) {
                                    verdict = null
                                } else {
                                val added = registry.add(SourceKind.ADDON, found.baseUrl, found.manifest.displayName)
                                registry.updatePolicy(added.id, found.manifest.downloadsAllowed, found.manifest.requiresLosslessOutput)
                                url = ""
                                verdict = null
                                detected = null
                                }
                            },
                        ) { Text(stringResource(R.string.fusion_add)) }
                    }
                    verdict?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = InkDim, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                Note(stringResource(R.string.fusion_addon_note))
            }
        }

        item("list-title") {
            if (configs.isEmpty()) {
                Section(stringResource(R.string.fusion_configured_sources)) {
                    Note(stringResource(R.string.fusion_no_sources))
                }
            }
        }

        items(configs, key = { it.id }) { config ->
            val index = configs.indexOf(config)
            Section(if (index == 0) stringResource(R.string.fusion_configured_sources) else null) {
                DownloadSwitch(
                    label = "${index + 1}. ${config.displayName}",
                    checked = config.enabled,
                    backdrop = dev.lelonio.square.ui.glass.backdrop.backdrops.emptyBackdrop(),
                    icon = PhosphorIcons.Regular.HardDrives,
                    onChange = { registry.setEnabled(config.id, it) },
                )
                Text(
                    listOfNotNull(
                        if (config.kind == SourceKind.JIOSAAVN) stringResource(R.string.fusion_jiosaavn_unofficial) else config.baseUrl.substringBefore("?").let(::redactPath),
                        if (!config.allowDownloads) stringResource(R.string.fusion_no_downloads) else null,
                        if (config.requiresLosslessOutput) stringResource(R.string.fusion_requires_lossless_output) else null,
                        health[config.id],
                    ).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                    modifier = Modifier.padding(start = ROW_TEXT_START, end = 18.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = ROW_TEXT_START - 12.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { test(config) }) {
                        Icon(PhosphorIcons.Regular.ArrowClockwise, contentDescription = stringResource(R.string.fusion_test_connection))
                    }
                    IconButton(enabled = index > 0, onClick = { registry.move(config.id, -1) }) {
                        Icon(PhosphorIcons.Regular.CaretUp, contentDescription = stringResource(R.string.fusion_move_up))
                    }
                    IconButton(enabled = index < configs.lastIndex, onClick = { registry.move(config.id, 1) }) {
                        Icon(PhosphorIcons.Regular.CaretDown, contentDescription = stringResource(R.string.fusion_move_down))
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    IconButton(onClick = { registry.remove(config.id); health.remove(config.id) }) {
                        Icon(
                            PhosphorIcons.Regular.Trash,
                            contentDescription = stringResource(R.string.fusion_remove),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        item("jiosaavn") {
            Section(stringResource(R.string.fusion_jiosaavn)) {
                Note(stringResource(R.string.fusion_jiosaavn_warning))
                if (configs.none { it.kind == SourceKind.JIOSAAVN }) {
                    ActionRow(stringResource(R.string.fusion_jiosaavn_add), destructive = false) {
                        // Added switched off: the user turns it on knowingly.
                        val added = registry.add(SourceKind.JIOSAAVN)
                        registry.setEnabled(added.id, false)
                    }
                }
            }
        }
    }
}

/** An addon URL with its path hidden: addons carry private tokens there. */
private fun redactPath(url: String): String {
    val scheme = url.substringBefore("://", "")
    val host = url.substringAfter("://").substringBefore('/')
    val hasPath = url.substringAfter("://").contains('/')
    return if (scheme.isEmpty()) url else "$scheme://$host" + if (hasPath) "/…" else ""
}

// ── Stream info page ─────────────────────────────────────────────────────

@Composable
internal fun FusionStreamInfoPage(contentPadding: PaddingValues, header: @Composable () -> Unit) {
    val report by StreamInspector.report.collectAsStateWithLifecycle()
    val output by LosslessOutput.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val unknown = stringResource(R.string.fusion_unknown)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        LosslessOutput.onPermissionChanged()
    }
    LaunchedEffect(Unit) { LosslessOutput.refresh() }

    fun hz(value: Int?) = value?.let { "%.1f kHz".format(Locale.ROOT, it / 1000f).replace(".0 ", " ") } ?: unknown
    fun bits(value: Int?) = value?.let { "$it bits" } ?: unknown

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item("top") { header() }

        item("announced") {
            Section(stringResource(R.string.fusion_announced)) {
                val announced = report.announced
                InfoRow(stringResource(R.string.fusion_source), announced?.sourceName ?: unknown)
                val format = announced?.format
                InfoRow(
                    stringResource(R.string.fusion_codec),
                    format?.codec?.uppercase(Locale.ROOT)?.let { if (format?.codecInferred == true) "$it (${stringResource(R.string.fusion_inferred)})" else it } ?: unknown,
                )
                InfoRow(stringResource(R.string.fusion_sample_rate), hz(format?.sampleRateHz))
                InfoRow(stringResource(R.string.fusion_bit_depth), bits(format?.bitDepth))
                InfoRow(
                    stringResource(R.string.fusion_bitrate),
                    format?.kbps?.let { if (format?.kbpsInferred == true) "≈ $it kbps (${stringResource(R.string.fusion_inferred)})" else "$it kbps" } ?: unknown,
                )
                Note(stringResource(R.string.fusion_announced_note))
            }
        }

        item("received") {
            Section(stringResource(R.string.fusion_received)) {
                val received = report.received
                InfoRow(stringResource(R.string.fusion_codec), received?.mimeType ?: unknown)
                InfoRow(stringResource(R.string.fusion_sample_rate), hz(received?.sampleRateHz))
                InfoRow(stringResource(R.string.fusion_bit_depth), bits(received?.bitDepth))
                InfoRow(stringResource(R.string.fusion_channels), received?.channels?.toString() ?: unknown)
                InfoRow(stringResource(R.string.fusion_bitrate), received?.bitrateKbps?.let { "$it kbps" } ?: unknown)
                InfoRow(stringResource(R.string.fusion_decoder), received?.decoderName ?: unknown)
                InfoRow(
                    stringResource(R.string.fusion_lossless),
                    when (report.receivedLossless) {
                        true -> stringResource(R.string.fusion_yes)
                        false -> stringResource(R.string.fusion_no)
                        null -> unknown
                    },
                )
            }
        }

        item("verdict") {
            Section(stringResource(R.string.fusion_verification)) {
                @Composable
                fun verdict(v: StreamInspector.Verdict) = stringResource(
                    when (v) {
                        StreamInspector.Verdict.MATCHES -> R.string.fusion_matches
                        StreamInspector.Verdict.DIFFERS -> R.string.fusion_differs
                        StreamInspector.Verdict.UNKNOWN -> R.string.fusion_unknown
                    },
                )
                InfoRow(stringResource(R.string.fusion_codec), verdict(report.codecVerdict))
                InfoRow(stringResource(R.string.fusion_sample_rate), verdict(report.sampleRateVerdict))
                InfoRow(stringResource(R.string.fusion_bit_depth), verdict(report.bitDepthVerdict))
            }
        }

        item("delivered") {
            Section(stringResource(R.string.fusion_delivered)) {
                val delivered = report.delivered
                InfoRow(stringResource(R.string.fusion_sample_rate), hz(delivered?.sampleRateHz))
                InfoRow(
                    stringResource(R.string.fusion_bit_depth),
                    delivered?.let { if (it.float) "32 bits float" else bits(it.bitDepth) } ?: unknown,
                )
                InfoRow(stringResource(R.string.fusion_mixer_rate), report.mixerRateHz?.let { hz(it) } ?: unknown)
                Note(stringResource(R.string.fusion_chain_note))
            }
        }

        item("atmos") {
            // Shown only for a stream that really is E-AC-3 JOC.
            if (report.atmos != StreamInspector.Atmos.NONE) {
                Section("Dolby Atmos") {
                    Note(
                        stringResource(
                            if (report.atmos == StreamInspector.Atmos.IN_STREAM_DECODER_PRESENT) {
                                R.string.fusion_atmos_decoder
                            } else {
                                R.string.fusion_atmos_not_rendered
                            },
                        ),
                    )
                }
            }
        }

        item("output") {
            Section(stringResource(R.string.fusion_output)) {
                InfoRow(
                    stringResource(R.string.fusion_output_device),
                    output.via?.name ?: if (output.bluetoothConnected) "BLUETOOTH" else stringResource(R.string.fusion_output_other),
                )
                if (output.bluetoothConnected) {
                    InfoRow(stringResource(R.string.fusion_bt_codec), output.bluetoothCodec ?: unknown)
                }
                InfoRow(
                    stringResource(R.string.fusion_lossless_output),
                    stringResource(if (output.capable) R.string.fusion_yes else R.string.fusion_no),
                )
                InfoRow("Dolby Atmos (E-AC-3 JOC)", if (DeviceCodecs.playsDolbyAtmos) DeviceCodecs.atmosDecoders.joinToString() else stringResource(R.string.fusion_no_decoder))
                if (output.needsPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    RowDivider()
                    ActionRow(stringResource(R.string.fusion_bt_permission), destructive = false) {
                        permission.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                    }
                    Note(stringResource(R.string.fusion_bt_permission_note))
                }
            }
        }

        item("loudness") {
            val id = report.mediaId?.substringAfterLast(':')
            val db = id?.let { Normalization.loudnessDb(it) }
            Section(stringResource(R.string.fusion_normalization)) {
                InfoRow("loudnessDb", db?.let { "%.1f dB".format(Locale.ROOT, it) } ?: unknown)
                InfoRow(stringResource(R.string.fusion_applied_level), "%.2f".format(Locale.ROOT, Normalization.levelFor(id)))
            }
        }
    }
}

// ── Account › Spotify ────────────────────────────────────────────────────

@Composable
internal fun SpotifyFreeSection(onSignIn: () -> Unit, onOpenLibrary: () -> Unit) {
    val connected by SpotifyFreeAccount.connected.collectAsStateWithLifecycle()
    Section(stringResource(R.string.fusion_spotify_library)) {
        if (connected) {
            InfoRow(stringResource(R.string.fusion_spotify_library), stringResource(R.string.connected))
            RowDivider()
            ActionRow(stringResource(R.string.fusion_spotify_browse), destructive = false, onClick = onOpenLibrary)
            RowDivider()
            ActionRow(stringResource(R.string.disconnect), destructive = true) { SpotifyFreeAccount.disconnect() }
        } else {
            ActionRow(stringResource(R.string.fusion_spotify_sign_in), destructive = false, onClick = onSignIn)
        }
        Note(stringResource(R.string.fusion_spotify_note))
    }
}

@Composable
internal fun SpotifyFreeLoginPage(header: @Composable () -> Unit, contentPadding: PaddingValues, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        header()
        Note(stringResource(R.string.fusion_spotify_login_note))
        SpotifyFreeLoginScreen(
            onConnected = { cookie ->
                SpotifyFreeAccount.connect(cookie)
                onDone()
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun SpotifyFreeLibraryPage(
    contentPadding: PaddingValues,
    header: @Composable () -> Unit,
    onSearch: (String) -> Unit,
) {
    var playlists by remember { mutableStateOf<List<SpotifyPlaylist>?>(null) }
    var open by remember { mutableStateOf<SpotifyPlaylist?>(null) }
    var tracks by remember { mutableStateOf<List<SpotifyTrack>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val expired = stringResource(R.string.fusion_spotify_expired)
    val network = stringResource(R.string.fusion_spotify_network)
    val protocol = stringResource(R.string.fusion_spotify_protocol)

    fun message(failure: Throwable): String = when (val f = (failure as? SpotifyFreeAccount.FailureException)?.failure) {
        SpotifyFreeAccount.Failure.SessionExpired -> expired
        is SpotifyFreeAccount.Failure.Network -> "$network (${f.detail})"
        is SpotifyFreeAccount.Failure.Protocol -> "$protocol (${f.detail})"
        null -> failure.message.orEmpty()
    }

    LaunchedEffect(Unit) {
        SpotifyFreeAccount.call { SpotifyFreeLibrary.playlists() }
            .onSuccess { playlists = it; error = null }
            .onFailure { error = message(it) }
    }
    LaunchedEffect(open) {
        val playlist = open ?: return@LaunchedEffect
        tracks = null
        SpotifyFreeAccount.call { SpotifyFreeLibrary.tracks(playlist.id) { page -> tracks = page } }
            .onSuccess { tracks = it; error = null }
            .onFailure { error = message(it) }
    }
    androidx.activity.compose.BackHandler(enabled = open != null) { open = null; tracks = null }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item("top") { header() }
        error?.let { item("error") { Section(null) { Note(it) } } }
        val playlist = open
        if (playlist == null) {
            if (playlists == null && error == null) item("loading") { Section(null) { Note(stringResource(R.string.fusion_loading)) } }
            items(playlists.orEmpty(), key = { "p" + it.id }) { item ->
                LibraryRow(item.imageUrl, item.name, item.owner.orEmpty()) { open = item }
            }
        } else {
            item("playlist") { Section(playlist.name) { Note(stringResource(R.string.fusion_spotify_track_note)) } }
            if (tracks == null && error == null) item("loading") { Section(null) { Note(stringResource(R.string.fusion_loading)) } }
            items(tracks.orEmpty(), key = { "t" + it.id }) { track ->
                LibraryRow(track.imageUrl, track.title, track.artist) {
                    onSearch("${track.title} ${track.artist.substringBefore(',')}")
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(imageUrl: String?, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(
            url = imageUrl,
            title = title,
            modifier = Modifier.size(48.dp),
            corner = 8.dp,
            decodeSize = 48.dp,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = InkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ── App › Icon ───────────────────────────────────────────────────────────

@Composable
internal fun AppIconSection() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(runCatching { AppIcon.current(context) }.getOrDefault(AppIcon.Square)) }
    Section(stringResource(R.string.fusion_app_icon)) {
        AppIcon.entries.forEach { icon ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        runCatching { AppIcon.apply(context, icon) }
                            .onSuccess { current = icon }
                    }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val bitmap = remember(icon) {
                    runCatching {
                        context.getDrawable(icon.iconRes)?.toBitmap(96, 96)?.asImageBitmap()
                    }.getOrNull()
                }
                if (bitmap != null) {
                    Image(bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
                }
                Text(
                    icon.label + if (icon == AppIcon.Square) " · " + stringResource(R.string.fusion_default) else "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (current == icon) Ink else InkDim,
                    fontWeight = if (current == icon) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                if (current == icon) {
                    Icon(
                        PhosphorIcons.Regular.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Note(stringResource(R.string.fusion_app_icon_note))
    }
}

// ── App › Performance ────────────────────────────────────────────────────

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
internal fun PerformanceSection(backdrop: dev.lelonio.square.ui.glass.backdrop.Backdrop) {
    if (!Fusion.initialised) return
    val context = LocalContext.current
    val settings = Fusion.settings
    val choose by settings.chooseRefreshRate.collectAsStateWithLifecycle()
    val chosen by settings.refreshRateHz.collectAsStateWithLifecycle()
    @Suppress("DEPRECATION")
    val display = remember(context) {
        val activity = context.activity()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity?.display else activity?.windowManager?.defaultDisplay
    }
    // Read back from the system after each change, never assumed.
    var applied by remember { mutableStateOf(RefreshRates.applied(display)) }
    LaunchedEffect(choose, chosen) {
        TouchRefreshRate.reapply()
        kotlinx.coroutines.delay(600)
        applied = RefreshRates.applied(display)
    }

    Section(stringResource(R.string.fusion_performance)) {
        DownloadSwitch(
            label = stringResource(R.string.fusion_choose_rate),
            checked = choose,
            backdrop = backdrop,
            icon = PhosphorIcons.Regular.BatteryCharging,
            onChange = settings::setChooseRefreshRate,
        )
        if (choose) {
            RefreshRates.OFFERED.forEach { hz ->
                val supported = RefreshRates.isSupported(display, hz)
                ChoiceRow(
                    label = "$hz Hz" + if (!supported) " — " + stringResource(R.string.fusion_rate_unsupported) else "",
                    selected = chosen == hz,
                ) { if (supported) settings.setRefreshRateHz(hz) }
            }
        }
        val supported = RefreshRates.supported(display)
        InfoRow(
            stringResource(R.string.fusion_rates_supported),
            supported.joinToString(" / ") { "$it Hz" }.ifBlank { stringResource(R.string.fusion_unknown) },
        )
        val confirmed = applied
        InfoRow(
            stringResource(R.string.fusion_rate_applied),
            confirmed?.let { "%.0f Hz".format(Locale.ROOT, it) } ?: stringResource(R.string.fusion_unknown),
        )
        if (choose && confirmed != null && kotlin.math.abs(confirmed - chosen) > 1f) {
            Note(stringResource(R.string.fusion_rate_not_confirmed, chosen))
        }
        Note(stringResource(R.string.fusion_performance_note))
    }
}
