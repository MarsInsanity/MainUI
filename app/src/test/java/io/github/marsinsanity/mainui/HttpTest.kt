package io.github.marsinsanity.mainui

import io.github.marsinsanity.mainui.store.Http
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** The store downloader against a small local server that limits every connection's speed. */
class HttpTest {
    private lateinit var server: ServerSocket
    private val pool = Executors.newCachedThreadPool()
    private val data = Random(7).nextBytes(5 * 1024 * 1024 + 12345)
    @Volatile private var etag = "\"v1\""
    private var rangesSupported = true
    private var changeAfterFirstRequest = false
    private val active = AtomicInteger()
    private val maxActive = AtomicInteger()
    private val requests = AtomicInteger()
    private val bytesPerSecond = 4L * 1024 * 1024

    private val url get() = "http://127.0.0.1:${server.localPort}/index-v1.jar"

    @Before fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        pool.execute {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(sock) }; runCatching { sock.close() } }
            }
        }
    }

    @After fun stop() { server.close(); pool.shutdownNow() }

    /** Minimal HTTP/1.0 server: Range, If-Range, If-None-Match, per-connection speed limit. */
    private fun handle(sock: Socket) {
        val input = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        input.readLine() ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val l = input.readLine() ?: break
            if (l.isEmpty()) break
            headers[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim()
        }
        val n = requests.incrementAndGet()
        maxActive.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
        try {
            if (changeAfterFirstRequest && n > 1) etag = "\"v2\""
            val out = sock.getOutputStream()
            fun head(status: String, extra: List<String>, length: Long) {
                val h = (listOf("HTTP/1.0 $status", "ETag: $etag", "Content-Length: $length", "Connection: close") + extra)
                    .joinToString("\r\n") + "\r\n\r\n"
                out.write(h.toByteArray(Charsets.ISO_8859_1))
            }
            if (headers["if-none-match"] == etag) { head("304 Not Modified", emptyList(), 0); out.flush(); return }
            var start = 0L
            var end = data.size - 1L
            val range = headers["range"]
            val ifRange = headers["if-range"]
            val partial = rangesSupported && range != null && (ifRange == null || ifRange == etag)
            if (partial) {
                val parts = range!!.removePrefix("bytes=").split("-")
                start = parts[0].toLong(); if (parts[1].isNotEmpty()) end = minOf(end, parts[1].toLong())
                head("206 Partial Content", listOf("Content-Range: bytes $start-$end/${data.size}"), end - start + 1)
            } else head("200 OK", emptyList(), end - start + 1)
            val t0 = System.nanoTime()
            var pos = start
            while (pos <= end) {
                val len = minOf(16 * 1024L, end - pos + 1).toInt()
                out.write(data, pos.toInt(), len)
                pos += len
                val ahead = (pos - start) * 1_000_000_000L / bytesPerSecond - (System.nanoTime() - t0)
                if (ahead > 0) Thread.sleep(ahead / 1_000_000, (ahead % 1_000_000).toInt())
            }
            out.flush()
        } finally {
            active.decrementAndGet()
        }
    }

    private fun tmp() = File.createTempFile("mainui-dl", ".jar").also { it.delete(); it.deleteOnExit() }

    @Test fun downloadsInParallelPiecesAndGetsEveryByteRight() {
        val dest = tmp()
        val t0 = System.currentTimeMillis()
        val info = Http.downloadFast(url, dest)
        val ms = System.currentTimeMillis() - t0
        assertArrayEquals(data, dest.readBytes())
        assertEquals("\"v1\"", info.etag)
        assertTrue("used ${maxActive.get()} connections", maxActive.get() > 1)
        // One connection would need ~1.3 s at 4 MB/s.
        assertTrue("took $ms ms", ms < 1000)
    }

    @Test fun unchangedFileIsNotDownloadedAgain() {
        val dest = tmp()
        val info = Http.downloadFast(url, dest, ifNoneMatch = "\"v1\"")
        assertTrue(info.notModified)
        assertEquals(1, requests.get())
        assertTrue(!dest.exists())
    }

    @Test fun serverWithoutRangesStillWorks() {
        rangesSupported = false
        val dest = tmp()
        Http.downloadFast(url, dest)
        assertArrayEquals(data, dest.readBytes())
        assertEquals(1, maxActive.get())
    }

    @Test fun fileChangingMidwayFallsBackToOneStream() {
        changeAfterFirstRequest = true
        val dest = tmp()
        Http.downloadFast(url, dest)
        assertArrayEquals(data, dest.readBytes())                   // test data stays the same; only the ETag changes
        assertEquals(data.size.toLong(), dest.length())
    }
}
