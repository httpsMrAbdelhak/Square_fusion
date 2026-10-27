// Ported from BitChord (https://github.com/kushagrasinghx/BitChord, GPL-3.0),
// app/src/main/java/com/music/bitchord/data/jiosaavn/JioSaavnService.kt and
// data/sources/JioSaavnSource.kt.
//
// Changes from BitChord:
//  - OkHttp instead of Ktor (Square already ships OkHttp).
//  - The X-Forwarded-For / X-Real-IP headers BitChord sends with an Indian IP
//    address are NOT sent: they pretend the request comes from India to get
//    around JioSaavn's regional restriction. Outside the regions JioSaavn
//    serves, this source is therefore expected to return nothing.
//
// UNOFFICIAL API. JioSaavn publishes no public API; this speaks to the private
// endpoint its own web client uses. It can change or stop at any time, its use
// is not authorised by JioSaavn, and it is off unless the user adds it. See
// docs/FUSION.md for the limits and the legal caveats.
package dev.lelonio.square.fusion.jiosaavn

import dev.lelonio.square.fusion.FusionLog
import dev.lelonio.square.fusion.sources.MusicSource
import dev.lelonio.square.fusion.sources.SourceConfig
import dev.lelonio.square.fusion.sources.SourceHealth
import dev.lelonio.square.fusion.sources.SourceKind
import dev.lelonio.square.fusion.sources.SourceStream
import dev.lelonio.square.fusion.sources.SourceTrack
import dev.lelonio.square.fusion.sources.StreamFormat
import dev.lelonio.square.fusion.sources.StreamRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@Serializable
data class SaavnArtist(val id: String = "", val name: String = "")

@Serializable
data class SaavnArtistMap(
    @SerialName("primary_artists") val primaryArtists: List<SaavnArtist> = emptyList(),
)

@Serializable
data class SaavnMoreInfo(
    val album: String = "",
    @SerialName("encrypted_media_url") val encryptedMediaUrl: String = "",
    val duration: String = "",
    @SerialName("320kbps") val has320: String = "",
    val artistMap: SaavnArtistMap = SaavnArtistMap(),
) {
    val supports320: Boolean get() = has320.equals("true", ignoreCase = true)
}

@Serializable
data class SaavnSong(
    val id: String = "",
    val title: String = "",
    val image: String = "",
    @SerialName("explicit_content") val explicitContent: String = "",
    @SerialName("more_info") val moreInfo: SaavnMoreInfo = SaavnMoreInfo(),
) {
    val isExplicit: Boolean
        get() = explicitContent == "1" || explicitContent.equals("true", ignoreCase = true)
}

@Serializable
data class SaavnSearchResponse(val results: List<SaavnSong> = emptyList())

data class SaavnStream(val url: String, val kbps: Int?)

/** The 320 kbps rendition when the song has one, otherwise what was offered. */
internal fun selectBestSaavnStream(decryptedUrl: String, supports320: Boolean): SaavnStream? {
    if (decryptedUrl.isBlank()) return null
    val rendition = Regex("_(48|96|160|320)\\.(mp4|aac|mp3)(?=[?#]|$)", RegexOption.IGNORE_CASE)
        .find(decryptedUrl) ?: return SaavnStream(decryptedUrl, null)
    val offered = rendition.groupValues[1].toInt()
    if (!supports320) return SaavnStream(decryptedUrl, offered)
    return SaavnStream(decryptedUrl.replaceRange(rendition.range, "_320.${rendition.groupValues[2]}"), 320)
}

class JioSaavnService(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = defaultClient,
) {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    private fun call(params: Map<String, String>): String? {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            COMMON.forEach { (k, v) -> addQueryParameter(k, v) }
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Accept-Language", "en-IN,en;q=0.9")
            .header("Cookie", "explicit_content=1")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                FusionLog.w("JioSaavn HTTP ${response.code}")
                return null
            }
            return response.body?.string()
        }
    }

    /** Never throws: an API change or outage is an empty list. */
    fun searchSongs(query: String): List<SaavnSong> = runCatching {
        val body = call(mapOf("__call" to "search.getResults", "q" to query, "p" to "1", "n" to "10"))
            ?: return@runCatching emptyList()
        json.decodeFromString<SaavnSearchResponse>(body).results
    }.getOrElse {
        FusionLog.w("JioSaavn search error: ${it.message}")
        emptyList()
    }

    /** Never throws: null when the song, the field or the format is missing. */
    fun streamFor(songId: String): SaavnStream? = runCatching {
        val body = call(mapOf("__call" to "song.getDetails", "pids" to songId)) ?: return@runCatching null
        val root = json.parseToJsonElement(body) as? JsonObject ?: return@runCatching null
        val element = (root["songs"] as? JsonArray)?.firstOrNull()
            ?: root.values.firstOrNull { it is JsonObject }
            ?: return@runCatching null
        val song = json.decodeFromJsonElement(SaavnSong.serializer(), element)
        selectBestSaavnStream(decryptUrl(song.moreInfo.encryptedMediaUrl), song.moreInfo.supports320)
    }.getOrElse {
        FusionLog.w("JioSaavn details error: ${it.message}")
        null
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.jiosaavn.com/api.php"

        private val COMMON = mapOf(
            "_format" to "json",
            "_marker" to "0",
            "api_version" to "4",
            "ctx" to "android",
        )

        /**
         * The media URL comes DES-encoded with a fixed key that JioSaavn's own
         * web client ships; this is how BitChord (and every other third-party
         * JioSaavn client) reads it. Documented as a legal caveat in FUSION.md.
         */
        internal fun decryptUrl(encrypted: String): String {
            if (encrypted.isBlank()) return ""
            return runCatching {
                val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("38346591".toByteArray(Charsets.UTF_8), "DES"))
                String(cipher.doFinal(Base64.getMimeDecoder().decode(encrypted)), Charsets.UTF_8).trim()
            }.getOrDefault("")
        }

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                .build()
        }
    }
}

class JioSaavnSource(
    private val config: SourceConfig,
    private val service: JioSaavnService = JioSaavnService(),
) : MusicSource {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.JIOSAAVN
    override val displayName: String get() = config.displayName

    override suspend fun health(): SourceHealth = withContext(Dispatchers.IO) {
        val rows = service.searchSongs("music")
        if (rows.isNotEmpty()) {
            SourceHealth.Ok("Unofficial API · ${rows.size} results")
        } else {
            SourceHealth.Unreachable("No answer — the unofficial API may have changed or may not serve your region")
        }
    }

    override suspend fun search(query: String, limit: Int, request: StreamRequest?): List<SourceTrack> =
        withContext(Dispatchers.IO) {
            service.searchSongs(query).take(limit).map { raw ->
                SourceTrack(
                    id = raw.id,
                    title = raw.title,
                    artist = raw.moreInfo.artistMap.primaryArtists.joinToString(", ") { it.name },
                    album = raw.moreInfo.album.ifBlank { null },
                    durationSec = raw.moreInfo.duration.toIntOrNull(),
                    artworkUrl = raw.image.replace(Regex("150x150|50x50"), "500x500").replace(Regex("^http://"), "https://"),
                    tier = "HIGH",
                    explicit = raw.isExplicit,
                )
            }
        }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
        withContext(Dispatchers.IO) {
            // AAC at 320 kbps at best: never lossless.
            if (request is StreamRequest.Lossless) return@withContext null
            val stream = service.streamFor(trackId) ?: return@withContext null
            if (!stream.url.startsWith("https://") && !stream.url.startsWith("http://")) return@withContext null
            if (stream.kbps != null && stream.kbps <= MIN_USABLE_KBPS) return@withContext null
            SourceStream(
                url = stream.url.replace(Regex("^http://"), "https://"),
                format = StreamFormat(codec = "aac", kbps = stream.kbps, kbpsInferred = stream.kbps == null),
                // JioSaavn's terms give no right to keep its files.
                downloadAllowed = false,
            )
        }

    private companion object {
        const val MIN_USABLE_KBPS = 96
    }
}
