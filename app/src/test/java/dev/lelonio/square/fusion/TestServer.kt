package dev.lelonio.square.fusion

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A local HTTP/1.1 server for the tests, on a plain ServerSocket: unit tests
 * compile against android.jar, which has no JDK HTTP server, and a test
 * library would bring its own OkHttp, which disagrees with the app's.
 * One request per connection, then closed.
 */
class TestServer {

    data class Reply(val code: Int = 200, val body: String = "", val delayMs: Long = 0)

    data class Seen(val path: String, val query: Map<String, String>, val headers: Map<String, String>)

    /** Answers by path; anything else is a 404. */
    val routes = ConcurrentHashMap<String, Reply>()

    /** Answers in turn regardless of path, before [routes]. */
    val queue = ConcurrentLinkedQueue<Reply>()

    val seen = CopyOnWriteArrayList<Seen>()

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    @Volatile
    private var running = true

    init {
        thread(isDaemon = true, name = "TestServer") {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) {
        client.use { conn ->
            try {
                val reader = BufferedReader(InputStreamReader(conn.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val target = requestLine.split(' ').getOrNull(1) ?: "/"
                val headers = HashMap<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val path = target.substringBefore('?')
                val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
                    URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                        URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                }
                seen += Seen(path, query, headers)
                val reply = queue.poll() ?: routes[path] ?: Reply(404, "")
                if (reply.delayMs > 0) Thread.sleep(reply.delayMs)
                val body = reply.body.toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 ${reply.code} X\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                val out = conn.getOutputStream()
                out.write(head.toByteArray(Charsets.US_ASCII))
                out.write(body)
                out.flush()
            } catch (_: Exception) {
                // The client gave up (a timeout test): nothing to answer.
            }
        }
    }

    fun url(path: String): String = "http://127.0.0.1:${socket.localPort}$path"

    fun shutdown() {
        running = false
        runCatching { socket.close() }
    }
}
