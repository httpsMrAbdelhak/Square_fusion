package dev.lelonio.square.fusion

import dev.lelonio.square.backend.youtube.AudioQuality
import dev.lelonio.square.ui.home.greetingOf
import dev.lelonio.square.fusion.spotify.AppleMusicPlaylists
import dev.lelonio.square.fusion.spotify.SpotifyEmbed
import dev.lelonio.square.fusion.spotify.SpotifyLinks
import dev.lelonio.square.fusion.spotify.SpotifyMatches
import dev.lelonio.square.fusion.spotify.SpotifyPlaylistUnavailable
import dev.lelonio.square.fusion.spotify.parseArtistPage
import dev.lelonio.square.fusion.spotify.parsePlaylistInfo
import dev.lelonio.square.fusion.spotify.parseTrackPage
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

    // ── Greeting, quality line ──────────────────────────────────────────

    @Test
    fun greetingFollowsTheHour() {
        val of: (Int) -> Int = { greetingOf(it) }
        assertEquals(2, of(4))
        assertEquals(0, of(5))
        assertEquals(0, of(11))
        assertEquals(1, of(12))
        assertEquals(1, of(17))
        assertEquals(2, of(18))
        assertEquals(2, of(23))
    }

    @Test
    fun qualityLineNamesTheCodecAndChecksTheStatedOne() {
        assertEquals("AAC", AudioQuality.codecName("audio/mp4a-latm"))
        assertEquals("Opus", AudioQuality.codecName("audio/opus"))
        assertNull(AudioQuality.codecName("audio/x-unknown"))
        val aac = AudioQuality.Choice(AudioQuality.Tier.HIGH, "M4A", 128, 140, signedIn = false)
        val opus = AudioQuality.Choice(AudioQuality.Tier.MAX, "WEBMA_OPUS", 141, 251, signedIn = false)
        val premium = AudioQuality.Choice(AudioQuality.Tier.MAX, "audio/mp4", 256, 141, signedIn = true)
        assertEquals("AAC", AudioQuality.codecOfStated(aac))
        assertEquals("Opus", AudioQuality.codecOfStated(opus))
        assertEquals("AAC", AudioQuality.codecOfStated(premium))
    }

    // ── Glass on Android 11 ─────────────────────────────────────────────

    @Test
    fun glassIsOfferedFromAndroid11() {
        assertEquals(false, dev.lelonio.square.ui.glass.isGlassSupported(29))
        assertEquals(true, dev.lelonio.square.ui.glass.isGlassSupported(30))
        assertEquals(true, dev.lelonio.square.ui.glass.isGlassSupported(31))
        assertEquals(true, dev.lelonio.square.ui.glass.isGlassSupported(35))
    }

    @Test
    fun fallbackBlurLevelsFollowTheRadius() {
        val big = androidx.compose.ui.unit.IntSize(400, 200)
        val levels = dev.lelonio.square.ui.glass.backdrop.internal.FallbackBlur.Companion::levelsFor
        assertEquals(0, levels(0f, big))
        assertEquals(2, levels(7f, big))
        assertEquals(4, levels(28f, big))
        // Never more halvings than the pane has room for.
        assertEquals(1, levels(200f, androidx.compose.ui.unit.IntSize(12, 12)))
    }

    @Test
    fun colourMatricesCombineInOrder() {
        val half = androidx.compose.ui.graphics.ColorMatrix(
            floatArrayOf(0.5f, 0f, 0f, 0f, 0f, 0f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0.5f, 0f, 0f, 0f, 0f, 0f, 1f, 0f),
        )
        val plus = androidx.compose.ui.graphics.ColorMatrix(
            floatArrayOf(1f, 0f, 0f, 0f, 10f, 0f, 1f, 0f, 0f, 10f, 0f, 0f, 1f, 0f, 10f, 0f, 0f, 0f, 1f, 0f),
        )
        // Halve, then add 10: red 100 -> 60. Add, then halve: 100 -> 55.
        val halveThenAdd = dev.lelonio.square.ui.glass.backdrop.effects.thenApply(half, plus).values
        assertEquals(60f, halveThenAdd[0] * 100f + halveThenAdd[4], 0.001f)
        val addThenHalve = dev.lelonio.square.ui.glass.backdrop.effects.thenApply(plus, half).values
        assertEquals(55f, addThenHalve[0] * 100f + addThenHalve[4], 0.001f)
    }

    // ── Updates ─────────────────────────────────────────────────────────

    @Test
    fun fusionVersionsCompareByBuildThenUpstream() {
        val newer = dev.lelonio.square.update.Updater.Companion::isNewer
        assertEquals(true, newer("2.6.3-fusion.14", "2.6.3-fusion.13"))
        assertEquals(false, newer("2.6.3-fusion.13", "2.6.3-fusion.13"))
        assertEquals(true, newer("2.6.4-fusion.20", "2.6.3-fusion.25"))
        assertEquals(false, newer("2.6.3-fusion.9", "2.6.3-fusion.10"))
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
    fun publicEmbedPageIsRead() {
        val data = """{"props":{"pageProps":{"state":{"data":{"entity":{"type":"playlist","name":"Today’s Top Hits",
            "subtitle":"Spotify","coverArt":{"sources":[{"url":"https://i.scdn.co/image/abc"}]},
            "trackList":[
              {"uri":"spotify:track:2sFepQNyl1WjpROruP8bJ0","title":"Housewife","subtitle":"Tate McRae","duration":208810},
              {"uri":"spotify:episode:x","title":"Podcast","subtitle":"Show","duration":1},
              {"uri":"spotify:track:0VjIjW4GlUZAMYd2vXMi3b","title":"Blinding Lights","subtitle":"The Weeknd","duration":200040}
            ]}},
            "settings":{"clientId":"cid","session":{"accessToken":"tok","accessTokenExpirationTimestampMs":1700000000000,"isAnonymous":true}}}}}}"""
        val page = SpotifyEmbed.parse("<html><script id=\"__NEXT_DATA__\" type=\"application/json\">$data</script></html>")
        assertEquals("Today’s Top Hits", page.name)
        assertEquals("Spotify", page.owner)
        assertEquals("https://i.scdn.co/image/abc", page.imageUrl)
        assertEquals(listOf("Housewife", "Blinding Lights"), page.tracks.map { it.title })
        assertEquals("2sFepQNyl1WjpROruP8bJ0", page.tracks[0].id)
        assertEquals("Tate McRae", page.tracks[0].artist)
        assertEquals(208810, page.tracks[0].durationMs)
        assertEquals("tok", page.token)
        assertEquals("cid", page.clientId)
        try {
            SpotifyEmbed.parse("<html>Page not found</html>")
            throw AssertionError("expected unavailable")
        } catch (_: SpotifyPlaylistUnavailable) {
        }
    }

    @Test
    fun appleMusicLinksAndPage() {
        assertEquals(
            "am.us.pl.f4d106fed2bd41149aaacabb233eb5eb",
            AppleMusicPlaylists.playlistId("https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb?l=fr"),
        )
        assertEquals("am.fr.pl.u-AbC123", AppleMusicPlaylists.playlistId("https://music.apple.com/fr/playlist/pl.u-AbC123"))
        assertNull(AppleMusicPlaylists.playlistId("https://music.apple.com/us/album/housewife/6822497770"))
        assertNull(AppleMusicPlaylists.playlistId("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))

        val data = """{"data":[{"data":{"sections":[
          {"itemKind":"containerDetailHeaderLockup","items":[{"title":"Today’s Hits","trackCount":50,
            "subtitleLinks":[{"title":"Apple Music Hits"}],"artwork":{"dictionary":{"url":"https://is1/x/{w}x{h}{c}.{f}"}}}]},
          {"itemKind":"trackLockup","items":[
            {"title":"Housewife","artistName":"Tate McRae","duration":208810,
             "tertiaryLinks":[{"title":"Housewife - Single"}],
             "contentDescriptor":{"kind":"song","identifiers":{"storeAdamID":"6822497774"}}},
            {"title":"A video","contentDescriptor":{"kind":"musicVideo","identifiers":{"storeAdamID":"1"}}},
            {"title":"Man I Need","subtitleLinks":[{"title":"Olivia Dean"}],"duration":185000,
             "contentDescriptor":{"kind":"song","identifiers":{"storeAdamID":"42"}}}
          ]}]}}]}"""
        val page = AppleMusicPlaylists.parse("am.us.pl.x", "<script type=\"application/json\" id=\"serialized-server-data\">$data</script>")
        assertEquals("Today’s Hits", page.playlist.name)
        assertEquals("Apple Music Hits", page.playlist.owner)
        assertEquals("https://is1/x/600x600bb.jpg", page.playlist.imageUrl)
        assertEquals(50, page.listedCount)
        assertEquals(listOf("Housewife", "Man I Need"), page.tracks.map { it.title })
        assertEquals("am6822497774", page.tracks[0].id)
        assertEquals("Housewife - Single", page.tracks[0].album)
        assertEquals("Olivia Dean", page.tracks[1].artist)
        assertEquals(185000, page.tracks[1].durationMs)
        try {
            AppleMusicPlaylists.parse("am.us.pl.x", "<html>Not found</html>")
            throw AssertionError("expected unavailable")
        } catch (_: SpotifyPlaylistUnavailable) {
        }
    }

    @Test
    fun playlistSongsKeepTheirLength() {
        val root = json.parseToJsonElement(
            """{"data":{"playlistV2":{"content":{"totalCount":2,"items":[
              {"itemV2":{"data":{"uri":"spotify:track:a","name":"One More Night","trackDuration":{"totalMilliseconds":219546},
                "artists":{"items":[{"profile":{"name":"Maroon 5"}}]},"albumOfTrack":{"name":"Overexposed"}}}},
              {"itemV2":{"data":{"uri":"spotify:track:b","name":"Older","duration":{"totalMilliseconds":200000},
                "artists":{"items":[{"profile":{"name":"X"}}]}}}}
            ]}}}}""",
        ).jsonObject
        val (songs, total, _) = parseTrackPage(root)
        assertEquals(2, total)
        assertEquals(219546, songs[0].durationMs)
        assertEquals("Maroon 5", songs[0].artist)
        assertEquals(200000, songs[1].durationMs)
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
