// Ported from BitChord (https://github.com/kushagrasinghx/BitChord, GPL-3.0),
// app/src/main/java/com/music/bitchord/data/sources/MusicSource.kt, adapted to
// Square Fusion. Licensed GPL-3.0, see LICENSE and NOTICE.
package dev.lelonio.square.fusion.sources

import java.util.Locale

/**
 * What a source says a stream is. Every field is a *claim* by the source,
 * never a measurement: what the decoder actually received is read separately
 * from ExoPlayer, see [dev.lelonio.square.fusion.audio.StreamInspector].
 *
 * [codecInferred] marks a codec that the source did not state but that was
 * guessed from a quality label ("LOSSLESS") or from the URL's extension, so the
 * screen can say "announced" rather than "stated".
 */
data class StreamFormat(
    val codec: String? = null,
    val kbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val kbpsInferred: Boolean = false,
    val codecInferred: Boolean = false,
) {
    /** Null when the codec is not known: unknown is not "lossy". */
    val isLossless: Boolean?
        get() = codec?.let { it in LOSSLESS_CODECS }

    val isDolbyAtmos: Boolean
        get() = codec in DOLBY_ATMOS_CODECS

    val summary: String
        get() = listOfNotNull(
            codec?.uppercase(Locale.ROOT),
            bitDepth?.let { "$it-bit" },
            sampleRateHz?.let { "${"%.1f".format(Locale.ROOT, it / 1000f).removeSuffix(".0")} kHz" },
            kbps?.takeIf { isLossless != true }?.let { "$it kbps" },
        ).joinToString(" · ").ifEmpty { "?" }

    companion object {
        val LOSSLESS_CODECS = setOf("flac", "alac", "wav", "aiff", "ape", "wv")
        val DOLBY_ATMOS_CODECS = setOf("eac3-joc", "ec3-joc")
    }
}

/**
 * A URL the player can open, and what is expected to come out of it.
 *
 * [headers] travel with the fetch: some sources bind the URL to the request.
 * [transport] is "hls" or "dash" when the URL is a manifest rather than the
 * audio itself; Square's YouTube player plays progressive files only, so such
 * a stream is passed over and the next source (and finally YouTube) is tried.
 */
data class SourceStream(
    val url: String,
    val format: StreamFormat = StreamFormat(),
    val headers: Map<String, String> = emptyMap(),
    val durationSec: Int? = null,
    val transport: String? = null,
    /** Which [MusicSource.configId] answered; set by [SourceResolver]. */
    val sourceConfigId: String? = null,
    val sourceName: String? = null,
    /** Whether the source allows this stream to be saved; see AddonManifest.allowDownloads. */
    val downloadAllowed: Boolean = true,
)

/** How much of the stream the caller is willing to pay for. */
sealed interface StreamRequest {
    /** Bit-exact. Sources that cannot do it say so rather than substitute. */
    data object Lossless : StreamRequest

    /** A transcode at or below [maxKbps]. */
    data class Capped(val maxKbps: Int) : StreamRequest

    /** Whatever the source considers its best lossy rendition. */
    data object Best : StreamRequest
}

/**
 * Whether a source is usable. [Unreachable] (down, slow, rate limiting: worth
 * keeping enabled) is kept apart from [Rejected] (wrong URL, refused: will not
 * fix itself), because the two call for different actions from the user.
 */
sealed interface SourceHealth {
    data class Ok(val detail: String? = null) : SourceHealth
    data class Unreachable(val reason: String) : SourceHealth
    data class Rejected(val reason: String) : SourceHealth

    val isOk: Boolean get() = this is Ok
}

/** One row of a source's catalogue. */
data class SourceTrack(
    /** The source's own id for the track, handed back to [MusicSource.stream]. */
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSec: Int? = null,
    val artworkUrl: String? = null,
    /** "LOSSLESS", "HIGH", "LOW", "DOLBY" or null, as the row advertises it. */
    val tier: String? = null,
    val explicit: Boolean? = null,
)

/**
 * One place tracks can come from. Small on purpose: search a catalogue, turn
 * one of its ids into something openable, and say whether it is reachable.
 * Implementations may throw; [SourceResolver] treats a throw as "this one did
 * not work" and moves on, so one failing source never stops playback.
 */
interface MusicSource {
    val configId: String
    val kind: SourceKind
    val displayName: String

    suspend fun health(): SourceHealth

    suspend fun search(query: String, limit: Int = 10, request: StreamRequest? = null): List<SourceTrack>

    /** Null is a miss (the source does not have it), not an error. */
    suspend fun stream(trackId: String, request: StreamRequest): SourceStream?
}
