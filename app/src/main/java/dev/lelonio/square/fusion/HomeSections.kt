package dev.lelonio.square.fusion

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Square Fusion: which sections of the Home page are shown.
 *
 * The sections are YouTube Music's own shelves, under the titles it sends
 * ("Quick picks", "Covers and remixes"…), which differ by account, language
 * and country; so the list offered in the settings is the shelves this phone
 * has actually been sent, in the order last seen, plus the app's own
 * "Play again". Everything is shown until it is switched off.
 */
object HomeSections {
    /** The app's own row of recently played songs. */
    const val PLAY_AGAIN = "local:play_again"

    private var prefs: SharedPreferences? = null

    private val _seen = MutableStateFlow<List<String>>(emptyList())

    /** The shelf titles sent so far, the latest page's first. */
    val seen: StateFlow<List<String>> = _seen.asStateFlow()

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())

    /** The sections switched off: shelf titles, or [PLAY_AGAIN]. */
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        _seen.value = p.getString(KEY_SEEN, null)?.let(::decode).orEmpty()
        _hidden.value = p.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().toSet()
    }

    /** Remembers the titles of a page of shelves, so they can be switched off. */
    @Synchronized
    fun saw(titles: List<String>) {
        val fresh = titles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (fresh.isEmpty()) return
        val next = (fresh + _seen.value.filterNot { it in fresh }).take(MAX_SEEN)
        if (next == _seen.value) return
        _seen.value = next
        prefs?.edit()?.putString(KEY_SEEN, encode(next))?.apply()
    }

    @Synchronized
    fun setShown(section: String, shown: Boolean) {
        val next = if (shown) _hidden.value - section else _hidden.value + section
        if (next == _hidden.value) return
        _hidden.value = next
        prefs?.edit()?.putStringSet(KEY_HIDDEN, next)?.apply()
    }

    private fun encode(list: List<String>) = org.json.JSONArray(list).toString()

    private fun decode(raw: String): List<String> = runCatching {
        val array = org.json.JSONArray(raw)
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())

    private const val FILE = "square_fusion_home_sections"
    private const val KEY_SEEN = "seen"
    private const val KEY_HIDDEN = "hidden"
    private const val MAX_SEEN = 40
}
