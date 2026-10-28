// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.localserver

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One parsed request: [path] without the leading `/`, the decoded [query] and the lower-case [headers]. */
data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
)

/** One answer: a status line (`200 OK`), the body and its content type. */
data class HttpAnswer(
    val status: String,
    val body: String,
    val contentType: String = "application/json; charset=utf-8",
) {
    companion object {
        val NOT_FOUND = HttpAnswer("404 Not Found", "Not found\n", "text/plain")
    }
}

/**
 * A tiny, bounded HTTP/1.1 server for the phone's local APIs (the xDrip+ server for Garmin). Safe by construction:
 *
 * - binds 127.0.0.1 only (nothing off the phone can reach it), plain HTTP because Garmin Connect allows cleartext
 *   only to 127.0.0.1;
 * - one accept thread and a fixed pool of [POOL] workers with a queue of [QUEUE]: a connection beyond that is closed at
 *   once, never a new thread;
 * - every connection has a total deadline ([DEADLINE_MS], also per read), a request head of at most [MAX_HEAD] bytes,
 *   GET / HEAD only, no body read; `Connection: close` after one answer;
 * - nothing a request does can crash the app: every connection catches everything.
 *
 * [bind] fails (returns false) when the port is taken: the caller shows "port in use" and never retries in a loop.
 */
class LocalHttpServer(
    private val port: Int,
    private val handler: (HttpRequest) -> HttpAnswer,
    private val onRequest: (HttpRequest, HttpAnswer) -> Unit = { _, _ -> },
) {
    @Volatile private var socket: ServerSocket? = null
    private var pool: ThreadPoolExecutor? = null

    val running: Boolean get() = socket?.isClosed == false

    /** The port actually bound (tests bind port 0). */
    val boundPort: Int get() = socket?.localPort ?: -1

    /** The address listened on (always loopback). */
    val boundAddress: InetAddress? get() = socket?.inetAddress

    @Synchronized
    fun bind(): Boolean {
        if (running) return true
        val s =
            try {
                ServerSocket(port, BACKLOG, InetAddress.getByName(LOOPBACK))
            } catch (e: IOException) {
                return false
            }
        val p =
            ThreadPoolExecutor(POOL, POOL, 30, TimeUnit.SECONDS, ArrayBlockingQueue(QUEUE)) { r ->
                Thread(r, "sukrara-local-http").apply {
                    isDaemon =
                        true
                }
            }
        socket = s
        pool = p
        Thread({ acceptLoop(s, p) }, "sukrara-local-accept").apply { isDaemon = true }.start()
        return true
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
        pool?.shutdownNow()
        pool = null
    }

    private fun acceptLoop(
        s: ServerSocket,
        p: ThreadPoolExecutor,
    ) {
        while (!s.isClosed) {
            val c =
                try {
                    s.accept()
                } catch (e: IOException) {
                    break
                }
            try {
                p.execute { serve(c) }
            } catch (e: RejectedExecutionException) {
                runCatching { c.close() }
            }
        }
    }

    private fun serve(c: Socket) {
        try {
            c.use {
                it.soTimeout = DEADLINE_MS.toInt()
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS)
                val head = readHead(BufferedInputStream(it.getInputStream()), deadline)
                val answer =
                    when {
                        head == null -> HttpAnswer("431 Request Header Fields Too Large", "", "text/plain")
                        else -> parse(head)?.let { req -> if (req.method == "GET" || req.method == "HEAD") route(req) else METHOD } ?: BAD
                    }
                write(it, answer, head?.startsWith("HEAD ") == true)
            }
        } catch (e: Throwable) {
            // a broken connection or a bug in a handler must never take the app (and its alerts) down
        }
    }

    private fun route(req: HttpRequest): HttpAnswer {
        val answer = runCatching { handler(req) }.getOrElse { HttpAnswer("500 Internal Server Error", "", "text/plain") }
        runCatching { onRequest(req, answer) }
        return answer
    }

    /** The request line and headers up to the blank line, or null when longer than [MAX_HEAD] or past the deadline. */
    private fun readHead(
        input: InputStream,
        deadline: Long,
    ): String? {
        val buf = StringBuilder()
        while (buf.length < MAX_HEAD) {
            if (System.nanoTime() > deadline) return null
            val b = input.read()
            if (b < 0) break
            buf.append(b.toChar())
            if (buf.endsWith("\r\n\r\n") || buf.endsWith("\n\n")) return buf.toString()
        }
        return if (buf.length >= MAX_HEAD) null else buf.toString()
    }

    private fun write(
        c: Socket,
        a: HttpAnswer,
        head: Boolean,
    ) {
        val bytes = a.body.toByteArray()
        val out = c.getOutputStream()
        out.write(
            (
                "HTTP/1.1 ${a.status}\r\nContent-Type: ${a.contentType}\r\nContent-Length: ${bytes.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
            ).toByteArray(),
        )
        if (!head) out.write(bytes)
        out.flush()
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val POOL = 4
        const val QUEUE = 8
        const val BACKLOG = 8
        const val MAX_HEAD = 8 * 1024
        const val DEADLINE_MS = 5_000L
        private val METHOD = HttpAnswer("405 Method Not Allowed", "", "text/plain")
        private val BAD = HttpAnswer("400 Bad Request", "", "text/plain")

        fun parse(head: String): HttpRequest? {
            val lines = head.split("\r\n", "\n")
            val parts = lines.firstOrNull()?.split(' ') ?: return null
            if (parts.size < 2) return null
            val target = parts[1]
            val headers = LinkedHashMap<String, String>()
            for (l in lines.drop(1)) {
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val query =
                target
                    .substringAfter('?', "")
                    .split('&')
                    .filter { it.isNotEmpty() }
                    .associate {
                        runCatching { URLDecoder.decode(it.substringBefore('='), "UTF-8") }.getOrDefault("") to
                            runCatching { URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
                    }
            return HttpRequest(parts[0].uppercase(), target.substringBefore('?').trimStart('/'), query, headers)
        }
    }
}
