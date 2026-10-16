// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.libreview

import android.content.Context
import android.util.Log
import app.mauri.sukrara.treatments.Treatment
import app.mauri.sukrara.treatments.TreatmentStore
import java.io.File
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date
import java.util.TreeMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Uploads the treatment log (and, in the direct flavor, sensor glucose) to LibreView.
 * Policy from SPEC §1: our own rows go up at once, rows from the Mac only if the Mac hasn't
 * uploaded them within 2 minutes. Glucose is opt-in and only from the moment it was switched on,
 * so the spike's footprint in LibreView is a bounded window.
 */
class LibreViewUploader(context: Context, private val store: TreatmentStore) {
    private val tag = "Sukrara.libreview"
    private val prefs = context.getSharedPreferences("libreview", Context.MODE_PRIVATE)
    private val client = LibreViewClient(prefs)
    private val ledger = File(context.filesDir, "lv-ledger.jsonl")
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val firstSeen = HashMap<String, Long>()

    /**
     * What goes up once Features.libreview (the parent switch in Settings) is on. Every part defaults to ON: turning
     * the upload on uploads everything, like LibreLink. Glucose starts from the moment it (or the upload) is switched on.
     */
    data class Settings(val email: String, val hasPassword: Boolean, val glucose: Boolean, val treatments: Boolean, val takeover: Boolean)

    val settings = MutableStateFlow(readSettings())
    val status = MutableStateFlow("off")
    val deviceId get() = client.deviceId

    // glucose from the sensor (direct flavor)
    private var serial: String? = null
    private var startEpoch = 0L
    private var current: LibreViewPayload.Glucose? = null
    private val history = TreeMap<Int, LibreViewPayload.Glucose>()

    private fun readSettings() = Settings(
        prefs.getString("email", "")!!, !prefs.getString("password", null).isNullOrEmpty(),
        prefs.getBoolean("glucose", true), prefs.getBoolean("treatments", true), prefs.getBoolean("takeover", true))

    fun configure(email: String? = null, password: String? = null, glucose: Boolean? = null, treatments: Boolean? = null,
                  takeover: Boolean? = null) {
        prefs.edit().apply {
            email?.let { putString("email", it.trim()); remove("token") }
            password?.let { putString("password", it); remove("token") }
            treatments?.let { putBoolean("treatments", it) }
            takeover?.let { putBoolean("takeover", it) }
            glucose?.let { putBoolean("glucose", it); if (!it) remove("glucoseFrom") }
        }.apply()
        settings.value = readSettings()
        kick()
    }

    /** Signs out: forgets the login (the glucose window and upload ledger stay). */
    fun signOut() {
        prefs.edit().remove("email").remove("password").remove("token").apply()
        settings.value = readSettings()
        kick()
    }

    /** Epoch s from which sensor glucose is uploaded, or null while glucose isn't uploading. */
    val glucoseFrom: Long? get() = prefs.getLong("glucoseFrom", 0).takeIf { it > 0 }

    fun kick() { wake.trySend(Unit) }

    fun onSensor(serial: String, startEpoch: Long) { this.serial = serial; this.startEpoch = startEpoch }

    fun onCurrent(g: LibreViewPayload.Glucose) {
        synchronized(history) { current = g }
        if (settings.value.glucose) kick()        // like the Libre 3 app: one upload per new reading
    }

    fun onHistory(points: List<LibreViewPayload.Glucose>) = synchronized(history) {
        points.forEach { history[it.lifeCount] = it }
        while (history.size > 3 * 24 * 12) history.pollFirstEntry()
    }

    fun start(scope: CoroutineScope) = scope.launch {
        while (true) {
            withTimeoutOrNull(app.mauri.sukrara.features.Features.value.libreviewIntervalMin * 60_000L) { wake.receive() }
            runCatching { cycle() }.onFailure {
                Log.w(tag, "libreview", it)
                status.value = "error: ${it.message}"
            }
        }
    }

    private fun cycle() {
        val s = settings.value
        // Sukrara: the parent switch is Features.libreview (settings screen / DebugReceiver), default OFF. Glucose goes
        // up from the moment upload (and glucose) was switched on: switching off ends the window.
        if (!app.mauri.sukrara.features.Features.value.libreview) {
            prefs.edit().remove("glucoseFrom").apply()
            status.value = "off"; return
        }
        if (s.glucose && glucoseFrom == null) prefs.edit().putLong("glucoseFrom", System.currentTimeMillis() / 1000).apply()
        val password = prefs.getString("password", null)
        if (s.email.isEmpty() || password.isNullOrEmpty()) { status.value = "set email + password"; return }

        val now = System.currentTimeMillis()
        val rows = if (!s.treatments) emptyList() else store.all().filter { it.needsUpload }.filter { tr ->
            tr.o == store.origin || now - firstSeen.getOrPut("${tr.id}@${tr.u}") { now } > 120_000
        }
        val sensor = glucoseBatch(s)
        if (rows.isEmpty() && sensor == null) {
            if (status.value.startsWith("error") || status.value == "off") status.value = "idle"
            return
        }

        if (client.token == null) login(s.email, password, s.takeover) ?: return
        if (sensor != null && !prefs.getBoolean("put:${sensor.serial}", false)) {
            val (st, reason) = client.putSensor(sensor.serial.takeLast(9))
            Log.i(tag, "libreview putSensor status $st $reason")
            if (st == 0) prefs.edit().putBoolean("put:${sensor.serial}", true).apply()
        }

        val zone = ZoneId.systemDefault()
        var result = client.postMeasurements(LibreViewPayload.payload(rows, client.deviceId, client.token!!, now / 1000, zone, sensor))
        if (result.first == 20 && "wrongDeviceInToken" in result.second) {
            login(s.email, password, s.takeover) ?: return
            result = client.postMeasurements(LibreViewPayload.payload(rows, client.deviceId, client.token!!, now / 1000, zone, sensor))
        }
        Log.i(tag, "libreview post: ${rows.size} treatment(s), glucose ${sensor?.history?.size ?: 0}+${if (sensor?.current != null) 1 else 0} → status ${result.first} ${result.second}")
        if (result.first != 0) { status.value = "rejected: ${result.first} ${result.second}"; return }

        store.markUploaded(rows)
        writeLedger(rows, now)
        sensor?.let { b ->
            prefs.edit().apply {
                putString("sent:${b.serial}", (sentHistory(b.serial) + b.history.map { it.lifeCount }).sorted().takeLast(1000).joinToString(","))
                b.current?.let { putInt("cur:${b.serial}", it.lifeCount) }
                if (b.sendStart) putBoolean("start:${b.serial}", true)
            }.apply()
        }
        status.value = "ok ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now))}" +
            " · ${rows.size} treatment(s)" + (sensor?.let { " · ${it.history.size} hist + ${if (it.current != null) 1 else 0} current" } ?: "")
    }

    /** Returns null (and sets [status]) when we may not log in. */
    private fun login(email: String, password: String, takeover: Boolean): Unit? {
        try {
            client.auth(email, password, setDevice = false)
        } catch (e: LibreViewClient.OtherDevice) {
            if (!takeover) {
                status.value = "account is bound to another device (Juggluco?) — enable take-over to proceed"
                return null
            }
            Log.i(tag, "libreview: taking over the account's device (SetDevice:true)")
            client.auth(email, password, setDevice = true)
        }
        return Unit
    }

    private fun glucoseBatch(s: Settings): LibreViewPayload.Sensor? {
        val serial = serial ?: return null
        if (!s.glucose) return null
        val from = glucoseFrom ?: return null
        val sent = sentHistory(serial)
        val lastCur = prefs.getInt("cur:$serial", -1)
        val (cur, hist) = synchronized(history) {
            current?.takeIf { it.lifeCount > lastCur && it.epochSec >= from && System.currentTimeMillis() / 1000 - it.epochSec < 5 * 60 } to
                history.values.filter { it.epochSec >= from && it.lifeCount !in sent }   // backfill arrives out of order
        }
        if (cur == null && hist.isEmpty()) return null
        return LibreViewPayload.Sensor(serial, startEpoch, cur, hist, sendStart = !prefs.getBoolean("start:$serial", false))
    }

    /** Life counts of 5-minute history already uploaded for this sensor (the last 1000 ≈ 3.5 days). */
    private fun sentHistory(serial: String): Set<Int> =
        prefs.getString("sent:$serial", "")!!.split(',').mapNotNull { it.toIntOrNull() }.toSet()

    private fun writeLedger(rows: List<Treatment>, now: Long) {
        if (rows.isEmpty()) return
        ledger.appendText(rows.joinToString("") { tr ->
            buildJsonObject {
                put("rn", LibreViewPayload.recordNumber(tr, tr.u)); put("u", tr.u); put("lv", tr.lv); put("id", tr.id); put("k", tr.k); put("t", tr.t); put("a", tr.a)
                put("n", tr.n); put("del", tr.del); put("dev", client.deviceId); put("at", now / 1000)
            }.toString() + "\n"
        })
    }
}
