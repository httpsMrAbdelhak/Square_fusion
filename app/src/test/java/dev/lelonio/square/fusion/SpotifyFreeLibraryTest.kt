// Ported from BitChord (GPL-3.0), shared/src/jvmTest/.../spotify/SpotifyLibraryTest.kt,
// with Square Fusion's tests of the HTTP failure paths added below.
package dev.lelonio.square.fusion.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpotifyFreeLibraryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun playlistsIncludeFolderLeaves() {
        val root = json.parseToJsonElement(
            """
            {"data":{"me":{"libraryV3":{"totalCount":1,"items":[
              {"item":{"__typename":"PlaylistResponseWrapper","_uri":"spotify:playlist:abc","data":{
                "__typename":"Playlist","name":"Road","ownerV2":{"data":{"name":"Amit"}},
                "images":{"items":[{"sources":[{"url":"https://i.scdn.co/x"}]}]}
              }}}
            ]}}}}
            """.trimIndent(),
        ).jsonObject
        val (items, total) = parsePlaylistPage(root)
        assertEquals(1, total)
        assertEquals("abc", items.single().id)
        assertEquals("Road", items.single().name)
        assertEquals("Amit", items.single().owner)
        assertEquals("https://i.scdn.co/x", items.single().imageUrl)
    }

    @Test
    fun tracksReadTitleArtistAndDuration() {
        val root = json.parseToJsonElement(
            """
            {"data":{"playlistV2":{"content":{"totalCount":1,"items":[
              {"itemV2":{"data":{
                "name":"Song","uri":"spotify:track:t1",
                "artists":{"items":[{"uri":"spotify:artist:a","profile":{"name":"Artist"}}]},
                "albumOfTrack":{"name":"Album","coverArt":{"sources":[{"url":"https://img"}]}},
                "duration":{"totalMilliseconds":180000}
              }}}
            ]}}}}
            """.trimIndent(),
        ).jsonObject
        val track = parseTrackPage(root).first.single()
        assertEquals("t1", track.id)
        assertEquals("Song", track.title)
        assertEquals("Artist", track.artist)
        assertEquals("Album", track.album)
        assertEquals(180000, track.durationMs)
        assertEquals("https://img", track.imageUrl)
    }

    private fun answering(code: Int, body: String, seen: MutableList<okhttp3.Request> = mutableListOf()) =
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            seen += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("x")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }).build()

    @Test
    fun playlistsSendTheTokensAndStartWithLikedSongs() = runBlocking {
        val seen = mutableListOf<okhttp3.Request>()
        SpotifyHttp.client = answering(
            200,
            """{"data":{"me":{"libraryV3":{"totalCount":0,"items":[]}}}}""",
            seen,
        )
        SpotifyFreeLibrary.auth = SpotifyFreeLibrary.Auth { SpotifyFreeLibrary.Tokens("bearer-1", "client-1") }
        val lists = SpotifyFreeLibrary.playlists()
        assertEquals(SpotifyFreeLibrary.LIKED_ID, lists.first().id)
        assertEquals("Bearer bearer-1", seen.single().header("Authorization"))
        assertEquals("client-1", seen.single().header("Client-Token"))
    }

    @Test
    fun refusedSessionIsReportedAsExpired() = runBlocking {
        SpotifyHttp.client = answering(401, "{}")
        SpotifyFreeLibrary.auth = SpotifyFreeLibrary.Auth { SpotifyFreeLibrary.Tokens("old", null) }
        try {
            SpotifyFreeLibrary.playlists()
            fail("expected a failure")
        } catch (e: SpotifyHttpException) {
            assertTrue(e.sessionExpired)
        }
    }

    @Test
    fun serverErrorIsNotAnExpiredSession() = runBlocking {
        SpotifyHttp.client = answering(503, "{}")
        SpotifyFreeLibrary.auth = SpotifyFreeLibrary.Auth { SpotifyFreeLibrary.Tokens("t", null) }
        try {
            SpotifyFreeLibrary.playlists()
            fail("expected a failure")
        } catch (e: SpotifyHttpException) {
            assertEquals(503, e.code)
            assertTrue(!e.sessionExpired)
        }
    }

    @Test
    fun noTokenMeansSignInExpired() = runBlocking {
        SpotifyFreeLibrary.auth = SpotifyFreeLibrary.Auth { null }
        try {
            SpotifyFreeLibrary.playlists()
            fail("expected a failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("sign-in expired"))
        }
    }

    @Test
    fun changedResponseShapeIsAnErrorNotACrash() = runBlocking {
        SpotifyHttp.client = answering(200, """{"data":{"somethingElse":{}}}""")
        SpotifyFreeLibrary.auth = SpotifyFreeLibrary.Auth { SpotifyFreeLibrary.Tokens("t", null) }
        try {
            SpotifyFreeLibrary.playlists()
            fail("expected a failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("empty"))
        }
        // GraphQL errors are surfaced by their message.
        SpotifyHttp.client = answering(200, """{"errors":[{"message":"PersistedQueryNotFound"}]}""")
        try {
            SpotifyFreeLibrary.playlists()
            fail("expected a failure")
        } catch (e: IllegalStateException) {
            assertEquals("PersistedQueryNotFound", e.message)
        }
    }
}
