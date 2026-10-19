// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.bridge

import android.util.Log
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap
import kotlin.concurrent.thread

/**
 * Investigation 08: the xDrip+ "local web service" on 127.0.0.1:17580, which is what Garmin Connect IQ
 * glucose apps (CGM+ GB / CGM+ Datafield, CGM Connect, CGM Gauge, …) poll through Garmin Connect's
 * `makeWebRequest` proxy. Output is byte-compatible with Juggluco's server, the one the watch was set up for.
 *
 * Loopback only, plain HTTP (Garmin Connect allows cleartext only to 127.0.0.1), one thread blocked in
 * accept() — no polling, no wakelocks. Every request is logged (logcat tag GlucoBridge.Xdrip and
 * files/xdrip-requests.log) so we can see exactly what each watch app asks for.
 */
class XdripServer(private val logFile: File, private val port: Int = PORT, private val store: File? = null) {
    private val tag = "Sukrara.garmin"
    private val readings = TreeMap<Long, Bridge.Reading>()      // epoch s → reading, ≤ KEEP_SECONDS

    init {
        // The sensor's stored history lags ~16 min behind and has a 5-min step, so the 1-minute live readings
        // are kept on disk: otherwise every service restart leaves a gap in the watch graphs.
        store?.takeIf { it.exists() }?.readLines()?.forEach { line ->
            val f = line.split(',')
            val t = f.getOrNull(0)?.toLongOrNull() ?: return@forEach
            val v = f.getOrNull(1)?.toIntOrNull() ?: return@forEach
            add(Bridge.Reading(t, v, f.getOrNull(2)?.toDoubleOrNull()), persist = false)
        }
        store?.writeText(readings.values.joinToString("") { line(it) })
    }

    private fun line(r: Bridge.Reading) = "${r.epochSec},${r.mgdl},${r.rate ?: ""}\n"
    @Volatile private var sensorStart: Long? = null
    @Volatile private var sensorSerial: String? = null
    @Volatile private var socket: ServerSocket? = null

    /** Last request per path, for the UI ("watch polled 2 min ago"). */
    @Volatile var lastRequest: Pair<Long, String>? = null
        private set
    var onRequest: (() -> Unit)? = null
    @Volatile var lastNightscoutRequest: Pair<Long, String>? = null
        private set

    fun start(): String? {
        val s = runCatching { ServerSocket(port, 8, InetAddress.getByName("127.0.0.1")) }
            .getOrElse { e -> Log.w(tag, "bind 127.0.0.1:$port failed: $e"); return "port $port busy (Juggluco/xDrip/GDH server on?)" }
        s.reuseAddress = true
        socket = s
        serve(s, nightscout = false)
        Log.i(tag, "listening on 127.0.0.1:$port")
        return null
    }

    fun stop() {
        socket?.close()
        socket = null
    }

    val running get() = socket?.isClosed == false

    // MARK: Nightscout listener (investigation 03's tier-1 read API, see [NightscoutApi])

    @Volatile private var nsSocket: ServerSocket? = null
    @Volatile private var nsSecret: String = ""
    /** Treatments for `/api/v1/treatments` (the wearer's shared log; a follower has none yet). */
    @Volatile var treatments: () -> List<app.mauri.sukrara.treatments.Treatment> = { emptyList() }
    @Volatile var siteName: String = "Sukrara"

    /**
     * A second listener with the full Nightscout v1 read surface, independent of port 17580 (which Juggluco keeps
     * while it is the source). [lan] = bind all interfaces so Home Assistant / xDrip+ / Nightguard on the same
     * network can read it; non-loopback callers then need [secret] (API secret or `?token=`). Loopback is open,
     * like Juggluco's and xDrip+'s local servers.
     */
    fun startNightscout(nsPort: Int, lan: Boolean, secret: String): String? {
        stopNightscout()
        val addr = InetAddress.getByName(if (lan) "0.0.0.0" else "127.0.0.1")
        val s = runCatching { ServerSocket(nsPort, 16, addr) }
            .getOrElse { e -> Log.w(tag, "nightscout bind ${addr.hostAddress}:$nsPort failed: $e"); return "port $nsPort busy" }
        s.reuseAddress = true
        nsSecret = secret
        nsSocket = s
        serve(s, nightscout = true)
        Log.i(tag, "nightscout API on ${addr.hostAddress}:$nsPort")
        return null
    }

    fun stopNightscout() {
        nsSocket?.close()
        nsSocket = null
    }

    private fun serve(s: ServerSocket, nightscout: Boolean) {
        thread(name = if (nightscout) "ns-server" else "xdrip-server", isDaemon = true) {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                thread(name = "xdrip-conn", isDaemon = true) { c.use { runCatching { handle(it, nightscout) } } }
            }
        }
    }

    /** Newest first: every stored reading (≤ 24 h; 1-minute live values where we had them). */
    fun snapshot(): List<Bridge.Reading> = synchronized(readings) { readings.descendingMap().values.toList() }

    /** Forget all readings (a follower stopped following). */
    fun clear() {
        synchronized(readings) { readings.clear() }
        store?.let { runCatching { it.writeText("") } }
    }

    fun add(r: Bridge.Reading, persist: Boolean = true) {
        synchronized(readings) {
            val near = readings.subMap(r.epochSec - 20, true, r.epochSec + 20, true)
            // A live reading (with rate) beats a history point for the same minute.
            if (r.rate == null && near.values.any { it.rate != null }) return
            near.clear()
            readings[r.epochSec] = r
            readings.headMap(readings.lastKey() - KEEP_SECONDS).clear()
            if (persist) store?.let { runCatching { it.appendText(line(r)) } }   // compacted on next start
        }
    }

    fun onSensor(serial: String, startEpoch: Long) {
        sensorSerial = serial
        sensorStart = startEpoch
    }

    // MARK: HTTP

    private fun handle(c: Socket, nightscout: Boolean) {
        c.soTimeout = 5000
        val input = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = input.readLine() ?: return
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrElse(0) { "GET" }
        val target = parts.getOrElse(1) { "/" }
        val path = target.substringBefore('?').trimStart('/')
        val query = parseQuery(target.substringAfter('?', ""))

        val loopback = c.inetAddress?.isLoopbackAddress != false
        val (status, body) = when {
            !nightscout -> route(path, query)
            !loopback && (nsSecret.isEmpty() || !NightscoutApi.authorized(nsSecret, headers, query)) ->
                "401 Unauthorized" to "{\"status\":401,\"message\":\"Unauthorized\"}"
            else -> NightscoutApi.route(path, query, snapshot(), runCatching { treatments() }.getOrDefault(emptyList()), siteName)
                ?.let { it.status to it.body } ?: route(path, query)
        }
        val bytes = body.toByteArray()
        val out = c.getOutputStream()
        val json = status.startsWith("200") || body.startsWith("{")
        out.write(("HTTP/1.1 $status\r\n" +
            "Content-Type: ${if (json) "application/json; charset=utf-8" else "text/plain"}\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n").toByteArray())
        if (method != "HEAD") out.write(bytes)
        out.flush()

        // api-secret is a hash, but don't keep it in a log anyway; everything else is useful evidence.
        val shownHeaders = headers.filterKeys { it != "api-secret" && it != "api_secret" && it != "authorization" }
            .map { (k, v) -> "$k=$v" }.joinToString("; ")
        val shownTarget = target.replace(Regex("([?&](token|secret)=)[^&]*"), "$1***")
        val line = "${iso(System.currentTimeMillis())} ${if (nightscout) "[ns] " else ""}$method $shownTarget -> " +
            "${status.substringBefore(' ')} ${bytes.size}B" +
            (if ("api-secret" in headers || "api_secret" in headers) " [api-secret]" else "") + " | $shownHeaders"
        Log.i(tag, line)
        synchronized(this) { runCatching { logFile.appendText(line + "\n") } }
        if (nightscout) { lastNightscoutRequest = System.currentTimeMillis() to "$method /$path"; return }
        lastRequest = System.currentTimeMillis() to "$method /$path"
        onRequest?.invoke()
    }

    private fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate {
        val k = URLDecoder.decode(it.substringBefore('='), "UTF-8")
        val v = URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        k to v
    }

    private fun route(path: String, q: Map<String, String>): Pair<String, String> = when (path) {
        "sgv.json", "api/v1/entries/sgv.json", "api/v1/entries.json", "api/v1/entries" -> "200 OK" to sgv(q, utc = path.startsWith("api/"))
        "pebble" -> "200 OK" to pebble()
        "status.json", "api/v1/status.json" -> "200 OK" to STATUS
        else -> "404 Not Found" to "Not found\n"
    }

    // MARK: responses (field order and number formats copied from Juggluco's server)

    /**
     * Newest first, at least [minGap] s apart. Watch apps ask for `interval=300` / `600` (Juggluco's parameter,
     * default 270); Libre readings jitter by a second or two, so allow up to 30 s of slack or a 300 s ask would skip to 6 min.
     */
    private fun spaced(count: Int, minGap: Long = SPACING): List<Bridge.Reading> = synchronized(readings) {
        val out = ArrayList<Bridge.Reading>()
        for (r in readings.descendingMap().values) {
            if (out.isNotEmpty() && out.last().epochSec - r.epochSec < minGap) continue
            out += r
            if (out.size >= count) break
        }
        out
    }

    /** mg/dL per 5 minutes: the live rate when we have it, else the slope to the previous reading. */
    private fun delta(r: Bridge.Reading): Double? {
        r.rate?.let { return it * 5 }
        val prev = synchronized(readings) { readings.lowerEntry(r.epochSec - 120)?.value } ?: return null
        val dt = r.epochSec - prev.epochSec
        if (dt > 15 * 60) return null
        return (r.mgdl - prev.mgdl) * 300.0 / dt
    }

    private fun sgv(q: Map<String, String>, utc: Boolean): String {
        // Monkey C apps send floats ("count=72.0"); Juggluco's parser stops at the '.', so do the same.
        val count = (q["count"]?.toDoubleOrNull()?.toInt() ?: 24).coerceIn(1, 1000)
        val minGap = q["interval"]?.toDoubleOrNull()?.toLong()?.let { (it - minOf(30, it / 10)).coerceAtLeast(0) } ?: SPACING
        val brief = q["brief_mode"]?.let { it.isEmpty() || it[0] !in "fFnN0" } ?: false
        val withSensor = q["sensor"]?.let { it.isEmpty() || it[0] !in "fFnN0" } ?: false
        val list = spaced(count, minGap)
        // the sensor when it isn't a Libre 3 (core/SensorOutputs): "Sukrara · Dexcom G7 / ONE+ (untested)"
        val device = app.mauri.sukrara.core.SensorOutputs.uploader("Sukrara", app.mauri.sukrara.core.ActiveSensor.profile.value)
        return list.mapIndexed { i, r ->
            val d = delta(r)
            buildString {
                append('{')
                if (!brief) {
                    val ds = if (utc) iso(r.epochSec * 1000, utc = true) else iso(r.epochSec * 1000)
                    append("\"_id\":\"${sensorSerial ?: "Sukrara"}#${r.epochSec / 60}\",\"device\":\"$device\",")
                    append("\"dateString\":\"$ds\",\"sysTime\":\"$ds\",")
                }
                append("\"date\":${r.epochSec}000,\"sgv\":${r.mgdl},")
                append("\"delta\":${String.format(Locale.US, "%.3f", d ?: 0.0)},\"direction\":\"${direction(d)}\",\"noise\":1")
                if (!brief) append(",\"filtered\":${r.mgdl}000,\"unfiltered\":${r.mgdl}000,\"rssi\":100,\"type\":\"sgv\"")
                if (i == 0) {
                    append(",\"units_hint\":\"mgdl\"")
                    if (withSensor) sensorStatus()?.let { append(",\"sensor_status\":\"$it\"") }
                }
                append('}')
            }
        }.joinToString(",\n", "[", "]\n")
    }

    private fun pebble(): String {
        val r = spaced(1).firstOrNull()
        val now = System.currentTimeMillis()
        val bgs = r?.let {
            val d = delta(it)
            "{\"sgv\":\"${it.mgdl}\",\"trend\":${trend(d)},\"direction\":\"${direction(d)}\",\"datetime\":${it.epochSec}000," +
                "\"bgdelta\":\"${String.format(Locale.US, "%.2f", d ?: 0.0)}\",\"iob\":\"0.00\"}"
        } ?: ""
        return "{\"status\":[{\"now\":$now}],\"bgs\":[$bgs],\"cals\":[]}"
    }

    /** "dd-mm-yy HH:MM (Xd Yh)": sensor start and age, as Juggluco reports it. */
    private fun sensorStatus(): String? {
        val start = sensorStart ?: return null
        val age = System.currentTimeMillis() / 1000 - start
        val f = SimpleDateFormat("dd-MM-yy HH:mm", Locale.US)
        return "${f.format(Date(start * 1000))} (${age / 86400}d ${age % 86400 / 3600}h)"
    }

    companion object {
        const val PORT = 17580
        private const val KEEP_SECONDS = 24 * 3600L
        private const val SPACING = 270L
        private const val STATUS = "{\"settings\":{\"units\":\"mg/dL\",\"thresholds\":{\"bgHigh\":185,\"bgLow\":65}}}"

        /** Nightscout trend names from a 5-minute delta (thresholds are per minute: 1, 2, 3 mg/dL). */
        fun direction(delta5: Double?): String {
            val m = (delta5 ?: return "NONE") / 5
            return when {
                m > 3 -> "DoubleUp"
                m > 2 -> "SingleUp"
                m > 1 -> "FortyFiveUp"
                m >= -1 -> "Flat"
                m >= -2 -> "FortyFiveDown"
                m >= -3 -> "SingleDown"
                else -> "DoubleDown"
            }
        }

        fun trend(delta5: Double?): Int = when (direction(delta5)) {
            "DoubleUp" -> 1; "SingleUp" -> 2; "FortyFiveUp" -> 3; "Flat" -> 4
            "FortyFiveDown" -> 5; "SingleDown" -> 6; "DoubleDown" -> 7; else -> 0
        }

        fun iso(ms: Long, utc: Boolean = false): String {
            val f = SimpleDateFormat(if (utc) "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" else "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
            if (utc) f.timeZone = TimeZone.getTimeZone("UTC")
            return f.format(Date(ms))
        }
    }
}
