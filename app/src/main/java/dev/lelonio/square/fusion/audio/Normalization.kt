package dev.lelonio.square.fusion.audio

import dev.lelonio.square.fusion.FusionLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.pow

/**
 * Loudness normalisation for YouTube Music, from the per-track `loudnessDb`
 * YouTube publishes in its player response.
 *
 * Applied as a level on the song's slot in Square's mixer (MixerSlot.level),
 * at the moment the song is heard, so it composes with crossfades and fades
 * instead of fighting them over the player's volume. Attenuation only, as
 * YouTube's own player does: a quiet song is not boosted, which could clip.
 *
 * Off by default, and while off the level is exactly one: nothing changes.
 * Never applied to a song playing from an addon or JioSaavn — the loudness
 * describes YouTube's file, not theirs — and never to Spotify, whose engine
 * has its own normalisation built in (always on in Square). So no song is ever
 * normalised twice.
 */
object Normalization {

    @Volatile
    var enabled: () -> Boolean = { false }

    /** Fetches `loudnessDb` for a video id, or null. Set by the app to an InnerTube call. */
    @Volatile
    var fetch: (String) -> Double? = { null }

    private val loudness = ConcurrentHashMap<String, Double>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** Video ids currently playing from another source than YouTube. */
    private val substituted = ConcurrentHashMap.newKeySet<String>()

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(String) -> Unit>()

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SquareFusionLoudness").apply { isDaemon = true }
    }

    /** Asks for the loudness of [videoId] in the background, once. */
    fun prefetch(videoId: String) {
        if (!enabled() || videoId.isBlank()) return
        if (loudness.containsKey(videoId) || videoId in missing || !pending.add(videoId)) return
        worker.execute {
            try {
                val db = runCatching { fetch(videoId) }.getOrNull()
                if (db == null || db.isNaN()) missing += videoId else loudness[videoId] = db
                FusionLog.d("loudness $videoId: ${db ?: "unknown"}")
            } finally {
                pending -= videoId
            }
            listeners.forEach { runCatching { it(videoId) } }
        }
    }

    fun markSubstituted(videoId: String, bySource: Boolean) {
        if (bySource) substituted += videoId else substituted -= videoId
    }

    fun isSubstituted(videoId: String) = videoId in substituted

    fun loudnessDb(videoId: String): Double? = loudness[videoId]

    /** The level for [videoId]: one unless normalisation is on and YouTube's own loudness is known. */
    fun levelFor(videoId: String?): Float {
        if (videoId == null || !enabled() || videoId in substituted) return 1f
        val db = loudness[videoId] ?: return 1f
        return levelForDb(db)
    }

    /** `loudnessDb` above the reference is turned down by as much; below it is left alone. */
    fun levelForDb(db: Double): Float =
        if (db <= 0.0) 1f else 10.0.pow(-db / 20.0).toFloat().coerceIn(MIN_LEVEL, 1f)

    fun addListener(listener: (String) -> Unit) = listeners.add(listener)

    /** Never more than -20 dB: a nonsense value cannot silence a song. */
    private const val MIN_LEVEL = 0.1f
}
