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
        _added.value = readAdded()
    }

    // ── Songs added in Square to a playlist whose source cannot take them ──

    private val _added = MutableStateFlow<Map<String, List<dev.lelonio.square.data.CatalogTrack>>>(emptyMap())

    /** By playlist URI, in the order they were added. Shown after the source's own songs. */
    val added: StateFlow<Map<String, List<dev.lelonio.square.data.CatalogTrack>>> = _added.asStateFlow()

    /** Adds [track] to the end of [uri]'s songs kept in Square; false when it is already there. */
    @Synchronized
    fun addTrack(uri: String, track: dev.lelonio.square.data.CatalogTrack): Boolean {
        val now = _added.value[uri].orEmpty()
        if (now.any { it.uri == track.uri }) return false
        val next = now + track
        _added.value = _added.value + (uri to next)
        prefs?.edit()?.putString(KEY_ADDED + uri, encode(next))?.apply()
        return true
    }

    /**
     * Fills in the songs of [uri] kept in Square from [found], the same songs as
     * their source lists them now: same URI, same place in the list.
     */
    @Synchronized
    fun refreshTracks(uri: String, found: List<dev.lelonio.square.data.CatalogTrack>) {
        val now = _added.value[uri] ?: return
        val byUri = found.associateBy { it.uri }
        val next = now.map { old -> byUri[old.uri]?.let { fresh -> merged(old, fresh) } ?: old }
        if (next == now) return
        _added.value = _added.value + (uri to next)
        prefs?.edit()?.putString(KEY_ADDED + uri, encode(next))?.apply()
    }

    /** [old] with what it lacks taken from [fresh]; nothing it has is replaced by an empty value. */
    private fun merged(old: dev.lelonio.square.data.CatalogTrack, fresh: dev.lelonio.square.data.CatalogTrack) =
        old.copy(
            name = old.name.ifBlank { fresh.name },
            artist = old.artist.ifBlank { fresh.artist },
            artistUri = old.artistUri ?: fresh.artistUri,
            artists = old.artists.ifEmpty { fresh.artists },
            album = old.album.ifBlank { fresh.album },
            albumUri = old.albumUri ?: fresh.albumUri,
            durationMs = old.durationMs.takeIf { it > 0 } ?: fresh.durationMs,
            explicit = old.explicit || fresh.explicit,
            artworkUrl = old.artworkUrl ?: fresh.artworkUrl,
        )

    /** Whether [track] lacks what its row and the player show. */
    fun incomplete(track: dev.lelonio.square.data.CatalogTrack) =
        track.artist.isBlank() || track.artworkUrl == null || track.durationMs <= 0

    private fun encode(tracks: List<dev.lelonio.square.data.CatalogTrack>): String =
        org.json.JSONArray().apply {
            tracks.forEach {
                put(
                    org.json.JSONObject()
                        .put("uri", it.uri).put("name", it.name).put("artist", it.artist)
                        .put("album", it.album).put("duration", it.durationMs).put("art", it.artworkUrl)
                        .put("artistUri", it.artistUri).put("albumUri", it.albumUri).put("explicit", it.explicit)
                        .put(
                            "artists",
                            org.json.JSONArray().apply {
                                it.artists.forEach { a -> put(org.json.JSONObject().put("name", a.name).put("uri", a.uri)) }
                            },
                        ),
                )
            }
        }.toString()

    private fun readAdded(): Map<String, List<dev.lelonio.square.data.CatalogTrack>> =
        prefs?.all.orEmpty().filterKeys { it.startsWith(KEY_ADDED) }.mapNotNull { (key, value) ->
            val list = runCatching {
                val array = org.json.JSONArray(value as String)
                (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    dev.lelonio.square.data.CatalogTrack(
                        uri = o.getString("uri"),
                        name = o.optString("name"),
                        artist = o.optString("artist"),
                        album = o.optString("album"),
                        durationMs = o.optLong("duration"),
                        artworkUrl = if (o.isNull("art")) null else o.optString("art").ifBlank { null },
                        artistUri = if (o.isNull("artistUri")) null else o.optString("artistUri").ifBlank { null },
                        albumUri = if (o.isNull("albumUri")) null else o.optString("albumUri").ifBlank { null },
                        explicit = o.optBoolean("explicit"),
                        artists = o.optJSONArray("artists")?.let { list ->
                            (0 until list.length()).map { j ->
                                val a = list.getJSONObject(j)
                                dev.lelonio.square.data.CatalogArtist(
                                    name = a.optString("name"),
                                    uri = if (a.isNull("uri")) null else a.optString("uri").ifBlank { null },
                                )
                            }
                        }.orEmpty(),
                    )
                }
            }.getOrNull() ?: return@mapNotNull null
            key.removePrefix(KEY_ADDED) to list
        }.toMap()

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
        _added.value = _added.value - uri
        prefs?.edit()?.remove(KEY_ADDED + uri)?.apply()
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
    private const val KEY_ADDED = "added:"
}
