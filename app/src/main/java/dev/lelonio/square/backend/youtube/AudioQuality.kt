package dev.lelonio.square.backend.youtube

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Square Fusion: which of a song's audio streams the quality setting picks.
 *
 * What YouTube offers a song, measured on the formats it lists (itags):
 *  - 139: AAC-HE about 48 kbps (not always listed any more)
 *  - 249 / 250: Opus about 50 / 70 kbps
 *  - 140: AAC-LC about 128 kbps
 *  - 251: Opus about 130-160 kbps (variable bitrate)
 *  - 141 (AAC) and 774 (Opus): about 256 kbps, listed only to a signed-in
 *    YouTube Music Premium account, and only to YouTube Music's own client.
 *
 * Opus is decoded by the app's own FFmpeg (media3-ffmpeg-decoder ships the
 * Opus decoder and the player prefers it), not by the phone's.
 *
 * The bitrates are the ones YouTube states for each format: the pick and the
 * label shown for it are what YouTube announced, not a measurement of the file.
 */
object AudioQuality {

    enum class Tier { LOW, MEDIUM, HIGH, MAX }

    /** The stream last chosen, as YouTube described it. */
    data class Choice(
        val tier: Tier,
        val codec: String,
        val kbps: Int,
        val itag: Int?,
        /** Asked for as the signed-in account (the Premium formats, or a song that needs one). */
        val signedIn: Boolean,
    )

    private val _last = MutableStateFlow<Choice?>(null)
    val last: StateFlow<Choice?> = _last.asStateFlow()

    fun record(choice: Choice, videoId: String? = null) {
        _last.value = choice
        if (videoId != null) {
            _stated.value = (_stated.value - videoId).entries.toList().takeLast(KEPT - 1)
                .associate { it.key to it.value } + (videoId to choice)
        }
        android.util.Log.i(TAG, "audio ${choice.tier}: ${choice.codec} ${choice.kbps} kbps itag ${choice.itag}" +
            if (choice.signedIn) " (signed in)" else "")
    }

    /** The tier for a song starting now: automatic is high on an unmetered network, low on a metered one. */
    fun tierNow(context: android.content.Context): Tier {
        val chosen = (context.applicationContext as? dev.lelonio.square.SquareApplication)
            ?.preferences?.youtubeQuality?.value
        return when (chosen) {
            dev.lelonio.square.data.PreferencesStore.YouTubeQuality.LOW -> Tier.LOW
            dev.lelonio.square.data.PreferencesStore.YouTubeQuality.MEDIUM -> Tier.MEDIUM
            dev.lelonio.square.data.PreferencesStore.YouTubeQuality.HIGH -> Tier.HIGH
            dev.lelonio.square.data.PreferencesStore.YouTubeQuality.MAX -> Tier.MAX
            else -> if (context.getSystemService(android.net.ConnectivityManager::class.java)
                    ?.isActiveNetworkMetered == true
            ) Tier.LOW else Tier.HIGH
        }
    }

    /**
     * The stream for [tier] among [streams]:
     *  - LOW: the smallest.
     *  - MEDIUM: the one closest to [MEDIUM_TARGET_KBPS].
     *  - HIGH: the best AAC (what Square has always played as "high"),
     *    or the best stream when there is no AAC.
     *  - MAX: the highest bitrate of all.
     * A tier a song does not have falls to its neighbour; nothing fails for
     * want of a rate. Pure, for tests.
     */
    fun <T> pick(streams: List<T>, tier: Tier, kbps: (T) -> Int, isAac: (T) -> Boolean): T? {
        if (streams.isEmpty()) return null
        return when (tier) {
            Tier.LOW -> streams.sortedWith(compareBy<T> { kbps(it).takeIf { k -> k > 0 } ?: Int.MAX_VALUE }
                .thenByDescending { isAac(it) }).first()
            Tier.MEDIUM -> streams.sortedWith(compareBy<T> { abs(kbps(it) - MEDIUM_TARGET_KBPS) }
                .thenByDescending { isAac(it) }).first()
            Tier.HIGH -> streams.filter(isAac).maxByOrNull(kbps) ?: streams.maxByOrNull(kbps)
            Tier.MAX -> streams.sortedWith(compareByDescending<T> { kbps(it) }
                .thenByDescending { isAac(it) }).first()
        }
    }

    // ── Square Fusion: what the player shows about the song it is playing ──

    private val _stated = MutableStateFlow<Map<String, Choice>>(emptyMap())

    /** The stream chosen for each recent video id, as YouTube described it. */
    val stated: StateFlow<Map<String, Choice>> = _stated.asStateFlow()

    /** The audio as it went into the decoder: read off the stream, not announced by anyone. */
    data class Decoded(val mime: String, val sampleRateHz: Int, val channels: Int)

    private val _decoded = MutableStateFlow<Map<String, Decoded>>(emptyMap())

    /** By queue entry (media id), for the recent ones. */
    val decoded: StateFlow<Map<String, Decoded>> = _decoded.asStateFlow()

    fun decoded(mediaId: String, mime: String?, sampleRateHz: Int, channels: Int) {
        if (mime.isNullOrBlank()) return
        _decoded.value = (_decoded.value - mediaId).entries.toList().takeLast(KEPT - 1)
            .associate { it.key to it.value } + (mediaId to Decoded(mime, sampleRateHz, channels))
    }

    /** A codec's usual name from its MIME type; null for one this does not know. */
    fun codecName(mime: String): String? = when (mime.lowercase()) {
        "audio/mp4a-latm", "audio/aac" -> "AAC"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/mpeg" -> "MP3"
        "audio/flac" -> "FLAC"
        "audio/eac3", "audio/eac3-joc" -> "E-AC-3"
        "audio/ac3" -> "AC-3"
        else -> null
    }

    /** The codec a stated stream is in ("M4A", "WEBMA_OPUS", "audio/mp4"…), as [codecName] names it. */
    fun codecOfStated(choice: Choice): String? {
        val c = choice.codec.lowercase()
        return when {
            "opus" in c || "webm" in c -> "Opus"
            "mp4" in c || "m4a" in c -> "AAC"
            else -> null
        }
    }

    private const val KEPT = 40

    const val MEDIUM_TARGET_KBPS = 80

    /** Above this, a format is one of the Premium ~256 kbps renditions. */
    const val PREMIUM_MIN_KBPS = 200

    private const val TAG = "SquareYTQuality"
}
