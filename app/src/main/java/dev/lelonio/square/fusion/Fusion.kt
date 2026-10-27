package dev.lelonio.square.fusion

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.lelonio.square.fusion.audio.DeviceCodecs
import dev.lelonio.square.fusion.audio.LosslessOutput
import dev.lelonio.square.fusion.audio.Normalization
import dev.lelonio.square.fusion.audio.StreamInspector
import dev.lelonio.square.fusion.sources.SourceRegistry
import dev.lelonio.square.fusion.sources.SourceResolver
import dev.lelonio.square.fusion.sources.SourceStorage
import dev.lelonio.square.fusion.sources.SourceStream
import dev.lelonio.square.fusion.sources.StreamRequest
import dev.lelonio.square.fusion.sources.TrackMatcher
import dev.lelonio.square.fusion.sources.addon.AudioGates
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Square Fusion's additions, in one place, set up once by SquareApplication.
 *
 * Everything Square's own code calls into goes through here, and every entry
 * point either does nothing (no sources configured: the default) or catches
 * its own failures, so Square's playback paths keep behaving as they did.
 */
object Fusion {

    @Volatile
    private var app: Context? = null

    lateinit var settings: FusionSettings
        private set

    lateinit var registry: SourceRegistry
        private set

    lateinit var resolver: SourceResolver
        private set

    /** The last decision per song: when, what, and with which source list. */
    private val decided = ConcurrentHashMap<String, Triple<Long, SourceStream?, List<dev.lelonio.square.fusion.sources.SourceConfig>>>()

    /** What the queue said about each item (title, artist, length), by media id. */
    private val hints = ConcurrentHashMap<String, TrackMatcher.Target>()

    val initialised: Boolean get() = app != null

    @Synchronized
    fun init(context: Context) {
        if (app != null) return
        val appContext = context.applicationContext
        app = appContext
        settings = FusionSettings(appContext)
        val prefs = appContext.getSharedPreferences("square_fusion_sources", Context.MODE_PRIVATE)
        registry = SourceRegistry(object : SourceStorage {
            override fun read(): String? = prefs.getString("sources", null)
            override fun write(value: String) { prefs.edit().putString("sources", value).apply() }
        })
        resolver = SourceResolver({ registry.enabledSources() })
        runCatching { LosslessOutput.init(appContext) }
        AudioGates.losslessOutput = { LosslessOutput.capable }
        AudioGates.atmosDecoder = { DeviceCodecs.playsDolbyAtmos }
        Normalization.enabled = { settings.normalization.value }
        Normalization.fetch = ::youtubeLoudness
        runCatching { dev.lelonio.square.fusion.spotify.SpotifyFreeAccount.init(appContext) }
            .onFailure { FusionLog.w("Spotify library unavailable: ${it.message}") }
    }

    /** Called by the player as items are queued. */
    fun hint(mediaId: String, title: String?, artist: String?, durationMs: Long?) {
        if (title.isNullOrBlank()) return
        hints[mediaId] = TrackMatcher.Target(
            title = title,
            artist = artist.orEmpty().removeSuffix(" - Topic"),
            durationSec = durationMs?.takeIf { it > 0 }?.let { (it / 1000).toInt() },
        )
        if (hints.size > 512) hints.keys.firstOrNull()?.let { hints.remove(it) }
    }

    fun hintFor(mediaId: String): TrackMatcher.Target? = hints[mediaId]

    /** The request for a song starting now, from the connection in hand. */
    fun requestNow(): StreamRequest {
        val tier = if (onMeteredNetwork()) settings.mobileTier.value else settings.wifiTier.value
        return requestFor(tier)
    }

    fun requestFor(tier: SourceTier): StreamRequest = when (tier) {
        SourceTier.LOSSLESS -> StreamRequest.Lossless
        SourceTier.HIGH -> StreamRequest.Best
        SourceTier.MEDIUM -> StreamRequest.Capped(160)
        SourceTier.LOW -> StreamRequest.Capped(128)
    }

    fun onMeteredNetwork(): Boolean {
        val context = app ?: return false
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * A stream from the user's sources for the YouTube Music song [mediaId],
     * or null to play it from YouTube as Square always has. Blocking: runs on
     * ExoPlayer's loading thread. Never throws.
     */
    fun substituteBlocking(mediaId: String, videoId: String): SourceStream? {
        if (!initialised || !registry.hasEnabledSources()) return null
        val target = hints[mediaId] ?: return null
        // ExoPlayer opens the stream again on every seek and retry: the answer
        // (a stream, or "YouTube") is kept for the song a while, so a seek
        // neither searches again nor switches source halfway through.
        val now = System.currentTimeMillis()
        decided[mediaId]?.takeIf { now - it.first < DECISION_TTL_MS && it.third == registry.configs.value }
            ?.let { return it.second }
        return try {
            val outcome = runBlocking {
                withTimeoutOrNull(TOTAL_BUDGET_MS) { resolver.resolve(target, requestNow()) }
            }
            val stream = outcome?.stream
            Normalization.markSubstituted(videoId, stream != null)
            StreamInspector.announce(
                mediaId,
                if (stream != null) {
                    StreamInspector.Announced(stream.sourceName ?: "Source", stream.format)
                } else {
                    StreamInspector.Announced(YOUTUBE, null)
                },
            )
            FusionLog.d("substitute $videoId: ${outcome?.tried?.joinToString(" / ") ?: "timed out"}")
            decided[mediaId] = Triple(now, stream, registry.configs.value)
            if (decided.size > 256) decided.entries.minByOrNull { it.value.first }?.let { decided.remove(it.key) }
            stream
        } catch (t: Throwable) {
            FusionLog.w("substitute failed for $videoId: ${t.message}")
            null
        }
    }

    /**
     * A lossless stream for a download, from a source that states a lossless
     * codec *and* allows downloads; null otherwise, and the download takes
     * YouTube's file as in Square. Only when the Wi-Fi tier is Lossless.
     */
    fun losslessDownloadBlocking(target: TrackMatcher.Target): SourceStream? {
        if (!initialised || !registry.hasEnabledSources()) return null
        if (settings.wifiTier.value != SourceTier.LOSSLESS) return null
        return try {
            runBlocking {
                withTimeoutOrNull(TOTAL_BUDGET_MS) {
                    resolver.resolve(target, StreamRequest.Lossless, forDownload = true)
                }
            }?.stream
        } catch (t: Throwable) {
            FusionLog.w("lossless download lookup failed: ${t.message}")
            null
        }
    }

    /** `loudnessDb` from YouTube Music's player response, anonymously. */
    private fun youtubeLoudness(videoId: String): Double? = runBlocking {
        com.metrolist.innertube.YouTube.player(
            videoId,
            client = com.metrolist.innertube.models.YouTubeClient.WEB_REMIX,
        ).getOrNull()?.let { response ->
            response.playerConfig?.audioConfig?.loudnessDb
                ?: response.streamingData?.adaptiveFormats?.firstOrNull { it.isAudio && it.loudnessDb != null }?.loudnessDb
        }
    }

    const val YOUTUBE = "YouTube Music"

    /** Within an addon's stream URL lifetime (the spec assumes an hour). */
    private const val DECISION_TTL_MS = 20 * 60 * 1000L

    /** How long a song waits on the sources before YouTube plays it. */
    private const val TOTAL_BUDGET_MS = 12_000L
}
