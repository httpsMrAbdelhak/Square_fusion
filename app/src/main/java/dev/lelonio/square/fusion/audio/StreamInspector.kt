package dev.lelonio.square.fusion.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import dev.lelonio.square.fusion.sources.StreamFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * What the current song's stream really is, set against what its source said.
 *
 * Three layers, each from where it can be known:
 *  - **Announced**: the source's own claim (an addon's JSON, or "YouTube").
 *  - **Received**: the format ExoPlayer read from the file (container and
 *    codec, sample rate, channels, bit depth where the codec exposes it), and
 *    the decoder it chose.
 *  - **Delivered**: the PCM that reached Square's mixer, read where the
 *    mixer's resampler is configured, and the mixer's own fixed rate.
 *
 * Anything not exposed is null and shown as "Unknown"; nothing is filled in by
 * assumption.
 */
object StreamInspector {

    data class Announced(
        val sourceName: String,
        val format: StreamFormat?,
    )

    data class Received(
        val mimeType: String?,
        val codecs: String?,
        val sampleRateHz: Int?,
        val channels: Int?,
        val bitDepth: Int?,
        val bitrateKbps: Int?,
        val decoderName: String?,
    )

    data class Delivered(
        val sampleRateHz: Int?,
        val channels: Int?,
        val bitDepth: Int?,
        val float: Boolean,
    )

    enum class Verdict { MATCHES, DIFFERS, UNKNOWN }

    enum class Atmos {
        /** Not an E-AC-3 JOC stream (or not known to be). */
        NONE,

        /** E-AC-3 JOC in the stream, and a platform Dolby decoder chose it. Rendering still depends on the output. */
        IN_STREAM_DECODER_PRESENT,

        /** E-AC-3 JOC in the stream, decoded by a decoder that renders a downmix, not Atmos. */
        IN_STREAM_NOT_RENDERED,
    }

    data class Report(
        val mediaId: String? = null,
        val announced: Announced? = null,
        val received: Received? = null,
        val delivered: Delivered? = null,
        /** Square's mixer rate when the song goes through the mixer, else null. */
        val mixerRateHz: Int? = null,
    ) {
        val codecVerdict: Verdict
            get() {
                val claimed = announced?.format?.codec ?: return Verdict.UNKNOWN
                val mime = received?.mimeType ?: return Verdict.UNKNOWN
                val expected = MIMES[claimed.lowercase(Locale.ROOT)] ?: return Verdict.UNKNOWN
                return if (expected.any { mime.equals(it, ignoreCase = true) }) Verdict.MATCHES else Verdict.DIFFERS
            }

        val sampleRateVerdict: Verdict
            get() {
                val claimed = announced?.format?.sampleRateHz ?: return Verdict.UNKNOWN
                val got = received?.sampleRateHz ?: return Verdict.UNKNOWN
                return if (claimed == got) Verdict.MATCHES else Verdict.DIFFERS
            }

        val bitDepthVerdict: Verdict
            get() {
                val claimed = announced?.format?.bitDepth ?: return Verdict.UNKNOWN
                val got = received?.bitDepth ?: return Verdict.UNKNOWN
                return if (claimed == got) Verdict.MATCHES else Verdict.DIFFERS
            }

        /** Whether the file is a lossless codec, by what the decoder was given. Null: unknown. */
        val receivedLossless: Boolean?
            get() = received?.mimeType?.let { it.lowercase(Locale.ROOT) in LOSSLESS_MIMES }

        val atmos: Atmos
            get() {
                if (received?.mimeType?.equals(MimeTypes.AUDIO_E_AC3_JOC, ignoreCase = true) != true) return Atmos.NONE
                val decoder = received.decoderName ?: return Atmos.IN_STREAM_NOT_RENDERED
                return if (decoder in DeviceCodecs.atmosDecoders) Atmos.IN_STREAM_DECODER_PRESENT else Atmos.IN_STREAM_NOT_RENDERED
            }
    }

    private val _report = MutableStateFlow(Report())
    val report: StateFlow<Report> = _report.asStateFlow()

    /** What each queued item's source announced, by media id. */
    private val announced = ConcurrentHashMap<String, Announced>()

    /** Whether the song goes through Square's mixer; set by the player factory. */
    @Volatile
    var mixed: () -> Boolean = { false }

    @Volatile
    var mixerRateHz: Int = 48_000

    fun announce(mediaId: String, value: Announced) {
        announced[mediaId] = value
        if (announced.size > 64) announced.keys.firstOrNull()?.let { announced.remove(it) }
        if (_report.value.mediaId == mediaId) _report.value = _report.value.copy(announced = value)
    }

    fun onItem(mediaId: String?) {
        _report.value = Report(
            mediaId = mediaId,
            announced = mediaId?.let { announced[it] },
        )
    }

    /** Called where the mixer's resampler is configured: the PCM as it reaches the mixer. */
    @OptIn(UnstableApi::class)
    fun onChainConfigured(format: AudioProcessor.AudioFormat) {
        val bits = when (format.encoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
            C.ENCODING_PCM_FLOAT -> 32
            C.ENCODING_PCM_8BIT -> 8
            else -> null
        }
        _report.value = _report.value.copy(
            delivered = Delivered(
                sampleRateHz = format.sampleRate.takeIf { it > 0 },
                channels = format.channelCount.takeIf { it > 0 },
                bitDepth = bits,
                float = format.encoding == C.ENCODING_PCM_FLOAT,
            ),
            mixerRateHz = if (mixed()) mixerRateHz else null,
        )
    }

    @OptIn(UnstableApi::class)
    val listener: AnalyticsListener = object : AnalyticsListener {
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            val bits = when (format.pcmEncoding) {
                C.ENCODING_PCM_16BIT -> 16
                C.ENCODING_PCM_24BIT -> 24
                C.ENCODING_PCM_32BIT -> 32
                C.ENCODING_PCM_8BIT -> 8
                else -> null
            }
            val bitrate = listOf(format.averageBitrate, format.bitrate, format.peakBitrate)
                .firstOrNull { it != Format.NO_VALUE && it > 0 }?.let { it / 1000 }
            val current = _report.value
            _report.value = current.copy(
                received = Received(
                    mimeType = format.sampleMimeType,
                    codecs = format.codecs,
                    sampleRateHz = format.sampleRate.takeIf { it != Format.NO_VALUE },
                    channels = format.channelCount.takeIf { it != Format.NO_VALUE },
                    bitDepth = bits,
                    bitrateKbps = bitrate,
                    decoderName = current.received?.decoderName,
                ),
            )
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            val current = _report.value
            _report.value = current.copy(
                received = (current.received ?: Received(null, null, null, null, null, null, null))
                    .copy(decoderName = decoderName),
            )
        }
    }

    private val MIMES = mapOf(
        "flac" to listOf(MimeTypes.AUDIO_FLAC),
        "alac" to listOf(MimeTypes.AUDIO_ALAC),
        "wav" to listOf(MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_WAV),
        "aiff" to listOf(MimeTypes.AUDIO_RAW),
        "mp3" to listOf(MimeTypes.AUDIO_MPEG),
        "aac" to listOf(MimeTypes.AUDIO_AAC),
        "he-aac" to listOf(MimeTypes.AUDIO_AAC),
        "m4a" to listOf(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_ALAC),
        "mp4" to listOf(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_ALAC),
        "opus" to listOf(MimeTypes.AUDIO_OPUS),
        "ogg" to listOf(MimeTypes.AUDIO_VORBIS, MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_FLAC),
        "vorbis" to listOf(MimeTypes.AUDIO_VORBIS),
        "webm" to listOf(MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_VORBIS),
        "eac3-joc" to listOf(MimeTypes.AUDIO_E_AC3_JOC),
        "ec3-joc" to listOf(MimeTypes.AUDIO_E_AC3_JOC),
    )

    private val LOSSLESS_MIMES = setOf(
        MimeTypes.AUDIO_FLAC.lowercase(Locale.ROOT),
        MimeTypes.AUDIO_ALAC.lowercase(Locale.ROOT),
        MimeTypes.AUDIO_RAW.lowercase(Locale.ROOT),
        MimeTypes.AUDIO_WAV.lowercase(Locale.ROOT),
    )
}
