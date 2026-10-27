// Inspired by BitChord's TrackMatcher (GPL-3.0), much reduced: Square Fusion
// only needs to decide whether a row from another catalogue is the same
// recording as the YouTube Music track about to play.
package dev.lelonio.square.fusion.sources

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

object TrackMatcher {

    /** The YouTube Music track a source is asked to stand in for. */
    data class Target(
        val title: String,
        val artist: String,
        val durationSec: Int? = null,
    )

    /** What a source is searched for. */
    fun query(target: Target): String =
        listOf(stripDecorations(target.title), primaryArtist(target.artist))
            .filter { it.isNotBlank() }
            .joinToString(" ")

    /**
     * The best row for [target], or null when nothing is close enough to be
     * the same recording. A wrong song is worse than YouTube's own stream, so
     * every test here errs towards "no".
     */
    fun best(candidates: List<SourceTrack>, target: Target): SourceTrack? =
        candidates.mapNotNull { row -> score(row, target)?.let { row to it } }
            .maxByOrNull { it.second }
            ?.first

    /** Higher is better; null is "not the same recording". */
    fun score(row: SourceTrack, target: Target): Int? {
        val wantedTitle = normalize(stripDecorations(target.title))
        val gotTitle = normalize(stripDecorations(row.title))
        if (wantedTitle.isEmpty() || gotTitle.isEmpty()) return null
        val titleScore = when {
            wantedTitle == gotTitle -> 100
            gotTitle.startsWith(wantedTitle) || wantedTitle.startsWith(gotTitle) -> 60
            else -> return null
        }
        // A version marker on one side only (live, remix, acoustic...) is a
        // different recording under the same name.
        if (versionMarkers(target.title) != versionMarkers(row.title)) return null

        val artistScore = if (sharesArtist(target.artist, row.artist)) 50 else return null

        val durationScore = when {
            target.durationSec == null || row.durationSec == null -> 0
            abs(target.durationSec - row.durationSec) <= 3 -> 40
            abs(target.durationSec - row.durationSec) <= DURATION_TOLERANCE_SEC -> 10
            else -> return null
        }
        return titleScore + artistScore + durationScore
    }

    fun sharesArtist(wanted: String, got: String): Boolean {
        val a = artistNames(wanted)
        val b = artistNames(got)
        if (a.isEmpty() || b.isEmpty()) return false
        return a.any { x -> b.any { y -> x == y || x.contains(y) || y.contains(x) } }
    }

    private fun artistNames(value: String): List<String> =
        value.split(ARTIST_SEPARATORS)
            .map { normalize(it) }
            .filter { it.length >= 2 }

    fun primaryArtist(artist: String): String = artist.split(ARTIST_SEPARATORS).firstOrNull()?.trim().orEmpty()

    /** "Song (Official Video) [HD]" → "Song"; keeps version markers out of the comparison. */
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

    private const val DURATION_TOLERANCE_SEC = 8

    private val ARTIST_SEPARATORS = Regex("\\s*(,|&|\\bx\\b|\\bfeat\\.?|\\bft\\.?|\\band\\b|;|/)\\s*", RegexOption.IGNORE_CASE)
    private val BRACKETS = Regex("[(\\[][^)\\]]*(official|video|audio|lyric|visualizer|hd|4k|explicit|clean|remaster(ed)?)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)
    private val FEAT = Regex("[(\\[]?\\s*(feat\\.?|ft\\.?|featuring)\\s[^)\\]]*[)\\]]?", RegexOption.IGNORE_CASE)
    private val VERSION_WORDS = listOf("live", "remix", "acoustic", "instrumental", "karaoke", "sped up", "slowed", "nightcore", "demo", "edit")
}
