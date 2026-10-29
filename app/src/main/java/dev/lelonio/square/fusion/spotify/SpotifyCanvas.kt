// Adapted from BitChord (GPL-3.0), SpotifyCanvasQuery.kt and SpotifyCanvas.kt:
// a track's Canvas asked for the way Spotify's web player asks for it.
package dev.lelonio.square.fusion.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Square Fusion: Spotify's Canvas — the looping clip behind a song — read with
 * the Spotify connection of Settings › Account, without the Spotify player.
 *
 * Only for a song whose Spotify identity is known: one from a Spotify playlist,
 * found on YouTube Music under its own Spotify id. A song known only to YouTube
 * Music is not looked up on Spotify by its name, since a similar title is not
 * the same recording. Signed out, or with no Canvas for the song, the answer is
 * null and the cover stays.
 */
object SpotifyCanvas {
    private const val ENDPOINT = "https://api-partner.spotify.com/pathfinder/v2/query"

    /** BitChord's last known hash of the `canvas` query; the live one is read off the web player. */
    private const val KNOWN_HASH = "575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val NONE = ""

    @Volatile private var hash: String? = null
    @Volatile private var hashCheckedAt = 0L

    /** The clip's address for `spotify:track:<id>`, or null. Never throws. */
    suspend fun clipFor(spotifyId: String): String? = withContext(Dispatchers.IO) {
        cache[spotifyId]?.let { return@withContext it.ifEmpty { null } }
        val tokens = runCatching { SpotifyFreeLibrary.auth?.tokens() }.getOrNull() ?: return@withContext null
        val uri = "spotify:track:$spotifyId"
        var answer = ask(uri, tokens, canvasHash(false))
        if (answer == Answer.Stale) answer = ask(uri, tokens, canvasHash(true))
        val url = (answer as? Answer.Found)?.url
        // A definite "none" is remembered for the session; a failure is not.
        if (answer is Answer.Found || answer == Answer.None) cache[spotifyId] = url ?: NONE
        url
    }

    private sealed interface Answer {
        data class Found(val url: String) : Answer
        data object None : Answer
        data object Stale : Answer
        data object Failed : Answer
    }

    private fun ask(trackUri: String, tokens: SpotifyFreeLibrary.Tokens, hash: String): Answer {
        val body = buildJsonObject {
            put("operationName", "canvas")
            putJsonObject("variables") { put("trackUri", trackUri) }
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", hash)
                }
            }
        }.toString()
        val request = Request.Builder()
            .url(ENDPOINT)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer ${tokens.bearer}")
            .header("App-platform", "WebPlayer")
            .header("Accept", "application/json")
            .header("Accept-Language", "en")
            .apply { tokens.clientToken?.let { header("Client-Token", it) } }
            .build()
        val text = runCatching {
            SpotifyHttp.client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return Answer.Failed
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return Answer.Failed
        val data = root["data"] as? JsonObject
        if (data == null) {
            val stale = root.toString().contains("PersistedQueryNotFound", ignoreCase = true)
            return if (stale) Answer.Stale else Answer.Failed
        }
        val canvas = (data["trackUnion"] as? JsonObject)?.get("canvas") as? JsonObject ?: return Answer.None
        val url = (canvas["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("https://") }
            ?: return Answer.None
        // Some canvases are still pictures; only a clip is a Canvas here.
        return if (VIDEO.containsMatchIn(url)) Answer.Found(url) else Answer.None
    }

    /** The live hash of the `canvas` query, read off the web player's scripts, kept for 12 hours. */
    @Synchronized
    private fun canvasHash(forceRefresh: Boolean): String {
        val age = System.currentTimeMillis() - hashCheckedAt
        if (!forceRefresh && hashCheckedAt != 0L && age < (if (hash != null) 12 * 3_600_000L else 1_800_000L)) {
            return hash ?: KNOWN_HASH
        }
        hashCheckedAt = System.currentTimeMillis()
        val html = get("https://open.spotify.com/")
        if (html != null) {
            for (script in SCRIPT.findAll(html).map { it.value }.distinct().take(8)) {
                val body = get(script) ?: continue
                DECLARATION.findAll(body).firstOrNull { it.groupValues[1] == "canvas" }?.let {
                    hash = it.groupValues[2]
                    return it.groupValues[2]
                }
            }
        }
        return hash ?: KNOWN_HASH
    }

    private fun get(url: String): String? = runCatching {
        SpotifyHttp.client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .build(),
        ).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    private val VIDEO = Regex("""\.mp4(?:[?#]|$)""", RegexOption.IGNORE_CASE)
    private val SCRIPT = Regex("""https://open\.spotifycdn\.com/cdn/build/web-player/[A-Za-z0-9._~-]+\.js""")
    private val DECLARATION = Regex("""["'](\w+)["']\s*,\s*["']query["']\s*,\s*["']([0-9a-f]{64})["']""")
}
