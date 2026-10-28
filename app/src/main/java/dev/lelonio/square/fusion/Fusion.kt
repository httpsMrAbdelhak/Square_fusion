package dev.lelonio.square.fusion

import android.content.Context

/**
 * Square Fusion's additions, set up once by SquareApplication: the refresh
 * rate setting and the Spotify library. Every entry point catches its own
 * failures, so Square's own paths keep behaving as they did.
 */
object Fusion {

    @Volatile
    private var app: Context? = null

    lateinit var settings: FusionSettings
        private set

    val initialised: Boolean get() = app != null

    @Synchronized
    fun init(context: Context) {
        if (app != null) return
        val appContext = context.applicationContext
        app = appContext
        settings = FusionSettings(appContext)
        runCatching { ProfilePhoto.init(appContext) }
        runCatching { PlaylistCustomizations.init(appContext) }
            .onFailure { FusionLog.w("playlist customizations unavailable: ${it.message}") }
        runCatching {
            dev.lelonio.square.fusion.spotify.SpotifyFreeAccount.init(appContext)
            dev.lelonio.square.fusion.spotify.SpotifyLibraryStore.init(appContext)
        }
            .onFailure { FusionLog.w("Spotify library unavailable: ${it.message}") }
    }
}
