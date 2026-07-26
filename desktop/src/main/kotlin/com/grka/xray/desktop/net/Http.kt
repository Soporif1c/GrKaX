package com.grka.xray.desktop.net

import java.net.http.HttpClient
import java.time.Duration

/** One shared client — the JDK's own, so the app ships no HTTP dependency. */
object Http {
    val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }
}
