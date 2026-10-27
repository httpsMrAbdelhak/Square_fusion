// Ported from BitChord (https://github.com/kushagrasinghx/BitChord, GPL-3.0),
// app/src/main/java/com/music/bitchord/data/sources/AddonSource.kt, adapted to
// Square Fusion's SourceTrack/SourceStream models.
package dev.lelonio.square.fusion.sources.addon

import dev.lelonio.square.fusion.FusionLog
import dev.lelonio.square.fusion.sources.MusicSource
import dev.lelonio.square.fusion.sources.SourceConfig
import dev.lelonio.square.fusion.sources.SourceHealth
import dev.lelonio.square.fusion.sources.SourceKind
import dev.lelonio.square.fusion.sources.SourceRegistry
import dev.lelonio.square.fusion.sources.SourceStream
import dev.lelonio.square.fusion.sources.SourceTrack
import dev.lelonio.square.fusion.sources.StreamFormat
import dev.lelonio.square.fusion.sources.StreamRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Collections
import java.util.Locale

/**
 * An addon server as a [MusicSource].
 *
 * `checkValidLossless` is enforced here: an addon that declares it is not asked
 * anything while [losslessOutput] says no lossless-capable output is connected.
 * `allowDownloads` is carried on every stream so a download can refuse it.
 */
class AddonSource(
    val config: SourceConfig,
    private val registry: SourceRegistry? = null,
    private val client: AddonClient = AddonClient(config.baseUrl, atmosWanted = { AudioGates.atmosDecoder() }),
    private val losslessOutput: () -> Boolean = { AudioGates.losslessOutput() },
    private val atmosPlayable: () -> Boolean = { AudioGates.atmosDecoder() },
) : MusicSource {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.ADDON
    override val displayName: String get() = config.displayName

    /** Search rows, so a row carrying its own `streamURL` can be played without `/stream`. */
    private val rows = Collections.synchronizedMap(
        object : LinkedHashMap<String, AddonTrack>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, AddonTrack>) = size > MAX_ROWS
        },
    )

    @Volatile
    private var manifestPolicy: Pair<Boolean, Boolean>? = null

    private val requiresLosslessOutput: Boolean
        get() = manifestPolicy?.second ?: (registry?.config(config.id) ?: config).requiresLosslessOutput

    private val downloadsAllowed: Boolean
        get() = manifestPolicy?.first ?: (registry?.config(config.id) ?: config).allowDownloads

    private fun shutOut(what: String): Boolean {
        if (!requiresLosslessOutput || losslessOutput()) return false
        FusionLog.d("${config.displayName}: no lossless output connected; not asking it for $what")
        return true
    }

    private suspend fun admitted(what: String): Boolean {
        if (shutOut(what)) return false
        client.manifest().getOrNull()?.let(::recordPolicy)
        return !shutOut(what)
    }

    private fun recordPolicy(manifest: AddonManifest) {
        manifestPolicy = manifest.downloadsAllowed to manifest.requiresLosslessOutput
        registry?.updatePolicy(config.id, manifest.downloadsAllowed, manifest.requiresLosslessOutput)
    }

    override suspend fun health(): SourceHealth = withContext(Dispatchers.IO) {
        if (config.baseUrl.toHttpUrlOrNull() == null) {
            return@withContext SourceHealth.Rejected("A valid http(s) addon URL is required")
        }
        client.manifest().fold(
            onSuccess = { manifest ->
                recordPolicy(manifest)
                if (shutOut("a health check")) return@fold SourceHealth.Rejected(NEEDS_LOSSLESS_OUTPUT)
                SourceHealth.Ok(
                    listOfNotNull(
                        manifest.displayName.takeIf { it.isNotBlank() },
                        manifest.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                    ).joinToString(" ").ifBlank { null },
                )
            },
            onFailure = { failure ->
                if (client.probeSearch().isSuccess) {
                    return@fold SourceHealth.Ok("No manifest · search works")
                }
                val reason = failure.message ?: "Could not reach the addon"
                if (failure is AddonException) SourceHealth.Rejected(reason) else SourceHealth.Unreachable(reason)
            },
        )
    }

    override suspend fun search(query: String, limit: Int, request: StreamRequest?): List<SourceTrack> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            if (!admitted("a search")) return@withContext emptyList()
            val tier = request?.tier ?: AddonClient.TIER_LOSSLESS
            val tracks = client.search(query, tier).getOrElse { failure ->
                FusionLog.w("${config.displayName}: search failed — ${failure.message}")
                return@withContext emptyList()
            }
            tracks.asSequence()
                .filter { it.id.isNotBlank() && it.title.isNotBlank() }
                .take(limit)
                .map { track ->
                    rows[track.id] = track
                    SourceTrack(
                        id = track.id,
                        title = track.title,
                        artist = track.artist,
                        album = track.album.ifBlank { null },
                        durationSec = track.durationSec,
                        artworkUrl = track.artwork,
                        tier = if (track.isDolbyAtmos) DOLBY else qualityTier("${track.audioQuality} ${track.format}"),
                    )
                }
                .toList()
        }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
        withContext(Dispatchers.IO) {
            if (!admitted("a stream")) return@withContext null
            val tier = request.tier
            val result = client.stream(trackId, tier)
            val answer = result.getOrNull()
            if (answer == null) {
                val failure = result.exceptionOrNull()
                if (failure !is AddonNotFound) {
                    FusionLog.w("${config.displayName}: stream failed — ${failure?.message}")
                }
                return@withContext fromRow(trackId, tier)
            }
            val url = answer.url.ifBlank { null } ?: run {
                FusionLog.w("${config.displayName}: no stream" + (answer.error?.ifBlank { null }?.let { " — ${it.take(200)}" } ?: ""))
                return@withContext fromRow(trackId, tier)
            }
            openable(url, answer, trackId, tier)
        }

    private fun fromRow(trackId: String, tier: String): SourceStream? {
        val row = rows[trackId] ?: return null
        val url = row.streamURL?.ifBlank { null } ?: return null
        return openable(url, AddonStream(url = url, format = row.format), trackId, tier)
    }

    private fun openable(url: String, answer: AddonStream, trackId: String, tier: String): SourceStream? {
        if (malformed(url)) {
            FusionLog.w("${config.displayName}: malformed or non-http(s) URL; skipping it")
            return null
        }
        if (answer.isEncrypted) {
            FusionLog.w("${config.displayName}: stream is DRM-encrypted, which is never requested — passing")
            return null
        }
        val format = formatOf(answer, url, tier)
        if (format.isDolbyAtmos && !atmosPlayable()) {
            FusionLog.w("${config.displayName}: answered with Dolby Atmos, which this device cannot decode — passing")
            return null
        }
        return SourceStream(
            url = url,
            format = format,
            durationSec = rows[trackId]?.durationSec,
            transport = answer.transport,
            downloadAllowed = downloadsAllowed,
        )
    }

    /**
     * What the addon says this stream is. A codec the addon did not state but
     * that is guessed from a label or an extension is marked [StreamFormat.codecInferred].
     */
    internal fun formatOf(answer: AddonStream, url: String, tier: String): StreamFormat {
        // `format` is the spec's own field for what the audio is ("flac", "mp3").
        val stated = answer.statedCodec?.takeIf { it in AUDIO_CODECS }
            ?: answer.format.trim().lowercase(Locale.ROOT).takeIf { it in AUDIO_CODECS }
            ?: answer.statedContainer?.takeIf { it in SELF_DESCRIBING_CONTAINERS }
            ?: answer.mimeType?.substringAfterLast('/')?.substringBefore(';')?.trim()
                ?.lowercase(Locale.ROOT)?.let { if (it == "mpeg") "mp3" else it }?.takeIf { it in AUDIO_CODECS }
        val inferred = if (stated != null) {
            null
        } else if (answer.isDolbyAtmos) {
            "eac3-joc"
        } else if (qualityTier(answer.qualityText) == LOSSLESS) {
            "flac"
        } else {
            url.substringBefore('?').substringAfterLast('.')
                .lowercase(Locale.ROOT).takeIf { it in AUDIO_CODECS }
        }
        val tierKbps = when (tier) {
            AddonClient.TIER_HIGH -> 320
            AddonClient.TIER_LOW -> 128
            else -> null
        }
        return StreamFormat(
            codec = stated ?: inferred,
            kbps = answer.kbps ?: tierKbps,
            sampleRateHz = answer.sampleRateHz,
            bitDepth = answer.bits,
            kbpsInferred = answer.kbps == null && tierKbps != null,
            codecInferred = stated == null && inferred != null,
        )
    }

    fun release() {
        rows.clear()
        client.clear()
    }

    companion object {
        const val NEEDS_LOSSLESS_OUTPUT =
            "This addon only works with a lossless-capable output (wired, USB, HDMI or LDAC/LHDC Bluetooth)"

        const val LOSSLESS = "LOSSLESS"
        const val HIGH = "HIGH"
        const val LOW = "LOW"
        const val DOLBY = "DOLBY"

        /** At or under this cap, the addon is asked for its LOW tier. */
        const val LOW_CEILING_KBPS = 128

        private const val MAX_ROWS = 256

        private val SELF_DESCRIBING_CONTAINERS = setOf("flac", "wav", "mp3", "aiff")

        private val AUDIO_CODECS = setOf(
            "flac", "alac", "wav", "aiff", "mp3", "aac", "he-aac", "m4a", "mp4",
            "ogg", "opus", "vorbis", "webm", "eac3-joc", "ec3-joc",
        )

        private val LOSSLESS_HINTS =
            listOf("LOSSLESS", "FLAC", "ALAC", "HI-RES", "HI_RES", "HIRES", "24-BIT", "16-BIT", "WAV")
        private val LOW_HINTS = listOf("LOW", "128", "96KBPS", "64")
        private val HIGH_HINTS = listOf("HIGH", "320", "MP3", "AAC", "M4A", "OPUS", "OGG")

        fun qualityTier(label: String): String? {
            val text = label.uppercase(Locale.ROOT)
            return when {
                text.isBlank() -> null
                LOSSLESS_HINTS.any { it in text } -> LOSSLESS
                LOW_HINTS.any { it in text } -> LOW
                HIGH_HINTS.any { it in text } -> HIGH
                else -> null
            }
        }

        val StreamRequest.tier: String
            get() = when (this) {
                is StreamRequest.Lossless -> AddonClient.TIER_LOSSLESS
                is StreamRequest.Best -> AddonClient.TIER_HIGH
                is StreamRequest.Capped ->
                    if (maxKbps <= LOW_CEILING_KBPS) AddonClient.TIER_LOW else AddonClient.TIER_HIGH
            }

        /**
         * Not an http(s) URL, or one that repeats its own origin (a base URL
         * concatenated twice, a bug seen in the wild).
         */
        fun malformed(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return true
            if (parsed.scheme != "https" && parsed.scheme != "http") return true
            val schemeEnd = url.indexOf("://")
            val originEnd = url.indexOf('/', schemeEnd + 3)
            if (originEnd < 0) return false
            return url.indexOf(url.substring(0, originEnd), originEnd) >= 0
        }
    }
}

/**
 * What the phone can do, as read by [dev.lelonio.square.fusion.audio.LosslessOutput]
 * and [dev.lelonio.square.fusion.audio.DeviceCodecs]. Hooks rather than direct
 * calls so JVM tests need neither the audio service nor the codec list. Both
 * answer "no" until the app has measured.
 */
object AudioGates {
    /** A wired, USB, HDMI or LDAC/LHDC output is connected. */
    @Volatile
    var losslessOutput: () -> Boolean = { false }

    /** A decoder for E-AC-3 JOC (Dolby Atmos) exists on this phone. */
    @Volatile
    var atmosDecoder: () -> Boolean = { false }
}
