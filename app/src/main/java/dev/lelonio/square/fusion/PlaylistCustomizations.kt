package dev.lelonio.square.fusion

import android.content.Context
import android.content.SharedPreferences
import dev.lelonio.square.data.CatalogPlaylist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Square Fusion: a playlist's name and cover as chosen in Square, kept on the
 * phone and laid over what its source says.
 *
 * Never sent anywhere: a Spotify playlist given another cover here keeps its
 * own on Spotify. The source's data is left as it is underneath (the view
 * model applies this only to what it shows), so a sync still brings in the
 * songs, the source's own name and cover changes, and nothing it does can
 * overwrite the choice; taking the choice away shows the source's again.
 */
object PlaylistCustomizations {

    data class Custom(val name: String? = null, val cover: String? = null)

    private var prefs: SharedPreferences? = null
    private var dir: File? = null
    private val _all = MutableStateFlow<Map<String, Custom>>(emptyMap())

    /** Every playlist's choices, by URI; changes whenever one does. */
    val all: StateFlow<Map<String, Custom>> = _all.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        dir = File(app.filesDir, COVERS).apply { mkdirs() }
        _all.value = read()
    }

    fun get(uri: String?): Custom? = uri?.let { _all.value[it] }

    /** [playlist] as Square shows it. */
    fun apply(playlist: CatalogPlaylist): CatalogPlaylist {
        val custom = _all.value[playlist.uri] ?: return playlist
        return playlist.copy(
            name = custom.name ?: playlist.name,
            artworkUrl = custom.cover ?: playlist.artworkUrl,
        )
    }

    /** A name of Square's own, or null to show the source's again. */
    fun setName(uri: String, name: String?) = edit(uri) { it.copy(name = name?.trim()?.takeIf(String::isNotEmpty)) }

    /** A cover of Square's own, as a JPEG; replaces the one there was. */
    fun setCover(uri: String, jpeg: ByteArray) {
        val folder = dir ?: return
        // A new file name each time, so the image loader does not show the old one.
        val file = File(folder, "${uri.hashCode().toUInt()}-${System.currentTimeMillis()}.jpg")
        file.writeBytes(jpeg)
        val old = get(uri)?.cover
        edit(uri) { it.copy(cover = android.net.Uri.fromFile(file).toString()) }
        old?.let(::deleteCover)
    }

    /** Back to the source's cover. */
    fun clearCover(uri: String) {
        val old = get(uri)?.cover ?: return
        edit(uri) { it.copy(cover = null) }
        deleteCover(old)
    }

    /** Everything chosen for a playlist that is gone. */
    fun forget(uri: String) {
        get(uri)?.cover?.let(::deleteCover)
        edit(uri) { Custom() }
    }

    private fun deleteCover(address: String) {
        runCatching { android.net.Uri.parse(address).path?.let { File(it).delete() } }
    }

    @Synchronized
    private fun edit(uri: String, change: (Custom) -> Custom) {
        val now = change(_all.value[uri] ?: Custom())
        val next = if (now.name == null && now.cover == null) _all.value - uri else _all.value + (uri to now)
        _all.value = next
        prefs?.edit()?.apply {
            if (now.name != null) putString(KEY_NAME + uri, now.name) else remove(KEY_NAME + uri)
            if (now.cover != null) putString(KEY_COVER + uri, now.cover) else remove(KEY_COVER + uri)
        }?.apply()
    }

    private fun read(): Map<String, Custom> {
        val stored = prefs?.all.orEmpty()
        val uris = stored.keys.mapNotNull { key ->
            when {
                key.startsWith(KEY_NAME) -> key.removePrefix(KEY_NAME)
                key.startsWith(KEY_COVER) -> key.removePrefix(KEY_COVER)
                else -> null
            }
        }.toSet()
        return uris.associateWith { uri ->
            Custom(
                name = stored[KEY_NAME + uri] as? String,
                // A cover whose file is gone (storage cleared) is not shown as a blank.
                cover = (stored[KEY_COVER + uri] as? String)
                    ?.takeIf { address -> android.net.Uri.parse(address).path?.let { File(it).isFile } == true },
            )
        }.filterValues { it.name != null || it.cover != null }
    }

    private const val FILE = "square_fusion_playlist_custom"
    private const val COVERS = "playlist_covers"
    private const val KEY_NAME = "name:"
    private const val KEY_COVER = "cover:"
}
