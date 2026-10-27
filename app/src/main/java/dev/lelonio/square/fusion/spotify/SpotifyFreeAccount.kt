// Square Fusion's account wrapper around the Spotify mechanism ported from
// BitChord (GPL-3.0): auth/SpotifyLoginScreen.kt, data/canvas/SpotifyToken.kt
// and data/spotify/SpotifyLibrary.kt.
package dev.lelonio.square.fusion.spotify

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.lelonio.square.fusion.FusionLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A Spotify account — free or Premium — used for its library only: playlists,
 * liked songs, titles, artists and covers. Playback stays on YouTube Music.
 *
 * How it works (BitChord's mechanism): the user signs in on Spotify's own
 * login page, in a WebView, so this app never sees the password. The page's
 * `sp_dc` session cookie is kept (encrypted on the phone), and the web
 * player's own short-lived access token is read from a hidden WebView loading
 * open.spotify.com. Library calls go to the web player's private GraphQL API.
 *
 * Separate from Square's own Spotify engine (librespot, Premium only) and from
 * its Web API setup: nothing here reads or writes their accounts.
 *
 * Every failure is caught and turned into a [Failure] for the screen; nothing
 * here can take the app down.
 */
object SpotifyFreeAccount {

    sealed interface Failure {
        /** The cookie or token was refused: sign in again. */
        data object SessionExpired : Failure

        /** The network did not answer. */
        data class Network(val detail: String) : Failure

        /** Spotify answered with something this code does not understand: the private API changed. */
        data class Protocol(val detail: String) : Failure
    }

    private var prefs: SharedPreferences? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Safari/537.36"

    fun log(message: String) = FusionLog.d("Spotify: $message")

    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        prefs = runCatching {
            EncryptedSharedPreferences.create(
                app,
                FILE,
                MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.onFailure { FusionLog.w("Spotify cookie store unavailable: ${it.message}") }.getOrNull()
        _connected.value = cookie().isNotBlank()
        SpotifyFreeToken.init(app)
        SpotifyFreeLibrary.auth = SpotifyFreeLibrary.Auth {
            SpotifyFreeToken.accessToken()?.let { SpotifyFreeLibrary.Tokens(it, SpotifyFreeToken.clientToken()) }
        }
    }

    fun cookie(): String = prefs?.getString(KEY_COOKIE, null).orEmpty()

    /** Called by the login screen with the `sp_dc` cookie from Spotify's own page. */
    fun connect(spDc: String) {
        prefs?.edit()?.putString(KEY_COOKIE, spDc)?.apply()
        SpotifyFreeToken.invalidate()
        _connected.value = spDc.isNotBlank()
    }

    /**
     * Forgets the session: the stored cookie, the tokens, and the `sp_dc`
     * cookie in the WebView, so the next sign-in starts at Spotify's login.
     */
    fun disconnect() {
        prefs?.edit()?.remove(KEY_COOKIE)?.apply()
        SpotifyFreeToken.invalidate()
        _connected.value = false
        runCatching {
            val cookies = CookieManager.getInstance()
            listOf("https://open.spotify.com", "https://accounts.spotify.com", "https://www.spotify.com").forEach { url ->
                val expired = "sp_dc=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
                cookies.setCookie(url, expired)
                cookies.setCookie(url, "$expired; Domain=.spotify.com")
            }
            cookies.flush()
        }
    }

    /** Runs a library call, turning every way it can fail into a [Failure]. */
    suspend fun <T> call(requireAccount: Boolean = true, block: suspend () -> T): Result<T> = try {
        if (requireAccount && cookie().isBlank()) {
            Result.failure(FailureException(Failure.SessionExpired))
        } else {
            Result.success(block())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SpotifyHttpException) {
        if (e.sessionExpired) SpotifyFreeToken.invalidate()
        Result.failure(FailureException(if (e.sessionExpired) Failure.SessionExpired else Failure.Protocol(e.message.orEmpty())))
    } catch (e: IOException) {
        Result.failure(FailureException(Failure.Network(e.message ?: e.javaClass.simpleName)))
    } catch (e: Throwable) {
        val message = e.message.orEmpty()
        val failure = if (message.contains("sign-in expired", ignoreCase = true)) {
            Failure.SessionExpired
        } else {
            Failure.Protocol(message.ifBlank { e.javaClass.simpleName })
        }
        FusionLog.w("Spotify call failed: ${e.javaClass.simpleName}: $message")
        Result.failure(FailureException(failure))
    }

    class FailureException(val failure: Failure) : Exception(failure.toString())

    private const val FILE = "square_fusion_spotify"
    private const val KEY_COOKIE = "sp_dc"
}
