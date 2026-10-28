package dev.lelonio.square.fusion.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

/**
 * Square Fusion: a public Spotify playlist read from its embed page
 * (open.spotify.com/embed/playlist/<id>), the one sites put in an iframe.
 *
 * The page carries the playlist's name, owner, cover and songs (title,
 * artists, length) in its own server-rendered data, plus an anonymous token.
 * No account and no WebView: used to import a public playlist when no
 * account is connected, or when the account cannot read it.
 */
object SpotifyEmbed {

    data class Playlist(
        val name: String,
        val owner: String?,
        val imageUrl: String?,
        val tracks: List<SpotifyTrack>,
        val token: String?,
        val tokenExpiresAtMs: Long,
        val clientId: String?,
    )

    /** Embed pages list at most this many songs; a playlist that reaches it may be longer. */
    const val MAX_TRACKS = 100

    suspend fun playlist(id: String): Playlist = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://open.spotify.com/embed/playlist/$id")
            .header("User-Agent", SpotifyFreeAccount.BROWSER_UA)
            .header("Accept-Language", "en")
            .build()
        val html = SpotifyFreeAccount.http.newCall(request).execute().use { response ->
            if (response.code == 404) throw SpotifyPlaylistUnavailable("embed: 404")
            if (!response.isSuccessful) throw java.io.IOException("embed: HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        parse(html)
    }

    /** The playlist in an embed page's HTML. Pure, for tests. */
    fun parse(html: String): Playlist {
        val data = NEXT_DATA.find(html)?.groupValues?.get(1)
            ?: throw SpotifyPlaylistUnavailable("embed: no page data")
        val root = Json.parseToJsonElement(data) as? JsonObject
            ?: throw SpotifyPlaylistUnavailable("embed: page data unreadable")
        val state = root.obj("props")?.obj("pageProps")?.obj("state")
        val entity = state?.obj("data")?.obj("entity")
        if (entity == null || entity.str("type") != "playlist") {
            throw SpotifyPlaylistUnavailable("embed: no playlist")
        }
        val tracks = entity.arr("trackList").orEmpty().mapNotNull { row ->
            val item = row as? JsonObject ?: return@mapNotNull null
            val uri = item.str("uri") ?: return@mapNotNull null
            if (!uri.startsWith("spotify:track:")) return@mapNotNull null
            SpotifyTrack(
                id = uri.removePrefix("spotify:track:"),
                title = item.str("title") ?: return@mapNotNull null,
                artist = item.str("subtitle").orEmpty().replace(' ', ' '),
                album = null,
                durationMs = item.long("duration")?.toInt() ?: 0,
                imageUrl = null,
            )
        }
        val session = state.obj("settings")?.obj("session")
        return Playlist(
            name = entity.str("name") ?: entity.str("title") ?: throw SpotifyPlaylistUnavailable("embed: no name"),
            owner = entity.str("subtitle"),
            imageUrl = entity.obj("coverArt")?.arr("sources")?.firstOrNull()?.let { (it as? JsonObject)?.str("url") },
            tracks = tracks,
            token = session?.str("accessToken")?.takeIf { session.bool("isAnonymous") != false },
            tokenExpiresAtMs = session?.long("accessTokenExpirationTimestampMs") ?: 0L,
            clientId = state.obj("settings")?.str("clientId"),
        )
    }

    private val NEXT_DATA = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.arr(key: String) = this[key] as? JsonArray
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
}
