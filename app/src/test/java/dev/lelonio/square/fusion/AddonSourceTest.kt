package dev.lelonio.square.fusion

import dev.lelonio.square.fusion.sources.MemorySourceStorage
import dev.lelonio.square.fusion.sources.MusicSource
import dev.lelonio.square.fusion.sources.SourceConfig
import dev.lelonio.square.fusion.sources.SourceHealth
import dev.lelonio.square.fusion.sources.SourceKind
import dev.lelonio.square.fusion.sources.SourceRegistry
import dev.lelonio.square.fusion.sources.SourceResolver
import dev.lelonio.square.fusion.sources.SourceStream
import dev.lelonio.square.fusion.sources.SourceTrack
import dev.lelonio.square.fusion.sources.StreamFormat
import dev.lelonio.square.fusion.sources.StreamRequest
import dev.lelonio.square.fusion.sources.TrackMatcher
import dev.lelonio.square.fusion.sources.addon.AddonClient
import dev.lelonio.square.fusion.sources.addon.AddonSource
import dev.lelonio.square.fusion.sources.addon.DetectedFormat
import dev.lelonio.square.fusion.sources.addon.SourceFormats
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The addon protocol against a real local HTTP server: valid answers,
 * invalid and partial ones, errors, timeouts, policy flags, ordering and the
 * fall back to YouTube.
 */
class AddonSourceTest {

    private lateinit var server: TestServer
    private val routes get() = server.routes
    private val seen get() = server.seen

    private val client = OkHttpClient.Builder()
        .readTimeout(1, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()

    @Before
    fun start() {
        server = TestServer()
    }

    @After
    fun stop() = server.shutdown()

    private fun base() = server.url("/addon")

    private fun json(body: String, code: Int = 200) = TestServer.Reply(code, body)

    private fun manifest(extra: String = "") = json(
        """{"id":"test.addon","name":"Test","version":"1.2","resources":["search","stream"]$extra}""",
    )

    private val searchBody = """
        {"tracks":[
          {"id":"t1","title":"Blinding Lights","artist":"The Weeknd","album":"After Hours","duration":200,"format":"flac","audioQuality":"LOSSLESS"},
          {"id":"t2","title":"Other Song","artist":"Someone","duration":180}
        ]}
    """.trimIndent()

    private fun source(config: SourceConfig = config(), lossless: Boolean = false, registry: SourceRegistry? = null) =
        AddonSource(
            config = config,
            registry = registry,
            client = AddonClient(config.baseUrl, client),
            losslessOutput = { lossless },
            atmosPlayable = { false },
        )

    private fun config(id: String = "a1") = SourceConfig(id = id, kind = SourceKind.ADDON, baseUrl = base())

    private val target = TrackMatcher.Target("Blinding Lights", "The Weeknd", 200)

    // ── Valid answers ───────────────────────────────────────────────────

    @Test
    fun healthyAddonReportsNameAndVersion() = runBlocking {
        routes["/addon/manifest.json"] = manifest()
        val health = source().health()
        assertTrue(health is SourceHealth.Ok)
        assertEquals("Test v1.2", (health as SourceHealth.Ok).detail)
    }

    @Test
    fun searchAndLosslessStreamResolve() = runBlocking {
        routes["/addon/manifest.json"] = manifest()
        routes["/addon/search"] = json(searchBody)
        routes["/addon/stream/t1"] = json(
            """{"url":"https://cdn.example/t1.flac","codec":"flac","sampleRate":96000,"bitDepth":24}""",
        )
        val src = source()
        val resolver = SourceResolver({ listOf(src) })
        val outcome = resolver.resolve(target, StreamRequest.Lossless)
        val stream = outcome.stream
        assertNotNull(outcome.tried.toString(), stream)
        assertEquals("https://cdn.example/t1.flac", stream!!.url)
        assertEquals("flac", stream.format.codec)
        assertEquals(96_000, stream.format.sampleRateHz)
        assertEquals(24, stream.format.bitDepth)
        assertFalse(stream.format.codecInferred)
        // The quality was asked for by name.
        val streamCall = seen.first { it.path == "/addon/stream/t1" }
        assertEquals("LOSSLESS", streamCall.query["quality"])
    }

    @Test
    fun qualityIsNegotiatedAgainstDeclaredOptions() = runBlocking {
        routes["/addon/manifest.json"] = manifest(
            ""","settings":[{"key":"quality","default":"normal","options":[{"value":"low"},{"value":"normal"},{"value":"hifi"}]}]""",
        )
        routes["/addon/search"] = json(searchBody)
        source().search("x", 5, StreamRequest.Lossless)
        source().search("y", 5, StreamRequest.Capped(96))
        val qualities = seen.filter { it.path == "/addon/search" }
            .map { it.query["quality"] }
        assertEquals(listOf("hifi", "low"), qualities)
    }

    // ── Invalid and partial answers ─────────────────────────────────────

    @Test
    fun invalidJsonIsRejectedNotCrashing() = runBlocking {
        routes["/addon/manifest.json"] = json("this is not json")
        routes["/addon/search"] = json("<html>nope</html>")
        val health = source().health()
        assertTrue(health.toString(), health is SourceHealth.Rejected)
        assertTrue(source().search("Blinding Lights", 5).isEmpty())
    }

    @Test
    fun manifestWithoutIdIsRejected() = runBlocking {
        routes["/addon/manifest.json"] = json("""{"name":"no id"}""")
        val health = source().health()
        assertTrue(health is SourceHealth.Rejected)
    }

    @Test
    fun rowsWithoutIdOrTitleAreDropped() = runBlocking {
        routes["/addon/manifest.json"] = manifest()
        routes["/addon/search"] = json("""{"tracks":[{"id":"","title":"x"},{"id":"a","title":""},{"id":"ok","title":"Fine","artist":"A"}]}""")
        val rows = source().search("q", 10)
        assertEquals(listOf("ok"), rows.map { it.id })
    }

    @Test
    fun streamWithoutUrlFallsBackToRowUrlOrMiss() = runBlocking {
        routes["/addon/manifest.json"] = manifest()
        routes["/addon/search"] = json(
            """{"tracks":[{"id":"t1","title":"Blinding Lights","artist":"The Weeknd","duration":200,"streamURL":"https://cdn.example/row.mp3","format":"mp3"}]}""",
        )
        routes["/addon/stream/t1"] = json("""{"error":"No playable audio"}""")
        val src = source()
        src.search("Blinding Lights The Weeknd", 5)
        val stream = src.stream("t1", StreamRequest.Best)
        assertEquals("https://cdn.example/row.mp3", stream?.url)
    }

    @Test
    fun encryptedOrMalformedStreamsAreRefused() = runBlocking {
        routes["/addon/manifest.json"] = manifest()
        routes["/addon/stream/enc"] = json("""{"url":"https://cdn.example/a.flac","codec":"flac","encrypted":"widevine"}""")
        routes["/addon/stream/bad"] = json("""{"url":"javascript:alert(1)","codec":"flac"}""")
        routes["/addon/stream/twice"] = json("""{"url":"https://cdn.example/https://cdn.example/a.mp3"}""")
        val src = source()
        assertNull(src.stream("enc", StreamRequest.Best))
        assertNull(src.stream("bad", StreamRequest.Best))
        assertNull(src.stream("twice", StreamRequest.Best))
    }

    // ── Errors, timeouts, unavailable sources ───────────────────────────

    @Test
    fun serverErrorIsUnreachableAndClientErrorIsRejected() = runBlocking {
        routes["/addon/manifest.json"] = json("{}", code = 503)
        assertTrue(source().health() is SourceHealth.Unreachable)
        routes["/addon/manifest.json"] = json("{}", code = 401)
        assertTrue(source(config("other")).health() is SourceHealth.Rejected)
    }

    @Test
    fun slowAddonTimesOutAndResolverFallsBack() = runBlocking {
        routes["/addon/manifest.json"] = manifest()
        routes["/addon/search"] = json(searchBody).copy(delayMs = 5_000)
        val resolver = SourceResolver({ listOf(source()) }, perSourceTimeoutMs = 3_000)
        val outcome = resolver.resolve(target, StreamRequest.Best)
        assertNull(outcome.stream)
    }

    @Test
    fun unreachableHostIsNotACrash() = runBlocking {
        val dead = SourceConfig(id = "dead", kind = SourceKind.ADDON, baseUrl = "http://127.0.0.1:9")
        val src = AddonSource(dead, null, AddonClient(dead.baseUrl, client), { false }, { false })
        assertTrue(src.health() is SourceHealth.Unreachable)
        assertNull(SourceResolver({ listOf(src) }).resolve(target, StreamRequest.Best).stream)
    }

    @Test
    fun throwingSourceIsSkippedAndNextOneAnswers() = runBlocking {
        val broken = object : FakeSource("broken") {
            override suspend fun search(query: String, limit: Int, request: StreamRequest?): List<SourceTrack> =
                throw IllegalStateException("boom")
        }
        val good = FakeSource("good")
        val outcome = SourceResolver({ listOf(broken, good) }).resolve(target, StreamRequest.Best)
        assertEquals("good", outcome.stream?.sourceConfigId)
    }

    @Test
    fun hangingSourceIsAbandoned() = runBlocking {
        val hanging = object : FakeSource("hang") {
            override suspend fun search(query: String, limit: Int, request: StreamRequest?): List<SourceTrack> {
                delay(60_000)
                return emptyList()
            }
        }
        val outcome = SourceResolver({ listOf(hanging, FakeSource("good")) }, perSourceTimeoutMs = 200)
            .resolve(target, StreamRequest.Best)
        assertEquals("good", outcome.stream?.sourceConfigId)
    }

    // ── Requested quality ───────────────────────────────────────────────

    @Test
    fun losslessRequestRefusesLossyAndInferredCodecs() = runBlocking {
        val lossy = FakeSource("mp3", StreamFormat(codec = "mp3", kbps = 320))
        val inferred = FakeSource("label", StreamFormat(codec = "flac", codecInferred = true))
        val real = FakeSource("flac", StreamFormat(codec = "flac", bitDepth = 16, sampleRateHz = 44_100))
        val outcome = SourceResolver({ listOf(lossy, inferred, real) }).resolve(target, StreamRequest.Lossless)
        assertEquals("flac", outcome.stream?.sourceConfigId)
        assertEquals(3, outcome.tried.size)
    }

    @Test
    fun cappedRequestRefusesStatedBitrateAboveCap() = runBlocking {
        val tooHigh = FakeSource("320", StreamFormat(codec = "mp3", kbps = 320))
        val fine = FakeSource("128", StreamFormat(codec = "aac", kbps = 128))
        val outcome = SourceResolver({ listOf(tooHigh, fine) }).resolve(target, StreamRequest.Capped(160))
        assertEquals("128", outcome.stream?.sourceConfigId)
    }

    @Test
    fun manifestTransportsAreRefused() = runBlocking {
        val dash = FakeSource("dash", StreamFormat(codec = "flac"), transport = "dash")
        assertNull(SourceResolver({ listOf(dash) }).resolve(target, StreamRequest.Best).stream)
    }

    @Test
    fun noMatchMeansYouTube() = runBlocking {
        val wrong = FakeSource("wrong", title = "Completely Different", artist = "Nobody")
        assertNull(SourceResolver({ listOf(wrong) }).resolve(target, StreamRequest.Best).stream)
        assertNull(SourceResolver({ emptyList() }).resolve(target, StreamRequest.Best).stream)
    }

    // ── Policies: allowDownloads, checkValidLossless ────────────────────

    @Test
    fun downloadsForbiddenByAddonAreRefusedForDownloadOnly() = runBlocking {
        routes["/addon/manifest.json"] = manifest(""","allowDownloads":0""")
        routes["/addon/search"] = json(searchBody)
        routes["/addon/stream/t1"] = json("""{"url":"https://cdn.example/t1.flac","codec":"flac"}""")
        val registry = SourceRegistry(MemorySourceStorage())
        val added = registry.add(SourceKind.ADDON, base())
        val src = source(added, registry = registry)
        val resolver = SourceResolver({ listOf(src) })
        assertNotNull(resolver.resolve(target, StreamRequest.Lossless).stream)
        val forDownload = resolver.resolve(target, StreamRequest.Lossless, forDownload = true)
        assertNull(forDownload.stream)
        assertTrue(forDownload.tried.last().contains("downloads not allowed"))
        assertFalse(registry.config(added.id)!!.allowDownloads)
    }

    @Test
    fun checkValidLosslessShutsTheAddonOutWithoutCapableOutput() = runBlocking {
        routes["/addon/manifest.json"] = manifest(""","checkValidLossless":true""")
        routes["/addon/search"] = json(searchBody)
        assertTrue(source(lossless = false).search("Blinding Lights", 5).isEmpty())
        assertTrue(source(lossless = false).health() is SourceHealth.Rejected)
        assertEquals(2, source(config("b"), lossless = true).search("Blinding Lights", 5).size)
    }

    // ── Registry: order, enable, remove ─────────────────────────────────

    @Test
    fun registryOrdersDisablesAndRemoves() {
        val storage = MemorySourceStorage()
        val registry = SourceRegistry(storage)
        val a = registry.add(SourceKind.ADDON, "https://a.example")
        val b = registry.add(SourceKind.ADDON, "https://b.example")
        val c = registry.add(SourceKind.JIOSAAVN)
        registry.move(c.id, -2)
        assertEquals(listOf(c.id, a.id, b.id), registry.configs.value.map { it.id })
        registry.setEnabled(a.id, false)
        val built = registry.enabledSources { FakeSource(it.id) }.map { it.configId }
        assertEquals(listOf(c.id, b.id), built)
        registry.remove(b.id)
        // Persisted: a new registry over the same storage reads the same list.
        val reread = SourceRegistry(storage).configs.value
        assertEquals(listOf(c.id, a.id), reread.map { it.id })
        assertFalse(reread.first { it.id == a.id }.enabled)
    }

    // ── Detection of pasted URLs ────────────────────────────────────────

    @Test
    fun detectRecognisesAddonsAndRefusesModules() {
        assertTrue(SourceFormats.detect("""{"id":"x","name":"X","resources":["search"]}""") is DetectedFormat.Addon)
        val module = SourceFormats.detect("""{"category:music":[{"name":"m"}]}""")
        assertTrue(module is DetectedFormat.Unsupported)
        assertTrue((module as DetectedFormat.Unsupported).reason.contains("MODULE"))
        assertTrue(SourceFormats.detect("""{"id":"x","resources":["catalog"]}""") is DetectedFormat.Unsupported)
        assertTrue(SourceFormats.detect("[1,2]") is DetectedFormat.Unsupported)
    }

    @Test
    fun identifyFindsAddonWithoutManifestThroughSearch() = runBlocking {
        routes["/addon/search"] = json(searchBody)
        val found = SourceFormats.identify(base(), client).getOrThrow()
        assertTrue(found.toString(), found is DetectedFormat.Addon)
    }

    @Test
    fun normalizeBaseStripsManifestName() {
        assertEquals("https://x.example/tok", AddonClient.normalizeBase("https://x.example/tok/manifest.json"))
        assertEquals("https://x.example/tok", AddonClient.normalizeBase("https://x.example/tok/"))
        assertEquals("https://x.example/***", AddonClient.redact("https://x.example/secret-token/search"))
    }

    // ── Matching ────────────────────────────────────────────────────────

    @Test
    fun matcherAcceptsSameRecordingAndRefusesVersions() {
        val row = SourceTrack("1", "Blinding Lights", "The Weeknd", durationSec = 201)
        assertNotNull(TrackMatcher.score(row, target))
        assertNull(TrackMatcher.score(row.copy(title = "Blinding Lights (Live)"), target))
        assertNull(TrackMatcher.score(row.copy(durationSec = 260), target))
        assertNull(TrackMatcher.score(row.copy(artist = "Someone Else"), target))
        assertNotNull(TrackMatcher.score(row.copy(title = "Blinding Lights (Official Audio)"), target))
    }

    /** A source answering from memory. */
    private open class FakeSource(
        override val configId: String,
        private val format: StreamFormat = StreamFormat(codec = "mp3", kbps = 320),
        private val title: String = "Blinding Lights",
        private val artist: String = "The Weeknd",
        private val transport: String? = null,
    ) : MusicSource {
        override val kind = SourceKind.ADDON
        override val displayName get() = configId
        override suspend fun health(): SourceHealth = SourceHealth.Ok()
        override suspend fun search(query: String, limit: Int, request: StreamRequest?): List<SourceTrack> =
            listOf(SourceTrack("id-$configId", title, artist, durationSec = 200))
        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream =
            SourceStream(url = "https://cdn.example/$configId", format = format, transport = transport)
    }
}
