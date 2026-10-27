package dev.lelonio.square.fusion

import dev.lelonio.square.backend.youtube.AudioQuality
import dev.lelonio.square.fusion.spotify.SpotifyLinks
import dev.lelonio.square.fusion.spotify.SpotifyMatches
import dev.lelonio.square.fusion.spotify.SpotifyPlaylistUnavailable
import dev.lelonio.square.fusion.spotify.parseArtistPage
import dev.lelonio.square.fusion.spotify.parsePlaylistInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Square Fusion's pure logic: stream tiers, Spotify-to-YouTube matching, links, parsing. */
class FusionLogicTest {

    // ── Quality tiers ───────────────────────────────────────────────────

    /** (itag, kbps, AAC) as YouTube lists a typical song. */
    private val typical = listOf(
        Triple(139, 48, true),
        Triple(140, 128, true),
        Triple(249, 50, false),
        Triple(250, 70, false),
        Triple(251, 141, false),
    )

    private fun pick(streams: List<Triple<Int, Int, Boolean>>, tier: AudioQuality.Tier) =
        AudioQuality.pick(streams, tier, { it.second }, { it.third })?.first

    @Test
    fun eachTierPicksADifferentStream() {
        assertEquals(139, pick(typical, AudioQuality.Tier.LOW))
        assertEquals(250, pick(typical, AudioQuality.Tier.MEDIUM))
        assertEquals(140, pick(typical, AudioQuality.Tier.HIGH))
        assertEquals(251, pick(typical, AudioQuality.Tier.MAX))
    }

    @Test
    fun premiumFormatsWinTheMaximum() {
        val premium = typical + Triple(141, 256, true) + Triple(774, 256, false)
        assertEquals(141, pick(premium, AudioQuality.Tier.MAX))
        // High stays Square's AAC choice, now the best AAC on offer.
        assertEquals(141, pick(premium, AudioQuality.Tier.HIGH))
    }

    @Test
    fun missingRatesFallToANeighbour() {
        val aacOnly = listOf(Triple(140, 128, true))
        AudioQuality.Tier.entries.forEach { assertEquals(140, pick(aacOnly, it)) }
        val opusOnly = listOf(Triple(249, 50, false), Triple(251, 141, false))
        assertEquals(251, pick(opusOnly, AudioQuality.Tier.HIGH))
        assertEquals(249, pick(opusOnly, AudioQuality.Tier.LOW))
        assertNull(pick(emptyList(), AudioQuality.Tier.MAX))
    }

    // ── Spotify to YouTube Music matching ───────────────────────────────

    private val target = SpotifyMatches.Target("Blinding Lights", "The Weeknd", "After Hours", 200)

    private fun c(title: String, artist: String = "The Weeknd", album: String? = "After Hours", seconds: Int? = 201) =
        SpotifyMatches.Candidate(title, artist, album, seconds)

    @Test
    fun matcherTakesTheSameRecordingOnly() {
        assertNotNull(SpotifyMatches.score(c("Blinding Lights"), target))
        assertNotNull(SpotifyMatches.score(c("Blinding Lights (Official Audio)"), target))
        assertNull(SpotifyMatches.score(c("Blinding Lights (Live)"), target))
        assertNull(SpotifyMatches.score(c("Blinding Lights - Remix"), target))
        assertNull(SpotifyMatches.score(c("Blinding Lights", artist = "Someone Else"), target))
        assertNull(SpotifyMatches.score(c("Blinding Lights", seconds = 260), target))
        assertNull(SpotifyMatches.score(c("Save Your Tears"), target))
    }

    @Test
    fun bestPrefersTheOriginalOverTheCover() {
        val rows = listOf(
            c("Blinding Lights (Cover)", artist = "The Weeknd"),
            c("Blinding Lights", seconds = 215),
            c("Blinding Lights"),
        )
        assertEquals(2, SpotifyMatches.best(rows, target))
        assertNull(SpotifyMatches.best(listOf(c("Other")), target))
    }

    @Test
    fun featuredArtistsAndAccentsStillMatch() {
        val feat = SpotifyMatches.Target("Señorita", "Shawn Mendes, Camila Cabello", null, 191)
        assertNotNull(SpotifyMatches.score(SpotifyMatches.Candidate("Senorita", "Shawn Mendes & Camila Cabello", null, 191), feat))
        assertNotNull(SpotifyMatches.score(SpotifyMatches.Candidate("Señorita (feat. Camila Cabello)", "Shawn Mendes", null, 190), feat))
    }

    @Test
    fun placeholdersAreRecognised() {
        assertEquals("ytmusic:track:sp-4cOdK2wGLETKBW3PvgPWqT", SpotifyMatches.uriFor("4cOdK2wGLETKBW3PvgPWqT"))
        assertEquals(true, SpotifyMatches.isPlaceholder("sp-4cOdK2wGLETKBW3PvgPWqT"))
        assertEquals(false, SpotifyMatches.isPlaceholder("dQw4w9WgXcQ"))
    }

    // ── Links ───────────────────────────────────────────────────────────

    @Test
    fun playlistLinksInEveryForm() {
        val id = "37i9dQZF1DXcBWIGoYBM5M"
        assertEquals(id, SpotifyLinks.playlistId("https://open.spotify.com/playlist/$id?si=abc"))
        assertEquals(id, SpotifyLinks.playlistId("  https://open.spotify.com/intl-fr/playlist/$id  "))
        assertEquals(id, SpotifyLinks.playlistId("spotify:playlist:$id"))
        assertNull(SpotifyLinks.playlistId("https://open.spotify.com/album/$id"))
        assertNull(SpotifyLinks.playlistId("https://example.com/playlist/short"))
        assertEquals("track" to id, SpotifyLinks.parse("https://open.spotify.com/track/$id"))
        assertEquals("album" to id, SpotifyLinks.parse("spotify:album:$id"))
    }

    // ── Spotify answers ─────────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun playlistInfoAndUnavailablePlaylists() {
        val ok = json.parseToJsonElement(
            """{"data":{"playlistV2":{"__typename":"Playlist","name":"Road","ownerV2":{"data":{"name":"Amit"}},
               "images":{"items":[{"sources":[{"url":"https://i/x","width":300},{"url":"https://i/y","width":640}]}]}}}}""",
        ).jsonObject
        val info = parsePlaylistInfo("abc", ok)
        assertEquals("Road", info.name)
        assertEquals("Amit", info.owner)
        assertEquals("https://i/y", info.imageUrl)

        val missing = json.parseToJsonElement("""{"data":{"playlistV2":{"__typename":"NotFound"}}}""").jsonObject
        try {
            parsePlaylistInfo("abc", missing)
            throw AssertionError("expected unavailable")
        } catch (_: SpotifyPlaylistUnavailable) {
        }
    }

    @Test
    fun followedArtistsAreRead() {
        val root = json.parseToJsonElement(
            """{"data":{"me":{"libraryV3":{"totalCount":2,"items":[
              {"item":{"_uri":"spotify:artist:a1","data":{"__typename":"Artist","profile":{"name":"Daft Punk"},
                "visuals":{"avatarImage":{"sources":[{"url":"https://img/a","width":160}]}}}}},
              {"item":{"_uri":"spotify:playlist:p","data":{"__typename":"Playlist","name":"x"}}}
            ]}}}}""",
        ).jsonObject
        val (artists, total) = parseArtistPage(root)
        assertEquals(2, total)
        assertEquals("Daft Punk", artists.single().name)
        assertEquals("a1", artists.single().id)
        assertEquals("https://img/a", artists.single().imageUrl)
    }
}
