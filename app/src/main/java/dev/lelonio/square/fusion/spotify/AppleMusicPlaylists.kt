package dev.lelonio.square.fusion.spotify

import dev.lelonio.square.fusion.FusionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

/**
 * Square Fusion: an Apple Music playlist read from its public web page
 * (music.apple.com/<storefront>/playlist/…/pl.…), for the Library's import.
 *
 * The page carries the playlist's title, curator, cover and its songs in
 * order (title, artist, album, length, cover) in the data it is rendered from
 * (`serialized-server-data`). No account, no Apple Music API key. A private
 * playlist (`pl.u-…`) or one taken down answers 404.
 *
 * Kept in the same store as Spotify's imports (SpotifyLibraryStore), under
 * an id of its own, "am.<storefront>.<playlist id>"; its songs are played
 * through YouTube Music like Spotify's (SpotifyMatches), under ids "am<song id>".
 */
object AppleMusicPlaylists {

    const val ID_PREFIX = "am."

    fun isApple(id: String) = id.startsWith(ID_PREFIX)

    private val LINK = Regex("""music\.apple\.com/([a-z]{2})/playlist/(?:[^/?#]+/)?(pl\.[A-Za-z0-9._-]+)""")

    /** "am.<storefront>.<playlist id>" for an Apple Music playlist link, or null. */
    fun playlistId(link: String): String? =
        LINK.find(link.trim())?.let { "$ID_PREFIX${it.groupValues[1]}.${it.groupValues[2]}" }

    data class Page(val playlist: SpotifyPlaylist, val tracks: List<SpotifyTrack>, val listedCount: Int?)

    suspend fun playlist(id: String): Page = withContext(Dispatchers.IO) {
        val (storefront, playlistId) = id.removePrefix(ID_PREFIX).split('.', limit = 2)
            .takeIf { it.size == 2 }?.let { it[0] to it[1] }
            ?: throw IllegalArgumentException("Not an Apple Music playlist id: $id")
        val request = Request.Builder()
            .url("https://music.apple.com/$storefront/playlist/$playlistId")
            .header("User-Agent", SpotifyFreeAccount.BROWSER_UA)
            .header("Accept-Language", "en")
            .build()
        val html = SpotifyFreeAccount.http.newCall(request).execute().use { response ->
            if (response.code == 404) throw SpotifyPlaylistUnavailable("apple music: 404")
            if (!response.isSuccessful) throw java.io.IOException("apple music: HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        parse(id, html).also { page ->
            if (page.listedCount != null && page.listedCount > page.tracks.size) {
                FusionLog.w("apple music $id: the page lists ${page.tracks.size} of ${page.listedCount} songs")
            }
            // What a song row looks like when its picture or length did not
            // come through, so a change in Apple's page shows in the log.
            val bare = page.tracks.count { it.imageUrl == null || it.durationMs <= 0 }
            if (bare > 0) {
                FusionLog.w("apple music $id: $bare of ${page.tracks.size} songs without a picture or a length; first row: ${firstRow(html)}")
            }
        }
    }

    /** The playlist in a page's HTML. Pure, for tests. */
    fun parse(id: String, html: String): Page {
        val data = DATA.find(html)?.groupValues?.get(1)
            ?: throw SpotifyPlaylistUnavailable("apple music: no page data")
        val root = Json.parseToJsonElement(data) as? JsonObject
            ?: throw SpotifyPlaylistUnavailable("apple music: page data unreadable")
        val sections = (root.arr("data")?.firstOrNull() as? JsonObject)
            ?.obj("data")?.arr("sections").orEmpty().filterIsInstance<JsonObject>()
        val header = sections.firstOrNull { it.str("itemKind") == "containerDetailHeaderLockup" }
            ?.arr("items")?.firstOrNull() as? JsonObject
            ?: throw SpotifyPlaylistUnavailable("apple music: no playlist")
        val rows = sections.firstOrNull { it.str("itemKind") == "trackLockup" }
            ?.arr("items").orEmpty().filterIsInstance<JsonObject>()
        val tracks = rows.mapNotNull { row ->
            val descriptor = row.obj("contentDescriptor")
            if (descriptor?.str("kind") != "song") return@mapNotNull null
            val songId = descriptor.obj("identifiers")?.str("storeAdamID") ?: return@mapNotNull null
            SpotifyTrack(
                id = "am$songId",
                title = row.str("title") ?: return@mapNotNull null,
                artist = row.str("artistName")
                    ?: row.arr("subtitleLinks")?.mapNotNull { (it as? JsonObject)?.str("title") }?.joinToString(", ")
                    ?: "",
                album = (row.arr("tertiaryLinks")?.firstOrNull() as? JsonObject)?.str("title"),
                durationMs = row.prim("duration")?.longOrNull?.toInt() ?: 0,
                imageUrl = artwork(row),
            )
        }
        val playlist = SpotifyPlaylist(
            id = id,
            name = header.str("title") ?: throw SpotifyPlaylistUnavailable("apple music: no title"),
            owner = (header.arr("subtitleLinks")?.firstOrNull() as? JsonObject)?.str("title"),
            imageUrl = artwork(header),
        )
        return Page(playlist, tracks, header.prim("trackCount")?.intOrNull)
    }

    /** The first song row of the page, shortened, for the log. */
    private fun firstRow(html: String): String = runCatching {
        val data = DATA.find(html)?.groupValues?.get(1) ?: return "no page data"
        val root = Json.parseToJsonElement(data) as JsonObject
        val sections = (root.arr("data")?.firstOrNull() as? JsonObject)
            ?.obj("data")?.arr("sections").orEmpty().filterIsInstance<JsonObject>()
        sections.firstOrNull { it.str("itemKind") == "trackLockup" }?.arr("items")?.firstOrNull().toString().take(1800)
    }.getOrDefault("unreadable")

    /** The picture's address, at a size the app shows, from Apple's "{w}x{h}{c}.{f}" template. */
    private fun artwork(item: JsonObject): String? =
        item.obj("artwork")?.obj("dictionary")?.str("url")
            ?.replace("{w}", "600")?.replace("{h}", "600")?.replace("{c}", "bb")?.replace("{f}", "jpg")

    private val DATA = Regex("""<script[^>]*id="serialized-server-data"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.arr(key: String) = this[key] as? JsonArray
    private fun JsonObject.prim(key: String) = this[key] as? JsonPrimitive
    private fun JsonObject.str(key: String) = prim(key)?.takeIf { it.isString }?.contentOrNull
}
