package dev.lelonio.square.fusion

import dev.lelonio.square.fusion.audio.LosslessOutput
import dev.lelonio.square.fusion.audio.Normalization
import dev.lelonio.square.fusion.audio.StreamInspector
import dev.lelonio.square.fusion.jiosaavn.JioSaavnService
import dev.lelonio.square.fusion.jiosaavn.selectBestSaavnStream
import dev.lelonio.square.fusion.sources.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class FusionAudioTest {

    // ── Normalisation ───────────────────────────────────────────────────

    @Test
    fun loudnessAboveReferenceIsAttenuatedAndQuietIsLeftAlone() {
        assertEquals(1f, Normalization.levelForDb(-4.0), 0f)
        assertEquals(1f, Normalization.levelForDb(0.0), 0f)
        assertEquals(0.5012f, Normalization.levelForDb(6.0), 0.001f)
        // Never below -20 dB, whatever the value says.
        assertEquals(0.1f, Normalization.levelForDb(60.0), 0f)
    }

    @Test
    fun normalisationOffMeansUnitLevel() {
        Normalization.enabled = { false }
        assertEquals(1f, Normalization.levelFor("abc"), 0f)
        assertEquals(1f, Normalization.levelFor(null), 0f)
    }

    // ── Lossless output ─────────────────────────────────────────────────

    @Test
    fun outputsAreClassifiedHonestly() {
        val a2dp = 8
        assertTrue(LosslessOutput.decide(setOf(4), null).capable) // wired headphones
        assertTrue(LosslessOutput.decide(setOf(22), null).capable) // USB headset
        assertFalse(LosslessOutput.decide(setOf(2), null).capable) // speaker
        val unknown = LosslessOutput.decide(setOf(a2dp), null)
        assertFalse(unknown.capable)
        assertTrue(unknown.bluetoothCodecUnknown)
        assertTrue(LosslessOutput.decide(setOf(a2dp), "LDAC").capable)
        assertFalse(LosslessOutput.decide(setOf(a2dp), "AAC").capable)
        assertFalse(LosslessOutput.decide(setOf(a2dp), "aptX HD").capable)
    }

    // ── Stream verification ─────────────────────────────────────────────

    @Test
    fun announcedAndReceivedAreCompared() {
        val announced = StreamInspector.Announced("Addon", StreamFormat(codec = "flac", sampleRateHz = 96_000, bitDepth = 24))
        val received = StreamInspector.Received("audio/flac", null, 96_000, 2, 24, null, "c2.android.flac.decoder")
        val match = StreamInspector.Report(announced = announced, received = received)
        assertEquals(StreamInspector.Verdict.MATCHES, match.codecVerdict)
        assertEquals(StreamInspector.Verdict.MATCHES, match.sampleRateVerdict)
        assertEquals(true, match.receivedLossless)

        val lie = StreamInspector.Report(
            announced = announced,
            received = received.copy(mimeType = "audio/mpeg", sampleRateHz = 44_100, bitDepth = null),
        )
        assertEquals(StreamInspector.Verdict.DIFFERS, lie.codecVerdict)
        assertEquals(StreamInspector.Verdict.DIFFERS, lie.sampleRateVerdict)
        assertEquals(StreamInspector.Verdict.UNKNOWN, lie.bitDepthVerdict)
        assertEquals(false, lie.receivedLossless)

        val nothing = StreamInspector.Report()
        assertEquals(StreamInspector.Verdict.UNKNOWN, nothing.codecVerdict)
        assertNull(nothing.receivedLossless)
        assertEquals(StreamInspector.Atmos.NONE, nothing.atmos)
    }

    // ── JioSaavn ────────────────────────────────────────────────────────

    private fun encrypt(url: String): String {
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("38346591".toByteArray(), "DES"))
        return Base64.getEncoder().encodeToString(cipher.doFinal(url.toByteArray()))
    }

    @Test
    fun saavnPicks320WhenOffered() {
        assertEquals(
            "https://aac.saavncdn.com/x_320.mp4",
            selectBestSaavnStream("https://aac.saavncdn.com/x_96.mp4", true)?.url,
        )
        assertEquals(96, selectBestSaavnStream("https://aac.saavncdn.com/x_96.mp4", false)?.kbps)
        assertNull(selectBestSaavnStream("", true))
    }

    @Test
    fun saavnServiceParsesAndSurvivesApiChanges() {
        val server = TestServer()
        try {
            val service = JioSaavnService(server.url("/api.php"))
            server.queue.add(
                TestServer.Reply(body =
                    """{"results":[{"id":"s1","title":"Song","image":"http://c/150x150.jpg",
                       "more_info":{"album":"Al","duration":"201","artistMap":{"primary_artists":[{"name":"Art"}]}}}]}""",
                ),
            )
            val rows = service.searchSongs("song")
            assertEquals("s1", rows.single().id)
            assertEquals("Art", rows.single().moreInfo.artistMap.primaryArtists.single().name)
            // No spoofed location header is sent.
            val sent = server.seen.single()
            assertNull(sent.headers["x-forwarded-for"])
            assertEquals("search.getResults", sent.query["__call"])

            server.queue.add(
                TestServer.Reply(body =
                    """{"s1":{"id":"s1","more_info":{"encrypted_media_url":"${encrypt("https://aac.saavncdn.com/s1_160.mp4")}","320kbps":"true"}}}""",
                ),
            )
            assertEquals("https://aac.saavncdn.com/s1_320.mp4", service.streamFor("s1")?.url)

            // An API change, an outage, garbage: empty, never a crash.
            server.queue.add(TestServer.Reply(body = """{"unexpected":true}"""))
            assertTrue(service.searchSongs("x").isEmpty())
            server.queue.add(TestServer.Reply(500))
            assertTrue(service.searchSongs("x").isEmpty())
            server.queue.add(TestServer.Reply(body = "<html>"))
            assertNull(service.streamFor("s1"))
        } finally {
            server.shutdown()
        }
    }
}
