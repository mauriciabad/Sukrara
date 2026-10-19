// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.nsupload

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

data class HttpRequest(val method: String, val url: String, val headers: Map<String, String> = emptyMap(), val body: String? = null)

/** [code] 0 = no HTTP answer (DNS, refused, timeout, TLS). */
data class HttpResponse(val code: Int, val body: String = "", val headers: Map<String, String> = emptyMap(), val error: String? = null)

/** The one seam to the network, so tests run against a fake server and Sukrara could plug in its own client. */
fun interface Http {
    fun send(req: HttpRequest): HttpResponse
}

/**
 * HttpURLConnection: present on the JVM and on Android, so the library needs no OkHttp/Ktor. TLS validation is the
 * platform's (no trust-all, no pinning: users bring their own hosting, from Heroku-likes to a Raspberry Pi behind
 * Let's Encrypt).
 */
class UrlConnectionHttp(private val connectTimeoutMs: Int = 15_000, private val readTimeoutMs: Int = 30_000) : Http {
    override fun send(req: HttpRequest): HttpResponse = try {
        val c = URI(req.url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.instanceFollowRedirects = false   // a redirect could carry the api-secret header to another host
        c.requestMethod = req.method
        req.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.setRequestProperty("Accept", "application/json")
        if (req.body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(req.body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        val headers = c.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }
        c.disconnect()
        HttpResponse(code, body, headers)
    } catch (e: IOException) {
        HttpResponse(0, error = e.javaClass.simpleName + ": " + e.message)
    }
}
