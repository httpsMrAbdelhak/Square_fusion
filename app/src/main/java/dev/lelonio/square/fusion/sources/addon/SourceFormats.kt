// Ported from BitChord (https://github.com/kushagrasinghx/BitChord, GPL-3.0),
// app/src/main/java/com/music/bitchord/data/sources/addon/SourceFormats.kt.
// Changes: a module index (BitChord's MODULE system, which downloads and runs
// JavaScript) is recognised only to be refused; nothing of it is ported.
package dev.lelonio.square.fusion.sources.addon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** What is on the end of a URL somebody pasted. */
sealed interface DetectedFormat {
    /** An addon server; [manifest] may be synthesised from a working `/search`. */
    data class Addon(val manifest: AddonManifest, val baseUrl: String) : DetectedFormat

    /** Recognised, and not something this app plays. [reason] is shown to the user. */
    data class Unsupported(val reason: String) : DetectedFormat
}

object SourceFormats {

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * Fetches [rawUrl] and works out what it is: the link itself when it names
     * a `.json` document, then `{base}/manifest.json`, then whether `{base}/search`
     * answers at all, so an addon without a manifest can still be added.
     */
    suspend fun identify(
        rawUrl: String,
        client: OkHttpClient = AddonClient.defaultClient,
    ): Result<DetectedFormat> = withContext(Dispatchers.IO) {
        val url = rawUrl.trim().trimEnd('/')
        val parsed = url.toHttpUrlOrNull()
        if (parsed == null || (parsed.scheme != "https" && parsed.scheme != "http")) {
            return@withContext Result.success(DetectedFormat.Unsupported("That is not an http(s) address"))
        }
        val base = AddonClient.normalizeBase(url)
        val isDocument = url.substringBefore('?').endsWith(".json", ignoreCase = true)
        val attempts = if (isDocument) listOf(url, AddonClient.manifestUrl(base)) else listOf(AddonClient.manifestUrl(url))

        var lastFailure: Throwable? = null
        var firstUnsupported: DetectedFormat.Unsupported? = null
        for (attempt in attempts) {
            val body = fetch(attempt, client).getOrElse { failure ->
                lastFailure = failure
                continue
            }
            when (val found = detect(body, attempt)) {
                is DetectedFormat.Unsupported -> firstUnsupported = firstUnsupported ?: found
                else -> return@withContext Result.success(found)
            }
        }

        if (AddonClient(base, client).probeSearch().isSuccess) {
            return@withContext Result.success(DetectedFormat.Addon(synthesised(base), base))
        }
        firstUnsupported?.let { return@withContext Result.success(it) }
        Result.failure(lastFailure ?: AddonUnavailable("Nothing answered at that address"))
    }

    /** Which format [body] is, decided on its shape alone. Pure, for tests. */
    fun detect(body: String, url: String = ""): DetectedFormat {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: return DetectedFormat.Unsupported("That address did not return JSON")
        val obj = root as? JsonObject
            ?: return DetectedFormat.Unsupported("That JSON is a list, not an addon manifest")

        if (obj.keys.any { it.startsWith("category:") }) {
            return DetectedFormat.Unsupported(
                "This is a MODULE index (JavaScript plugins). Square Fusion does not run third-party code; only addon servers are supported.",
            )
        }

        val looksLikeManifest = obj["resources"] is JsonArray ||
            (obj.containsKey("id") && (obj.containsKey("name") || obj.containsKey("version")))
        if (looksLikeManifest) {
            val manifest = runCatching { json.decodeFromJsonElement(AddonManifest.serializer(), obj) }.getOrNull()
                ?: return DetectedFormat.Unsupported("That looks like an addon manifest, but it could not be read")
            if (manifest.resources.isNotEmpty() && !manifest.declares("search")) {
                return DetectedFormat.Unsupported(
                    "This addon declares ${manifest.resources.joinToString(", ")} — search is required",
                )
            }
            return DetectedFormat.Addon(manifest, AddonClient.normalizeBase(url))
        }

        val listKey = obj.entries.firstOrNull { it.value is JsonArray && (it.value as JsonArray).isNotEmpty() }?.key
        if (listKey != null) {
            return DetectedFormat.Unsupported("That JSON lists \"$listKey\", which is not an addon manifest")
        }
        val keys = obj.keys.take(4).joinToString(", ").ifBlank { "nothing" }
        return DetectedFormat.Unsupported("Unrecognised JSON — it holds $keys")
    }

    private fun synthesised(base: String) = AddonManifest(
        id = base,
        name = base.toHttpUrlOrNull()?.host.orEmpty().ifBlank { base },
        resources = listOf("search", "stream"),
    )

    private fun fetch(url: String, client: OkHttpClient): Result<String> = runCatching {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", AddonClient.USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code >= 500) throw AddonUnavailable("HTTP ${response.code}")
                throw AddonException("HTTP ${response.code}")
            }
            val text = response.body?.string()
            text?.takeIf { it.isNotBlank() && it.length < 4 * 1024 * 1024 } ?: throw AddonException("Empty response")
        }
    }
}
