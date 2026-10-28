package dev.lelonio.square.fusion.spotify

import android.content.Context
import android.content.SharedPreferences
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import dev.lelonio.square.fusion.FusionLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Spotify songs played through YouTube Music.
 *
 * A Spotify song is queued as `ytmusic:track:sp-<spotify id>` (see [uriFor]):
 * a YouTube Music track URI whose id is a placeholder. The player resolves it
 * when it loads, through [videoIdBlocking], which searches YouTube Music for
 * the same recording and remembers the answer on the phone; the queue entry
 * is then renamed to the real song (YouTubePlayerFactory), as Square already
 * does for a music video found to have a song.
 *
 * The match errs towards "not found" rather than a wrong song: a live, remix,
 * acoustic, instrumental, sped-up or slowed version on one side only is not
 * the same recording, and neither is one whose length differs by more than
 * [DURATION_REJECT_SEC] seconds.
 */
object SpotifyMatches {

    const val ID_PREFIX = "sp-"
    private const val TRACK_PREFIX = "ytmusic:track:"

    /** What a Spotify song is, as far as matching it needs. */
    data class Target(
        val title: String,
        val artist: String,
        val album: String? = null,
        val durationSec: Int? = null,
    )

    /** Thrown when no YouTube Music song is a close enough match. */
    class NotFound(spotifyId: String) : java.io.IOException("$spotifyId: no matching song on YouTube Music")

    private var prefs: SharedPreferences? = null
    private val targets = ConcurrentHashMap<String, Target>()
    private val matched = ConcurrentHashMap<String, String>()

    /** Recently not found, by id and when: asked again only after [MISS_TTL_MS], so a retry works. */
    private val misses = ConcurrentHashMap<String, Long>()

    private val _resolved = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)

    /** (placeholder URI, real URI), as each song is found. */
    val resolved: SharedFlow<Pair<String, String>> = _resolved.asSharedFlow()

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun uriFor(spotifyId: String) = "$TRACK_PREFIX$ID_PREFIX$spotifyId"

    fun isPlaceholder(videoId: String) = videoId.startsWith(ID_PREFIX)

    /** Remembers what [spotifyId] is, so it can be matched later, after a restart too. */
    fun register(spotifyId: String, target: Target) {
        targets[spotifyId] = target
        prefs?.edit()?.putString(KEY_TARGET + spotifyId, encode(target))?.apply()
    }

    fun known(spotifyId: String): String? =
        matched[spotifyId] ?: prefs?.getString(KEY_MATCH + spotifyId, null)?.also { matched[spotifyId] = it }

    private fun targetOf(spotifyId: String): Target? =
        targets[spotifyId] ?: prefs?.getString(KEY_TARGET + spotifyId, null)?.let(::decode)?.also { targets[spotifyId] = it }

    /**
     * The YouTube video id for a placeholder id (`sp-…`), searching YouTube
     * Music when it is not known yet. Blocking: the player's loading thread.
     * @throws NotFound when nothing is close enough.
     */
    fun videoIdBlocking(placeholderId: String): String {
        val spotifyId = placeholderId.removePrefix(ID_PREFIX)
        known(spotifyId)?.let { return it }
        misses[spotifyId]?.takeIf { System.currentTimeMillis() - it < MISS_TTL_MS }?.let { throw NotFound(spotifyId) }
        val target = targetOf(spotifyId) ?: throw NotFound(spotifyId)
        val found = runBlocking { search(target) }
        if (found == null) {
            misses[spotifyId] = System.currentTimeMillis()
            FusionLog.i("match $spotifyId \"${target.title}\" by ${target.artist}: none close enough")
            throw NotFound(spotifyId)
        }
        matched[spotifyId] = found.id
        prefs?.edit()?.putString(KEY_MATCH + spotifyId, found.id)?.apply()
        FusionLog.i("match $spotifyId \"${target.title}\" by ${target.artist} -> ${found.id} \"${found.title}\"")
        _resolved.tryEmit(uriFor(spotifyId) to "$TRACK_PREFIX${found.id}")
        return found.id
    }

    private suspend fun search(target: Target): SongItem? {
        val queries = listOf(
            "${stripDecorations(target.title)} ${primaryArtist(target.artist)}",
            stripDecorations(target.title),
        ).distinct().filter { it.isNotBlank() }
        var answered = false
        var failure: Throwable? = null
        for (query in queries) {
            val result = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
            result.exceptionOrNull()?.let { failure = it }
            val songs = result.getOrNull()?.also { answered = true }
                ?.items.orEmpty().filterIsInstance<SongItem>()
            best(songs.map { Candidate(it.title, it.artists.joinToString(", ") { a -> a.name }, it.album?.name, it.duration) }, target)
                ?.let { return songs[it] }
        }
        // No answer at all is the network, not a song missing from YouTube Music.
        if (!answered) throw java.io.IOException("YouTube Music search failed: ${failure?.message}", failure)
        return null
    }

    /** A YouTube Music row, as far as matching reads it. */
    data class Candidate(val title: String, val artist: String, val album: String?, val durationSec: Int?)

    /** The index of the best candidate for [target], or null. Pure, for tests. */
    fun best(candidates: List<Candidate>, target: Target): Int? =
        candidates.withIndex().mapNotNull { (i, c) -> score(c, target)?.let { i to it } }.maxByOrNull { it.second }?.first

    /** Higher is better; null is "not the same recording". */
    fun score(candidate: Candidate, target: Target): Int? {
        val wanted = normalize(stripDecorations(target.title))
        val got = normalize(stripDecorations(candidate.title))
        if (wanted.isEmpty() || got.isEmpty()) return null
        val titleScore = when {
            wanted == got -> 100
            got.startsWith(wanted) || wanted.startsWith(got) -> 60
            else -> return null
        }
        if (versionMarkers(target.title) != versionMarkers(candidate.title)) return null
        if (!sharesArtist(target.artist, candidate.artist)) return null
        val durationScore = when {
            target.durationSec == null || candidate.durationSec == null -> 0
            abs(target.durationSec - candidate.durationSec) <= 3 -> 40
            abs(target.durationSec - candidate.durationSec) <= 8 -> 15
            abs(target.durationSec - candidate.durationSec) <= DURATION_REJECT_SEC -> 0
            else -> return null
        }
        val albumScore = if (target.album != null && candidate.album != null &&
            normalize(target.album) == normalize(candidate.album)
        ) 10 else 0
        return titleScore + 50 + durationScore + albumScore
    }

    fun sharesArtist(wanted: String, got: String): Boolean {
        val a = artistNames(wanted)
        val b = artistNames(got)
        if (a.isEmpty() || b.isEmpty()) return false
        return a.any { x -> b.any { y -> x == y || x.contains(y) || y.contains(x) } }
    }

    private fun artistNames(value: String): List<String> =
        value.split(ARTIST_SEPARATORS).map { normalize(it) }.filter { it.length >= 2 }

    private fun primaryArtist(artist: String): String = artist.split(ARTIST_SEPARATORS).firstOrNull()?.trim().orEmpty()

    /** "Song (feat. X) [Official Video]" -> "Song"; version words are kept for [versionMarkers]. */
    fun stripDecorations(title: String): String =
        title.replace(BRACKETS, " ").replace(FEAT, " ").replace(Regex("\\s+"), " ").trim()

    private fun versionMarkers(title: String): Set<String> {
        val text = title.lowercase(Locale.ROOT)
        return VERSION_WORDS.filter { Regex("\\b$it\\b").containsMatchIn(text) }.toSet()
    }

    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private fun encode(t: Target) = listOf(t.title, t.artist, t.album.orEmpty(), t.durationSec?.toString().orEmpty()).joinToString(SEP)

    private fun decode(s: String): Target? {
        val parts = s.split(SEP)
        if (parts.size < 4) return null
        return Target(parts[0], parts[1], parts[2].ifBlank { null }, parts[3].toIntOrNull())
    }

    private const val FILE = "square_fusion_spotify_matches"
    private const val KEY_MATCH = "m:"
    private const val KEY_TARGET = "t:"
    private const val SEP = "\u001F"
    private const val MISS_TTL_MS = 2 * 60 * 1000L
    private const val DURATION_REJECT_SEC = 15

    private val ARTIST_SEPARATORS = Regex("\\s*(,|&|\\bx\\b|\\bfeat\\.?|\\bft\\.?|\\band\\b|\\bet\\b|;|/)\\s*", RegexOption.IGNORE_CASE)
    private val BRACKETS = Regex(
        "[(\\[][^)\\]]*(official|video|audio|lyric|visualizer|hd|4k|explicit|clean|remaster(ed)?|feat|ft\\.)[^)\\]]*[)\\]]",
        RegexOption.IGNORE_CASE,
    )
    private val FEAT = Regex("\\s[-–]?\\s*(feat\\.?|ft\\.?|featuring)\\s.*$", RegexOption.IGNORE_CASE)
    private val VERSION_WORDS = listOf(
        "live", "remix", "acoustic", "instrumental", "karaoke", "sped up", "slowed", "nightcore", "demo", "cover", "8d",
    )
}
