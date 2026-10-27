// Adapted from BitChord (GPL-3.0), playback/audio/LosslessOutput.kt and
// playback/audio/bluetooth/BluetoothAudioTracker.kt, much reduced.
package dev.lelonio.square.fusion.audio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.lelonio.square.fusion.FusionLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Whether the phone is connected to an output that can carry high-resolution
 * audio, for addons that declare `checkValidLossless`.
 *
 * Cabled outputs (wired, USB, HDMI, dock) count. Bluetooth counts only when
 * its codec is named and is LDAC, LHDC or aptX Lossless — and reading the
 * codec needs the "Nearby devices" permission from Android 12 on. Without it,
 * or on a phone that does not expose the codec, the codec is reported
 * *unknown* and the output is not counted, rather than guessed.
 *
 * Note that LDAC and LHDC are themselves lossy codecs at their usual bitrates;
 * they are accepted because `checkValidLossless` addons ask for them, and the
 * screen says what the codec is rather than calling it lossless.
 */
object LosslessOutput {

    enum class Kind { WIRED, USB, HDMI, DOCK, BLUETOOTH }

    data class State(
        val capable: Boolean = false,
        val via: Kind? = null,
        val bluetoothConnected: Boolean = false,
        val bluetoothCodec: String? = null,
        /** Bluetooth is connected but its codec could not be read. */
        val bluetoothCodecUnknown: Boolean = false,
        val needsPermission: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val capable: Boolean get() = _state.value.capable

    private var app: Context? = null
    private var audioManager: AudioManager? = null
    @Volatile private var a2dp: BluetoothA2dp? = null

    // Lazy, so the object can be loaded in JVM tests, where Android's classes are stubs.
    private val callback by lazy {
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
        }
    }

    fun init(context: Context) {
        if (audioManager != null) return
        val appContext = context.applicationContext
        app = appContext
        val manager = appContext.getSystemService(AudioManager::class.java) ?: return
        audioManager = manager
        runCatching { manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper())) }
        connectA2dp()
        refresh()
    }

    fun hasBluetoothPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** After the permission is granted. */
    fun onPermissionChanged() {
        connectA2dp()
        refresh()
    }

    @SuppressLint("MissingPermission")
    private fun connectA2dp() {
        val context = app ?: return
        if (a2dp != null || !hasBluetoothPermission(context)) return
        runCatching {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    a2dp = proxy as? BluetoothA2dp
                    refresh()
                }

                override fun onServiceDisconnected(profile: Int) {
                    a2dp = null
                    refresh()
                }
            }, BluetoothProfile.A2DP)
        }.onFailure { FusionLog.w("A2DP proxy unavailable: ${it.message}") }
    }

    fun refresh() {
        val outputs = runCatching {
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
                .filter { it.isSink }.map { it.type }.toSet()
        }.getOrDefault(emptySet())
        val context = app
        val permitted = context != null && hasBluetoothPermission(context)
        val codec = if (AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in outputs && permitted) bluetoothCodec() else null
        val next = decide(outputs, codec).let {
            if (it.bluetoothConnected && !permitted) it.copy(needsPermission = true) else it
        }
        if (next != _state.value) {
            FusionLog.d("output: $next")
            _state.value = next
        }
    }

    /** The active A2DP codec's name, read through `getCodecStatus`, or null when not readable. */
    @SuppressLint("MissingPermission")
    private fun bluetoothCodec(): String? = runCatching {
        val proxy = a2dp ?: return null
        val device: BluetoothDevice = proxy.connectedDevices.firstOrNull() ?: return null
        val status = proxy.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java).invoke(proxy, device)
            ?: return null
        val config = status.javaClass.getMethod("getCodecConfig").invoke(status) ?: return null
        val named = if (Build.VERSION.SDK_INT >= 35) {
            runCatching { config.javaClass.getMethod("getCodecName").invoke(config) as? String }.getOrNull()
        } else {
            null
        }
        named ?: codecTypeName(config.javaClass.getMethod("getCodecType").invoke(config) as? Int ?: -1)
    }.getOrElse {
        FusionLog.w("Bluetooth codec not readable: ${it.message}")
        null
    }

    private fun codecTypeName(type: Int): String? = when (type) {
        0 -> "SBC"
        1 -> "AAC"
        2 -> "aptX"
        3 -> "aptX HD"
        4 -> "LDAC"
        5 -> "LC3"
        6 -> "Opus"
        else -> null
    }

    /** Pure, for tests. */
    internal fun decide(outputTypes: Set<Int>, bluetoothCodec: String?): State {
        val cabled = when {
            outputTypes.any { it in WIRED } -> Kind.WIRED
            outputTypes.any { it in USB } -> Kind.USB
            outputTypes.any { it in HDMI } -> Kind.HDMI
            outputTypes.any { it in DOCK } -> Kind.DOCK
            else -> null
        }
        val bluetooth = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in outputTypes
        if (cabled != null) return State(capable = true, via = cabled, bluetoothConnected = bluetooth, bluetoothCodec = bluetoothCodec)
        if (!bluetooth) return State()
        if (bluetoothCodec == null) return State(bluetoothConnected = true, bluetoothCodecUnknown = true)
        val hiRes = isHiResBluetoothCodec(bluetoothCodec)
        return State(
            capable = hiRes,
            via = Kind.BLUETOOTH.takeIf { hiRes },
            bluetoothConnected = true,
            bluetoothCodec = bluetoothCodec,
        )
    }

    internal fun isHiResBluetoothCodec(name: String): Boolean {
        val codec = name.uppercase(Locale.ROOT)
        return "LDAC" in codec || "LHDC" in codec || ("APTX" in codec && "LOSSLESS" in codec)
    }

    // Literal values: some constants are newer than minSdk, and the values are fixed by the platform.
    private val WIRED = setOf(3, 4, 5, 6, 19) // WIRED_HEADSET, WIRED_HEADPHONES, LINE_ANALOG, LINE_DIGITAL, AUX_LINE
    private val USB = setOf(11, 12, 22) // USB_DEVICE, USB_ACCESSORY, USB_HEADSET
    private val HDMI = setOf(9, 10, 29) // HDMI, HDMI_ARC, HDMI_EARC
    private val DOCK = setOf(13, 31) // DOCK, DOCK_ANALOG
}
