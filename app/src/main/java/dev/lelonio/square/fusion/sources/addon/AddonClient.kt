// Ported from BitChord (https://github.com/kushagrasinghx/BitChord, GPL-3.0),
// app/src/main/java/com/music/bitchord/data/sources/addon/AddonClient.kt.
// Changes: BitChord's SharedCalls replaced by a small TTL cache, the HTTP client
// injected (for tests), and Square Fusion's own logging and user agent.
package dev.lelonio.square.fusion.sources.addon

import dev.lelonio.square.fusion.FusionLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * One addon server, and every question this app asks it.
 *
 * Three endpoints, all GETs returning JSON: `/manifest.json`, `/search?q=` and
 * `/stream/{id}`. Nothing an addon sends is ever executed; everything it sends
 * is parsed leniently and treated as untrusted (bounded call time, bounded
 * body, URLs validated before they reach the player).
 */
class AddonClient(
    rawBaseUrl: String,
    private val client: OkHttpClient = defaultClient,
    /** Whether the `?atmos=auto` hint may be sent; see [settingsFor]. */
    private val atmosWanted: () -> Boolean = { false },
) {

    val baseUrl: String = normalizeBase(rawBaseUrl)

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val manifests = TtlCache<AddonManifest>(MANIFEST_TTL_MS)
    private val searches = TtlCache<AddonSearchResponse>(SEARCH_TTL_MS)
    private val streams = TtlCache<AddonStream>(STREAM_TTL_MS)

    /** When this addon may be spoken to again after a 429; per server, not per call. */
    @Volatile
    private var quietUntilMs = 0L

    // ── Manifest ──────────────────────────────────────────────────────────

    suspend fun manifest(): Result<AddonManifest> = manifests.get(baseUrl) {
        fetch<AddonManifest>(manifestUrl(baseUrl)).mapCatching { manifest ->
            if (manifest.id.isBlank()) {
                throw AddonException("That URL answered, but not with an addon manifest")
            }
            if (!manifest.isPlayable) {
                val declared = manifest.resources.joinToString(", ")
                throw AddonException("This addon declares $declared — search is required")
            }
            manifest
        }.recoverCatching { failure ->
            if (failure is AddonNotFound) throw AddonException("No manifest at that URL")
            throw failure
        }
    }

    /** Whether `/search` answers, without a manifest. Zero tracks is a success. */
    suspend fun probeSearch(): Result<Int> =
        fetch<AddonSearchResponse>(endpoint(listOf("search"), mapOf("q" to PROBE_QUERY)))
            .map { it.tracks.size }

    // ── Search ────────────────────────────────────────────────────────────

    suspend fun search(query: String, tier: String): Result<List<AddonTrack>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return Result.success(emptyList())
        val params = settingsFor(tier)
        return searches.get(keyOf(trimmed, params.toString())) {
            fetch<AddonSearchResponse>(endpoint(listOf("search"), params + ("q" to trimmed)))
        }.map { it.tracks }
    }

    // ── Stream ────────────────────────────────────────────────────────────

    /** [trackId] goes in as a path segment, so an id holding `/` or `?` cannot rewrite the request. */
    suspend fun stream(trackId: String, tier: String): Result<AddonStream> {
        val params = settingsFor(tier)
        return streams.get(keyOf(trackId, params.toString())) {
            fetch<AddonStream>(endpoint(listOf("stream", trackId), params))
        }
    }

    // ── Settings passthrough ──────────────────────────────────────────────

    /**
     * The query parameters that travel with every request: the addon's own
     * declared defaults, then `quality` negotiated against the options the
     * addon lists (an addon offering `high|normal|low` does not understand
     * `LOSSLESS`), then the standard `atmos=auto` hint when Atmos is wanted and
     * decodable here. `auto` and never `1`: the strict form turns every track
     * without an immersive mix into an error.
     */
    internal suspend fun settingsFor(tier: String): Map<String, String> {
        val declared = manifest().getOrNull()?.settings.orEmpty()
        val params = LinkedHashMap<String, String>()
        declared.forEach { setting ->
            val key = setting.key.takeIf { it.isNotBlank() } ?: return@forEach
            setting.defaultValue?.let { params[key] = it }
        }
        if (tier.isNotBlank()) {
            val options = declared.firstOrNull { it.key == QUALITY_KEY }
                ?.options.orEmpty()
                .mapNotNull { it.stringValue }
            params[QUALITY_KEY] = matchTier(tier, options) ?: tier
        }
        if (atmosWanted()) params.putIfAbsent(ATMOS_KEY, ATMOS_AUTO)
        return params
    }

    private fun matchTier(tier: String, options: List<String>): String? {
        if (options.isEmpty()) return null
        options.firstOrNull { it.equals(tier, ignoreCase = true) }?.let { return it }
        val wanted = when (tier.uppercase()) {
            TIER_LOSSLESS -> LOSSLESS_WORDS
            TIER_LOW -> LOW_WORDS
            else -> HIGH_WORDS
        }
        return options.firstOrNull { option -> wanted.any { it in option.lowercase() } }
            ?: options.first()
    }

    // ── Transport ─────────────────────────────────────────────────────────

    private fun endpoint(segments: List<String>, params: Map<String, String>): String {
        val base = baseUrl.toHttpUrlOrNull()
            ?: throw AddonException("That is not a usable address")
        val builder = base.newBuilder()
        segments.forEach(builder::addPathSegment)
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    private suspend inline fun <reified T> fetch(url: String): Result<T> =
        runCatching { json.decodeFromString<T>(body(url)) }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                if (failure !is AddonNotFound) {
                    FusionLog.w("addon call failed ${redact(url)}: ${failure.message}")
                }
            }
            .recoverCatching { failure ->
                // A body that is not the JSON promised is an addon fault the
                // user can act on, not a crash.
                if (failure is kotlinx.serialization.SerializationException) {
                    throw AddonException("The addon answered with something that is not valid JSON")
                }
                throw failure
            }

    /**
     * One GET. A 429 is waited out (Retry-After believed, capped); a 404 is a
     * miss; a 5xx is [AddonUnavailable] (try again later); any other 4xx is
     * [AddonException] (will not fix itself).
     */
    private suspend fun body(url: String): String = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            (quietUntilMs - System.currentTimeMillis()).takeIf { it > 0 }?.let { delay(it) }

            val request = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build()

            val wait = try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> {
                            val body = response.body ?: throw AddonException("Empty response")
                            if (body.contentLength() > MAX_BODY_BYTES) throw AddonException("Response too large")
                            val text = body.source().let { source ->
                                source.request(MAX_BODY_BYTES + 1)
                                if (source.buffer.size > MAX_BODY_BYTES) throw AddonException("Response too large")
                                source.buffer.readUtf8()
                            }
                            return@withContext text.takeIf { it.isNotBlank() }
                                ?: throw AddonException("Empty response")
                        }
                        response.code == 404 -> throw AddonNotFound()
                        response.code >= 500 -> throw AddonUnavailable("HTTP ${response.code}")
                        response.code != 429 -> throw AddonException("HTTP ${response.code}")
                        attempt >= MAX_RETRIES -> throw AddonUnavailable("This addon is rate limiting requests")
                        else -> retryAfterMs(response.header("Retry-After"), attempt)
                    }
                }
            } catch (e: java.io.InterruptedIOException) {
                throw AddonUnavailable("The addon did not answer in time")
            } catch (e: java.io.IOException) {
                throw AddonUnavailable(e.message ?: "Network error")
            }
            quietUntilMs = System.currentTimeMillis() + wait
            attempt++
        }
        @Suppress("UNREACHABLE_CODE")
        throw AddonException("unreachable")
    }

    private fun retryAfterMs(header: String?, attempt: Int): Long {
        val stated = header?.trim()?.toDoubleOrNull()?.times(1000)?.toLong()
        return (stated ?: (BACKOFF_BASE_MS shl attempt)).coerceIn(BACKOFF_BASE_MS, BACKOFF_CAP_MS)
    }

    fun clear() {
        manifests.clear()
        searches.clear()
        streams.clear()
        quietUntilMs = 0L
    }

    private fun keyOf(vararg parts: String) = parts.joinToString("|") { "${it.length}:$it" }

    /** Successful answers, kept for [ttlMs]. Failures are never kept. */
    private class TtlCache<T>(private val ttlMs: Long) {
        private val entries = ConcurrentHashMap<String, Pair<Long, T>>()

        suspend fun get(key: String, load: suspend () -> Result<T>): Result<T> {
            val now = System.currentTimeMillis()
            entries[key]?.takeIf { now - it.first < ttlMs }?.let { return Result.success(it.second) }
            return load().onSuccess {
                entries[key] = now to it
                if (entries.size > MAX_ENTRIES) {
                    entries.entries.minByOrNull { e -> e.value.first }?.let { e -> entries.remove(e.key) }
                }
            }
        }

        fun clear() = entries.clear()

        private companion object {
            const val MAX_ENTRIES = 256
        }
    }

    companion object {
        const val TIER_LOSSLESS = "LOSSLESS"
        const val TIER_HIGH = "HIGH"
        const val TIER_LOW = "LOW"

        private const val QUALITY_KEY = "quality"
        private const val ATMOS_KEY = "atmos"
        private const val ATMOS_AUTO = "auto"

        private val LOSSLESS_WORDS = listOf("lossless", "flac", "hifi", "hi-res", "hires", "max", "best")
        private val HIGH_WORDS = listOf("high", "320", "normal", "standard")
        private val LOW_WORDS = listOf("low", "96", "128", "min")

        private const val MANIFEST_SUFFIX = "/manifest.json"

        /** Both `https://host/` and `https://host/manifest.json` (or any `.json`) give the same base. */
        fun normalizeBase(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            val lastSegment = trimmed.substringAfterLast('/', "")
            return if (lastSegment.endsWith(".json", ignoreCase = true) && trimmed.contains("://")) {
                trimmed.dropLast(lastSegment.length).trimEnd('/')
            } else {
                trimmed
            }
        }

        fun manifestUrl(base: String): String = "$base$MANIFEST_SUFFIX"

        /** The host only: addons put private tokens in the path. */
        fun redact(url: String): String =
            url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/***" } ?: "***"

        const val MANIFEST_TTL_MS = 10 * 60 * 1000L
        const val SEARCH_TTL_MS = 10 * 60 * 1000L
        const val STREAM_TTL_MS = 5 * 60 * 1000L

        private const val MAX_RETRIES = 2
        private const val BACKOFF_BASE_MS = 500L
        private const val BACKOFF_CAP_MS = 8_000L

        /** No addon answer this app reads is anywhere near this. */
        private const val MAX_BODY_BYTES = 4L * 1024 * 1024

        const val USER_AGENT = "SquareFusion"

        private const val PROBE_QUERY = "music"

        /** The spec asks for search under 5 s and stream under 3 s; this bounds a server dribbling bytes. */
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
}

/** Something trying again will not fix: shown as [dev.lelonio.square.fusion.sources.SourceHealth.Rejected]. */
class AddonException(message: String) : Exception(message)

/** Momentarily not answering: down, overloaded, rate limiting, timed out. */
class AddonUnavailable(message: String) : Exception(message)

/** The addon answered and does not have it. A miss, not a fault. */
class AddonNotFound : Exception("Not held by this addon")
