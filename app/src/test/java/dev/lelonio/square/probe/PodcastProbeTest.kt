package dev.lelonio.square.probe

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PodcastItem
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PodcastProbeTest {
    private fun p(s: String) = println("PROBE $s")

    @Test
    fun probe() = runBlocking {
        YouTube.locale = com.metrolist.innertube.models.YouTubeLocale(gl = "FR", hl = "fr")
        val vd = YouTube.visitorData()
        p("visitorData: ${vd.getOrNull()?.take(12)} err=${vd.exceptionOrNull()?.message?.take(200)}")
        vd.getOrNull()?.let { YouTube.visitorData = it }
        for (q in listOf("Flip & Gossip", "Flip & Gossip Maghla", "Les Grosses Têtes")) {
            val r = YouTube.search(q, YouTube.SearchFilter.FILTER_PODCAST)
            p("search '$q': ${r.exceptionOrNull()?.message?.replace("\n"," ")?.take(400) ?: ""}")
            val items = r.getOrNull()?.items.orEmpty()
            items.take(4).forEach { p("  ${it::class.simpleName} id=${it.id} title=${it.title}") }
            items.filterIsInstance<PodcastItem>().take(2).forEach { show ->
                val pl = YouTube.playlist(show.id)
                p("  playlist(${show.id}): songs=${pl.getOrNull()?.songs?.size} err=${pl.exceptionOrNull()?.javaClass?.simpleName}:${pl.exceptionOrNull()?.message?.take(80)} cont=${pl.getOrNull()?.songsContinuation != null || pl.getOrNull()?.continuation != null}")
                pl.getOrNull()?.songs?.take(2)?.forEach { p("    song ${it.id} ${it.title}") }
                val pod = YouTube.podcast(show.id)
                p("  podcast(${show.id}): episodes=${pod.getOrNull()?.episodes?.size} cont=${pod.getOrNull()?.continuation != null} err=${pod.exceptionOrNull()?.javaClass?.simpleName}:${pod.exceptionOrNull()?.message?.take(80)}")
                pod.getOrNull()?.episodes?.take(2)?.forEach { p("    episode ${it.id} ${it.title}") }
                if (show.id.startsWith("MPSP")) {
                    val raw = YouTube.playlist(show.id.removePrefix("MPSP"))
                    p("  playlist(${show.id.removePrefix("MPSP")}): songs=${raw.getOrNull()?.songs?.size} err=${raw.exceptionOrNull()?.message?.take(80)}")
                }
            }
        }
    }
}
