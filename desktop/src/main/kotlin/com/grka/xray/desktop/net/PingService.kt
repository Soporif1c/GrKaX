package com.grka.xray.desktop.net

import com.grka.xray.desktop.AppConfig
import com.grka.xray.desktop.data.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Latency measurement.
 *
 * The Android client asks the core for a delay, which the gomobile binding
 * exposes. A desktop core is a child process with no such call, so servers are
 * measured with a TCP handshake, and the live connection is measured with a
 * real request through the local HTTP inbound.
 */
object PingService {

    const val TIMEOUT_MS = 3000

    /** TCP handshake time to the server, in ms; -1 when unreachable. */
    suspend fun tcpPing(profile: Profile): Long = withContext(Dispatchers.IO) {
        if (profile.server.isBlank() || profile.port <= 0) return@withContext -1L
        val started = System.nanoTime()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(profile.server, profile.port), TIMEOUT_MS)
            }
            (System.nanoTime() - started) / 1_000_000
        } catch (e: Exception) {
            -1L
        }
    }

    /** Measures a whole list in parallel, keyed by profile id. */
    suspend fun tcpPingAll(profiles: List<Profile>): Map<String, Long> = coroutineScope {
        profiles.map { p -> p.id to async { tcpPing(p) } }
            .associate { (id, deferred) -> id to deferred.await() }
    }

    /**
     * Round-trip through the running core, i.e. what the user actually feels.
     * Goes via the local HTTP inbound because the JDK client cannot speak SOCKS.
     */
    suspend fun proxyDelay(httpPort: Int): Long = withContext(Dispatchers.IO) {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(TIMEOUT_MS.toLong()))
            .proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", httpPort)))
            .build()
        for (url in listOf(AppConfig.DELAY_TEST_URL, AppConfig.DELAY_TEST_URL2)) {
            val started = System.nanoTime()
            val ok = runCatching {
                val request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(TIMEOUT_MS.toLong()))
                    .GET()
                    .build()
                client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..399
            }.getOrDefault(false)
            if (ok) return@withContext (System.nanoTime() - started) / 1_000_000
        }
        -1L
    }
}
