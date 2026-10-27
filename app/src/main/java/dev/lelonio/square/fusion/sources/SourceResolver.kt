// Square Fusion's take on BitChord's SourceResolver (GPL-3.0): the same walk —
// enabled sources in the user's order, first acceptable answer wins, YouTube
// when none does — without BitChord's background upgrade machinery.
package dev.lelonio.square.fusion.sources

import dev.lelonio.square.fusion.FusionLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Finds a stream from the enabled sources for a YouTube Music track.
 *
 * Every failure here is contained: a source that throws, hangs, answers with
 * garbage or with something that does not satisfy the request is logged and
 * skipped. Null means "play it from YouTube", which is what Square did before
 * any of this existed.
 */
class SourceResolver(
    private val sources: () -> List<MusicSource>,
    private val perSourceTimeoutMs: Long = PER_SOURCE_TIMEOUT_MS,
) {

    /** Why the last resolution ended the way it did, for the stream-info screen. */
    data class Outcome(val stream: SourceStream?, val tried: List<String>)

    suspend fun resolve(
        target: TrackMatcher.Target,
        request: StreamRequest,
        forDownload: Boolean = false,
    ): Outcome {
        val tried = mutableListOf<String>()
        val active = runCatching { sources() }.getOrElse {
            FusionLog.w("sources unavailable: ${it.message}")
            emptyList()
        }
        if (active.isEmpty()) return Outcome(null, tried)
        val query = TrackMatcher.query(target)
        if (query.isBlank()) return Outcome(null, tried)

        for (source in active) {
            val stream = attempt(source) {
                val rows = source.search(query, limit = SEARCH_LIMIT, request = request)
                val row = TrackMatcher.best(rows, target) ?: return@attempt null.also {
                    tried += "${source.displayName}: no match"
                }
                source.stream(row.id, request)?.let { found ->
                    found.copy(
                        durationSec = found.durationSec ?: row.durationSec,
                        sourceConfigId = source.configId,
                        sourceName = source.displayName,
                    )
                }
            }
            if (stream == null) {
                if (tried.lastOrNull()?.startsWith(source.displayName) != true) tried += "${source.displayName}: no stream"
                continue
            }
            val refusal = refusal(stream, request, forDownload)
            if (refusal != null) {
                tried += "${source.displayName}: $refusal"
                FusionLog.d("${source.displayName}: passed over — $refusal")
                continue
            }
            tried += "${source.displayName}: ${stream.format.summary}"
            return Outcome(stream, tried)
        }
        return Outcome(null, tried)
    }

    /**
     * Why [stream] does not answer [request], or null when it does.
     *
     * Lossless is a promise: a stream the source does not itself describe as a
     * lossless codec is not accepted for a Lossless request, and nothing is
     * ever re-labelled. A capped request refuses a stream known to exceed the
     * cap. A manifest (HLS/DASH) is refused because Square's player opens
     * progressive files only. A download refuses a source with
     * `allowDownloads` off.
     */
    internal fun refusal(stream: SourceStream, request: StreamRequest, forDownload: Boolean): String? {
        if (stream.transport != null) return "${stream.transport} manifest (not supported by the player)"
        if (forDownload && !stream.downloadAllowed) return "downloads not allowed by this source"
        val format = stream.format
        return when (request) {
            is StreamRequest.Lossless ->
                if (format.isLossless != true || format.codecInferred) "not stated as lossless (${format.summary})" else null
            is StreamRequest.Capped ->
                if (!format.kbpsInferred && format.kbps != null && format.kbps > request.maxKbps) {
                    "${format.kbps} kbps over the ${request.maxKbps} kbps cap"
                } else if (format.isLossless == true) {
                    "lossless stream for a capped request"
                } else {
                    null
                }
            is StreamRequest.Best -> null
        }
    }

    private suspend fun <T> attempt(source: MusicSource, block: suspend () -> T?): T? = try {
        withTimeout(perSourceTimeoutMs) { block() }
    } catch (e: TimeoutCancellationException) {
        FusionLog.w("${source.displayName}: timed out after ${perSourceTimeoutMs}ms")
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        FusionLog.w("${source.displayName}: failed — ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    companion object {
        /** Spent on a loading thread while the track waits; YouTube follows. */
        const val PER_SOURCE_TIMEOUT_MS = 8_000L
        private const val SEARCH_LIMIT = 10
    }
}
