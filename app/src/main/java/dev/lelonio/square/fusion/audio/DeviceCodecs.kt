// Adapted from BitChord (GPL-3.0), data/sources/DeviceCodecs.kt.
package dev.lelonio.square.fusion.audio

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import dev.lelonio.square.fusion.FusionLog

/**
 * Which immersive-audio decoders this phone actually has.
 *
 * Read from the codec list, never assumed: a phone without an E-AC-3 JOC
 * decoder cannot render Dolby Atmos whatever a source says, and a failure to
 * read the list is "no", not "yes".
 */
object DeviceCodecs {

    /** Decoder names for E-AC-3 JOC (Dolby Atmos in Dolby Digital Plus). */
    val atmosDecoders: List<String> by lazy { decoders("audio/eac3-joc") }

    /** Decoder names for plain E-AC-3. */
    val eac3Decoders: List<String> by lazy { decoders("audio/eac3") }

    val playsDolbyAtmos: Boolean get() = atmosDecoders.isNotEmpty()

    @OptIn(UnstableApi::class)
    private fun decoders(mime: String): List<String> = runCatching {
        MediaCodecUtil.getDecoderInfos(mime, false, false).map { it.name }.distinct()
    }.onSuccess {
        FusionLog.d("$mime decoders: ${it.ifEmpty { listOf("none") }}")
    }.getOrElse {
        FusionLog.w("could not read decoders for $mime: ${it.message}")
        emptyList()
    }
}
