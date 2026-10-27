package dev.lelonio.square.fusion

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A local HTTP server for the tests, on the JDK's own HttpServer: no
 * dependency whose version could disagree with the app's OkHttp.
 */
class TestServer {

    data class Reply(val code: Int = 200, val body: String = "", val delayMs: Long = 0)

    /** What a request looked like, as far as the tests care. */
    data class Seen(val path: String, val query: Map<String, String>, val headers: Map<String, String>)

    /** Answers by path; anything else is a 404. */
    val routes = ConcurrentHashMap<String, Reply>()

    /** Answers in turn regardless of path, before [routes]. */
    val queue = java.util.concurrent.ConcurrentLinkedQueue<Reply>()

    val seen = CopyOnWriteArrayList<Seen>()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/") { exchange ->
            val uri = exchange.requestURI
            val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
                java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
            seen += Seen(uri.rawPath, query, headers)
            val reply = queue.poll() ?: routes[uri.rawPath] ?: Reply(404, "")
            try {
                if (reply.delayMs > 0) Thread.sleep(reply.delayMs)
                val bytes = reply.body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(reply.code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            } catch (_: Exception) {
                // The client gave up (a timeout test): nothing to answer.
            } finally {
                exchange.close()
            }
        }
        start()
    }

    fun url(path: String): String = "http://127.0.0.1:${server.address.port}$path"

    fun shutdown() = server.stop(0)
}
