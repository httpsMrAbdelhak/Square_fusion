package com.music.bitchord.glass.effects

import android.content.Context
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.WeakHashMap

/**
 * BitChord Glass: Square's audio effects on BitChord's players.
 *
 * Nothing in BitChord's engine is replaced. Tempo stays BitChord's own
 * playback-speed setting, which its service and crossfade already apply. What
 * Square adds is carried here: the pitch, kept on every player whatever sets
 * its speed, and Square's reverb, hung off each player as an aux effect.
 */
object GlassEffects {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val players = WeakHashMap<ExoPlayer, PlayerReverb>()
    private var started = false

    /** Called once per player BitChord builds. */
    fun attach(context: Context, player: ExoPlayer) {
        AudioEffects.load(context)
        start()
        val reverb = PlayerReverb()
        players[player] = reverb
        player.addListener(object : Player.Listener {
            override fun onPlaybackParametersChanged(params: PlaybackParameters) {
                val pitch = AudioEffects.pitch.value
                // BitChord sets speed alone (pitch 1); put the listener's pitch back.
                if (params.pitch != pitch) player.playbackParameters = PlaybackParameters(params.speed, pitch)
            }
        })
        applyPitch(player)
        reverb.apply(player, AudioEffects.reverb.value)
    }

    private fun start() {
        if (started) return
        started = true
        scope.launch {
            AudioEffects.pitch.collect { players.keys.toList().forEach(::applyPitch) }
        }
        scope.launch {
            AudioEffects.reverb.collect { amount ->
                players.entries.toList().forEach { (player, reverb) -> reverb.apply(player, amount) }
            }
        }
    }

    private fun applyPitch(player: ExoPlayer) {
        runCatching {
            val params = player.playbackParameters
            val pitch = AudioEffects.pitch.value
            if (params.pitch != pitch) player.playbackParameters = PlaybackParameters(params.speed, pitch)
        }
    }

    /** Speed is BitChord's setting; Square's controls write to it. */
    fun setSpeed(speed: Float) = AppSettings.setPlaybackSpeed(speed.coerceIn(0.5f, 2f))
    fun setPitch(pitch: Float) = AudioEffects.setPitch(pitch.coerceIn(0.5f, 2f))
    fun setReverb(amount: Float) = AudioEffects.setReverb(amount)

    fun apply(preset: EffectPreset) {
        setSpeed(preset.speed)
        setPitch(preset.pitch)
        setReverb(preset.reverbAmount)
    }
}
