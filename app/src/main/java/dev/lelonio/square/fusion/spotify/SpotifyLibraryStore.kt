package dev.lelonio.square.fusion.spotify

import android.content.Context
import dev.lelonio.square.fusion.FusionLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The Spotify library shown in Square's Library tab, kept on the phone.
 *
 * Two kinds of playlist: the connected account's own (its playlists, the ones
 * it follows, and its liked songs), refreshed from Spotify by [sync], and the
 * ones imported from a link, which stay until removed. Plus the artists the
 * account follows. Everything is written to a file, so the Library shows it at
 * once after a restart and while offline; a failed refresh keeps what was there.
 */
object SpotifyLibraryStore {

    const val PLAYLIST_PREFIX = "spotifree:playlist:"
    const val ARTIST_PREFIX = "spotifree:artist:"

    data class Playlist(
        val id: String,
        val name: String,
        val owner: String?,
        val imageUrl: String?,
        val imported: Boolean,
    ) {
        val uri: String get() = PLAYLIST_PREFIX + id
    }

    data class State(
        val playlists: List<Playlist> = emptyList(),
        val artists: List<SpotifyArtist> = emptyList(),
        val syncing: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val tracks = HashMap<String, List<SpotifyTrack>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        file = File(context.applicationContext.filesDir, "square_fusion_spotify_library.json")
        runCatching { load() }.onFailure { FusionLog.w("Spotify library cache unreadable: ${it.message}") }
        SpotifyMatches.init(context)
        scope.launch {
            SpotifyFreeAccount.connected.collect { connected -> if (connected) sync() else clearAccount() }
        }
    }

    fun playlistIdOf(uri: String): String? = uri.takeIf { it.startsWith(PLAYLIST_PREFIX) }?.removePrefix(PLAYLIST_PREFIX)

    fun playlist(id: String): Playlist? = _state.value.playlists.firstOrNull { it.id == id }

    fun cachedTracks(id: String): List<SpotifyTrack> = synchronized(tracks) { tracks[id].orEmpty() }

    /** The account's playlists and followed artists, from Spotify. Keeps the cache on failure. */
    suspend fun sync(): Result<Unit> = lock.withLock {
        _state.value = _state.value.copy(syncing = true)
        val result = SpotifyFreeAccount.call {
            val own = SpotifyFreeLibrary.playlists().map { Playlist(it.id, it.name, it.owner, it.imageUrl, imported = false) }
            val artists = runCatching { SpotifyFreeLibrary.followedArtists() }
                .onFailure { FusionLog.w("Spotify followed artists unavailable: ${it.message}") }
                .getOrDefault(_state.value.artists)
            val imported = _state.value.playlists.filter { it.imported && own.none { o -> o.id == it.id } }
            _state.value = State(playlists = own + imported, artists = artists)
            save()
        }
        _state.value = _state.value.copy(syncing = false)
        result.onFailure { FusionLog.w("Spotify library sync failed: ${it.message}") }
    }

    /**
     * The songs of a playlist, from Spotify: with the account when one is
     * connected, otherwise from the playlist's public page (see [read]).
     * Registered for matching on YouTube Music, and kept.
     */
    suspend fun loadTracks(id: String, onPage: (List<SpotifyTrack>) -> Unit = {}): Result<List<SpotifyTrack>> =
        SpotifyFreeAccount.call(requireAccount = id == SpotifyFreeLibrary.LIKED_ID) {
            val list = if (id == SpotifyFreeLibrary.LIKED_ID) SpotifyFreeLibrary.tracks(id, onPage) else read(id, onPage).second
            remember(id, list)
            list
        }

    /**
     * Imports a playlist from a Spotify link or URI: its name, owner, cover and
     * songs, in order. Public playlists import without an account; private
     * ones need the connected account to have access.
     */
    suspend fun import(link: String): Result<Playlist> {
        val id = SpotifyLinks.playlistId(link) ?: AppleMusicPlaylists.playlistId(link)
            ?: return Result.failure(IllegalArgumentException("Not a Spotify or Apple Music playlist link"))
        return SpotifyFreeAccount.call(requireAccount = false) {
            val (info, songs) = read(id)
            val playlist = Playlist(id, info.name, info.owner, info.imageUrl ?: songs.firstOrNull()?.imageUrl, imported = true)
            lock.withLock {
                val others = _state.value.playlists.filterNot { it.id == id }
                // An account playlist imported again stays the account's.
                val kept = _state.value.playlists.firstOrNull { it.id == id && !it.imported }
                _state.value = _state.value.copy(playlists = others + (kept ?: playlist))
                remember(id, songs)
            }
            FusionLog.i("imported playlist $id \"${info.name}\": ${songs.size} songs, first ${songs.firstOrNull()?.id}")
            playlist
        }
    }

    /**
     * A playlist and its songs. Through the connected account first; without
     * one, or when the account cannot read it, from the playlist's public
     * embed page, which needs neither an account nor a WebView. That page
     * lists at most [SpotifyEmbed.MAX_TRACKS] songs, so a playlist that
     * reaches it is read again in full with the page's anonymous token.
     */
    private suspend fun read(id: String, onPage: (List<SpotifyTrack>) -> Unit = {}): Pair<SpotifyPlaylist, List<SpotifyTrack>> {
        // Square Fusion: an Apple Music playlist, from its own public page.
        if (AppleMusicPlaylists.isApple(id)) return AppleMusicPlaylists.playlist(id).let { it.playlist to it.tracks }
        if (SpotifyFreeAccount.cookie().isNotBlank()) {
            try {
                return SpotifyFreeLibrary.playlistInfo(id) to SpotifyFreeLibrary.tracks(id, onPage)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                FusionLog.w("account could not read playlist $id (${e.message}); trying its public page")
            }
        }
        val embed = SpotifyEmbed.playlist(id)
        embed.token?.let { SpotifyFreeToken.seedAnonymous(it, embed.tokenExpiresAtMs, embed.clientId) }
        var songs = embed.tracks
        if (songs.size >= SpotifyEmbed.MAX_TRACKS && embed.token != null) {
            try {
                val full = SpotifyFreeLibrary.tracks(id)
                if (full.size > songs.size) songs = full
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                FusionLog.w("playlist $id: only the first ${songs.size} songs could be read (${e.message})")
            }
        }
        return SpotifyPlaylist(id, embed.name, embed.owner, embed.imageUrl) to songs
    }

    fun removeImported(id: String) {
        scope.launch {
            lock.withLock {
                _state.value = _state.value.copy(playlists = _state.value.playlists.filterNot { it.id == id && it.imported })
                dev.lelonio.square.fusion.PlaylistCustomizations.forget(PLAYLIST_PREFIX + id)
                synchronized(tracks) { tracks.remove(id) }
                save()
            }
        }
    }

    private fun remember(id: String, list: List<SpotifyTrack>) {
        synchronized(tracks) { tracks[id] = list }
        list.forEach {
            SpotifyMatches.register(
                it.id,
                SpotifyMatches.Target(it.title, it.artist, it.album, (it.durationMs / 1000).takeIf { s -> s > 0 }),
            )
        }
        save()
    }

    private suspend fun clearAccount() = lock.withLock {
        _state.value = State(playlists = _state.value.playlists.filter { it.imported })
        synchronized(tracks) { tracks.keys.retainAll(_state.value.playlists.map { it.id }.toSet()) }
        save()
    }

    private fun save() {
        val target = file ?: return
        val root = JSONObject()
        root.put("playlists", JSONArray().apply {
            _state.value.playlists.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("owner", it.owner).put("image", it.imageUrl).put("imported", it.imported))
            }
        })
        root.put("artists", JSONArray().apply {
            _state.value.artists.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("image", it.imageUrl)) }
        })
        root.put("tracks", JSONObject().apply {
            synchronized(tracks) {
                tracks.forEach { (id, list) ->
                    put(id, JSONArray().apply {
                        list.forEach {
                            put(JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist)
                                .put("album", it.album).put("duration", it.durationMs).put("image", it.imageUrl))
                        }
                    })
                }
            }
        })
        runCatching {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(target)
        }.onFailure { FusionLog.w("Spotify library cache not written: ${it.message}") }
    }

    private fun load() {
        val source = file?.takeIf { it.isFile } ?: return
        val root = JSONObject(source.readText())
        val playlists = root.optJSONArray("playlists")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Playlist(it.getString("id"), it.getString("name"), it.optStringOrNull("owner"), it.optStringOrNull("image"), it.optBoolean("imported"))
            }
        }.orEmpty()
        val artists = root.optJSONArray("artists")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                SpotifyArtist(it.getString("id"), it.getString("name"), it.optStringOrNull("image"))
            }
        }.orEmpty()
        root.optJSONObject("tracks")?.let { t ->
            t.keys().forEach { id ->
                val a = t.getJSONArray(id)
                tracks[id] = (0 until a.length()).map { a.getJSONObject(it) }.map {
                    SpotifyTrack(it.getString("id"), it.getString("title"), it.getString("artist"),
                        it.optStringOrNull("album"), it.optInt("duration"), it.optStringOrNull("image"))
                }
            }
        }
        _state.value = State(playlists, artists)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifBlank { null }
}

/** Spotify links and URIs, as people paste them. */
object SpotifyLinks {
    /** What a public Spotify page says it is: read from its own meta tags, no account needed. */
    data class PageInfo(val title: String, val artist: String, val imageUrl: String?, val durationSec: Int?)

    /**
     * The title, artist, picture and length of a Spotify track, album or
     * artist page, from the page's own Open Graph and music meta tags; null
     * when the page could not be read.
     */
    suspend fun pageInfo(kind: String, id: String): PageInfo? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val request = okhttp3.Request.Builder()
                .url("https://open.spotify.com/$kind/$id")
                .header("User-Agent", SpotifyFreeAccount.BROWSER_UA)
                .header("Accept-Language", "en")
                .build()
            val html = SpotifyFreeAccount.http.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
                ?: return@runCatching null
            fun meta(name: String) = Regex("""<meta[^>]+(?:property|name)="${Regex.escape(name)}"[^>]+content="([^"]*)"""")
                .find(html)?.groupValues?.get(1)?.let(::unescape)
            val title = meta("og:title")?.ifBlank { null } ?: return@runCatching null
            val description = meta("og:description").orEmpty()
            val artist = meta("music:musician_description")?.ifBlank { null }
                ?: description.split(" · ").firstOrNull().orEmpty()
            PageInfo(title, artist, meta("og:image"), meta("music:duration")?.toIntOrNull())
        }.getOrNull()
    }

    private fun unescape(text: String) = text.replace("&amp;", "&").replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")

    private val PLAYLIST = Regex("""(?:open\.spotify\.com/(?:intl-[a-z]{2,5}/|embed/)?playlist/|spotify:playlist:)([A-Za-z0-9]{22})""")
    private val ANY = Regex("""(?:open\.spotify\.com/(?:intl-[a-z]{2,5}/)?|spotify:)(track|album|artist|playlist)[/:]([A-Za-z0-9]{22})""")

    fun playlistId(link: String): String? = PLAYLIST.find(link.trim())?.groupValues?.get(1)

    /** (kind, id) of any Spotify link. */
    fun parse(link: String): Pair<String, String>? = ANY.find(link.trim())?.let { it.groupValues[1] to it.groupValues[2] }
}
