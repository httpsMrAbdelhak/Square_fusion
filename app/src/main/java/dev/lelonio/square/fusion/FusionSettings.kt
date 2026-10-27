package dev.lelonio.square.fusion

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Square Fusion's own settings, in a file of their own: nothing here reads or
 * writes Square's preferences, and every default keeps Square's behaviour.
 */
class FusionSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Off: Square's own refresh-rate behaviour, untouched. */
    private val _chooseRefreshRate = MutableStateFlow(prefs.getBoolean(KEY_CHOOSE_RATE, false))
    val chooseRefreshRate: StateFlow<Boolean> = _chooseRefreshRate.asStateFlow()

    private val _refreshRateHz = MutableStateFlow(prefs.getInt(KEY_RATE_HZ, 60))
    val refreshRateHz: StateFlow<Int> = _refreshRateHz.asStateFlow()

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
        const val KEY_CHOOSE_RATE = "choose_refresh_rate"
        const val KEY_RATE_HZ = "refresh_rate_hz"
    }
}
