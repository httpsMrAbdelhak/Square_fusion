package dev.lelonio.square.fusion

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The quality tiers a source is asked for. Square's own YouTube setting
 * (automatic, high, low) is untouched and still decides YouTube's stream;
 * these decide what the addon sources are asked for.
 */
enum class SourceTier(val key: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    LOSSLESS("lossless"),
}

/**
 * Square Fusion's own settings, in a file of their own: nothing here reads or
 * writes Square's preferences, and every default keeps Square's behaviour.
 */
class FusionSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun tier(key: String, default: SourceTier) =
        SourceTier.entries.firstOrNull { it.key == prefs.getString(key, null) } ?: default

    private val _wifiTier = MutableStateFlow(tier(KEY_WIFI, SourceTier.HIGH))
    val wifiTier: StateFlow<SourceTier> = _wifiTier.asStateFlow()

    private val _mobileTier = MutableStateFlow(tier(KEY_MOBILE, SourceTier.MEDIUM))
    val mobileTier: StateFlow<SourceTier> = _mobileTier.asStateFlow()

    private val _normalization = MutableStateFlow(prefs.getBoolean(KEY_NORMALIZATION, false))
    val normalization: StateFlow<Boolean> = _normalization.asStateFlow()

    /** Off: Square's own refresh-rate behaviour, untouched. */
    private val _chooseRefreshRate = MutableStateFlow(prefs.getBoolean(KEY_CHOOSE_RATE, false))
    val chooseRefreshRate: StateFlow<Boolean> = _chooseRefreshRate.asStateFlow()

    private val _refreshRateHz = MutableStateFlow(prefs.getInt(KEY_RATE_HZ, 60))
    val refreshRateHz: StateFlow<Int> = _refreshRateHz.asStateFlow()

    fun setWifiTier(value: SourceTier) {
        _wifiTier.value = value
        prefs.edit().putString(KEY_WIFI, value.key).apply()
    }

    fun setMobileTier(value: SourceTier) {
        _mobileTier.value = value
        prefs.edit().putString(KEY_MOBILE, value.key).apply()
    }

    fun setNormalization(value: Boolean) {
        _normalization.value = value
        prefs.edit().putBoolean(KEY_NORMALIZATION, value).apply()
    }

    fun setChooseRefreshRate(value: Boolean) {
        _chooseRefreshRate.value = value
        prefs.edit().putBoolean(KEY_CHOOSE_RATE, value).apply()
    }

    fun setRefreshRateHz(value: Int) {
        _refreshRateHz.value = value
        prefs.edit().putInt(KEY_RATE_HZ, value).apply()
    }

    private companion object {
        const val FILE = "square_fusion"
        const val KEY_WIFI = "source_tier_wifi"
        const val KEY_MOBILE = "source_tier_mobile"
        const val KEY_NORMALIZATION = "normalization"
        const val KEY_CHOOSE_RATE = "choose_refresh_rate"
        const val KEY_RATE_HZ = "refresh_rate_hz"
    }
}
