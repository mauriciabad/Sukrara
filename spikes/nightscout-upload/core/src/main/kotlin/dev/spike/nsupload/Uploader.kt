// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.nsupload

import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone
import java.util.TreeSet
import kotlin.math.min
import kotlin.random.Random

/** The app's own storage, read by the uploader (Sukrara: the readings history and the TreatmentStore). */
interface LocalData {
    /** Own-sensor and followed readings with `epochMs` in [fromMs, toMs], oldest first. */
    fun readings(fromMs: Long, toMs: Long): List<Reading>
    fun treatment(id: String): Treatment?
}

/** Where the queue lives between runs (Sukrara: a file in `filesDir`, like the TreatmentStore). */
interface StateStore {
    fun load(): String?
    fun save(json: String)
}

class MemoryStateStore : StateStore {
    @Volatile var json: String? = null
    override fun load() = json
    override fun save(json: String) { this.json = json }
}

enum class Health { OK, IDLE, BACKING_OFF, AUTH_FAILED, CONFLICT }

data class SyncReport(
    val health: Health,
    val entries: Int = 0,
    val treatments: Int = 0,
    val deletes: Int = 0,
    val sensorEvents: Int = 0,
    val deviceStatus: Int = 0,
    val backfilled: Int = 0,
    val dropped: Int = 0,
    val pending: Int = 0,
    val nextAttemptMs: Long = 0,
    val message: String = "",
)

/**
 * The upload queue. Nothing here blocks the reading path: callers enqueue (cheap, in memory + one small file) and
 * call [sync] from a worker when the network is up (Sukrara: after each reading, plus a periodic job).
 *
 * - **Queue**: pending entry *timestamps* (the readings themselves stay in the app's store), pending treatment ids,
 *   sensor events and the newest battery level. Persisted after every change, so a process death loses nothing.
 * - **Dedupe**: a timestamp is queued once; the server dedupes again (v1 `sysTime`+`type`, v3 `identifier`), so a
 *   retry after a lost response never creates a duplicate.
 * - **Retry/backoff**: 30 s doubling to 15 min with ±20 % jitter, or the server's `Retry-After`; 401/403 stop until
 *   [resetAuth]; a document the server rejects (other 4xx) is dropped and reported.
 * - **Backfill**: the first run and every run after a failure diff the server against the local history (at most
 *   [NsConfig.maxBackfillMs], then [NsConfig.reconcileWindowMs] every [NsConfig.reconcileEveryMs]) and queue every
 *   own reading the server is missing: an outage, a wiped database or readings that arrived late from the sensor's
 *   own backfill all heal the same way.
 * - **Roles**: [Origin.FOLLOWED] readings are never queued, unless they belong to [NsConfig.relayPerson].
 * - **Conflict**: if the server got sgv entries in the last 30 min from another `device` (Juggluco, xDrip+…), entries
 *   pause until the user confirms ([acceptConflict]); two uploaders would show a zig-zag to every follower.
 */
class Uploader(
    private val cfg: NsConfig,
    private val local: LocalData,
    private val store: StateStore,
    http: Http,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: TimeZone = TimeZone.getDefault(),
    private val random: Random = Random.Default,
    private val log: (String) -> Unit = {},
) {
    private val client = NsClient(cfg, http, now)

    private val pendingEntries = TreeSet<Long>()
    private val pendingTreatments = LinkedHashMap<String, Long>()   // id → updatedMs queued
    private val pendingSensors = ArrayList<SensorStart>()
    private var pendingBattery: UploaderBattery? = null
    private var sentBatteryPercent = -1
    private var sentBatteryMs = 0L
    private var failures = 0
    private var nextAttemptMs = 0L
    private var authError: String? = null
    private var lastReconcileMs = 0L
    private var failingSinceMs = 0L
    private var conflictDevice: String? = null
    private var conflictAccepted = false

    init { restore() }

    // MARK: enqueue

    fun onReadings(list: List<Reading>) = synchronized(this) {
        if (!cfg.uploadEntries) return
        var added = 0
        for (r in list) if (uploadable(r) && pendingEntries.add(r.epochMs)) added++
        if (added > 0) persist()
    }

    fun onTreatmentChanged(t: Treatment) = synchronized(this) {
        if (!cfg.uploadTreatments) return
        pendingTreatments[t.id] = t.updatedMs
        persist()
    }

    fun onSensorStart(s: SensorStart) = synchronized(this) {
        if (!cfg.uploadTreatments || pendingSensors.any { it.serial == s.serial }) return
        pendingSensors += s
        persist()
    }

    /** Sent when the level changed, or every 30 min at most otherwise (xDrip+: on change only). */
    fun onBattery(b: UploaderBattery) = synchronized(this) {
        if (!cfg.uploadDeviceStatus) return
        if (b.percent == sentBatteryPercent && b.epochMs - sentBatteryMs < 30 * NsConfig.MINUTE) return
        pendingBattery = b
        persist()
    }

    fun resetAuth() = synchronized(this) { authError = null; failures = 0; nextAttemptMs = 0; persist() }

    fun acceptConflict() = synchronized(this) { conflictAccepted = true; conflictDevice = null; persist() }

    fun pendingCount(): Int = synchronized(this) { pendingEntries.size + pendingTreatments.size + pendingSensors.size + (if (pendingBattery != null) 1 else 0) }

    private fun uploadable(r: Reading) = when (r.origin) {
        Origin.OWN_SENSOR -> true
        Origin.FOLLOWED -> cfg.relayPerson != null && r.followedPerson == cfg.relayPerson
    }

    // MARK: sync

    fun sync(): SyncReport = synchronized(this) {
        val t = now()
        authError?.let { return SyncReport(Health.AUTH_FAILED, pending = pendingCount(), message = it) }
        if (t < nextAttemptMs) return SyncReport(Health.BACKING_OFF, pending = pendingCount(), nextAttemptMs = nextAttemptMs)

        var rep = SyncReport(Health.OK)
        try {
            // 1. Diff against the server: first run, after an outage, and periodically.
            val needReconcile = cfg.uploadEntries && (lastReconcileMs == 0L || failingSinceMs > 0 || t - lastReconcileMs >= cfg.reconcileEveryMs)
            if (needReconcile) {
                val window = when {
                    lastReconcileMs == 0L -> cfg.maxBackfillMs
                    failingSinceMs > 0 -> min(cfg.maxBackfillMs, maxOf(cfg.reconcileWindowMs, t - failingSinceMs + cfg.reconcileWindowMs))
                    else -> cfg.reconcileWindowMs
                }
                val r = reconcile(t - window, t) ?: return fail(rep)
                rep = rep.copy(backfilled = r)
            }

            // 2. Drop what is older than the backfill horizon (Nightscout would prune it anyway).
            val horizon = t - cfg.maxBackfillMs
            val old = pendingEntries.headSet(horizon).size
            if (old > 0) { pendingEntries.headSet(horizon).clear(); rep = rep.copy(dropped = rep.dropped + old) }

            // 3. Entries, oldest first, in batches.
            if (conflictDevice == null || conflictAccepted) {
                var budget = if (cfg.api == NsApi.V3) cfg.v3MaxPerRun else Int.MAX_VALUE
                while (pendingEntries.isNotEmpty() && budget > 0) {
                    val stamps = pendingEntries.asSequence().take(min(cfg.batchSize, budget)).toList()
                    val docs = entryDocs(stamps)
                    if (docs.isEmpty()) { stamps.forEach(pendingEntries::remove); continue }   // gone from local store
                    when (val o = client.postEntries(docs)) {
                        is Outcome.Ok -> {
                            stamps.forEach(pendingEntries::remove)
                            val bad = if (cfg.api == NsApi.V3) client.lastRejected else 0
                            rep = rep.copy(entries = rep.entries + docs.size - bad, dropped = rep.dropped + bad)
                            persist()
                        }
                        is Outcome.Rejected -> { stamps.forEach(pendingEntries::remove); rep = rep.copy(dropped = rep.dropped + stamps.size, message = o.reason); persist() }
                        else -> return fail(rep, o)
                    }
                    budget -= stamps.size
                }
            }

            // 4. Treatments: upsert or delete, one at a time (few, and each needs its own outcome).
            for ((id, queuedU) in pendingTreatments.entries.toList()) {
                val tr = local.treatment(id)
                val o = when {
                    tr == null || tr.deleted -> client.deleteTreatment(id).also { if (it is Outcome.Ok) rep = rep.copy(deletes = rep.deletes + 1) }
                    else -> client.upsertTreatment(NsDocs.treatment(tr, cfg.device, zone)).also { if (it is Outcome.Ok) rep = rep.copy(treatments = rep.treatments + 1) }
                }
                when (o) {
                    is Outcome.Ok, is Outcome.Rejected -> {
                        // a newer edit queued while we were sending stays queued
                        if (pendingTreatments[id] == queuedU) pendingTreatments.remove(id)
                        if (o is Outcome.Rejected) rep = rep.copy(dropped = rep.dropped + 1, message = o.reason)
                        persist()
                    }
                    else -> return fail(rep, o)
                }
            }

            // 5. Sensor start → Sensor Change + Sensor Start treatments.
            for (s in pendingSensors.toList()) {
                when (val o = client.postTreatments(NsDocs.sensorEvents(s, cfg.device, zone))) {
                    is Outcome.Ok, is Outcome.Rejected -> { pendingSensors.remove(s); rep = rep.copy(sensorEvents = rep.sensorEvents + if (o is Outcome.Ok) 2 else 0); persist() }
                    else -> return fail(rep, o)
                }
            }

            // 6. Uploader battery.
            pendingBattery?.let { b ->
                when (val o = client.postDeviceStatus(NsDocs.deviceStatus(b, cfg.device, zone))) {
                    is Outcome.Ok, is Outcome.Rejected -> {
                        pendingBattery = null; sentBatteryPercent = b.percent; sentBatteryMs = b.epochMs
                        if (o is Outcome.Ok) rep = rep.copy(deviceStatus = 1)
                        persist()
                    }
                    else -> return fail(rep, o)
                }
            }

            failures = 0; nextAttemptMs = 0; failingSinceMs = 0
            persist()
            val health = when {
                conflictDevice != null && !conflictAccepted -> Health.CONFLICT
                pendingEntries.isNotEmpty() -> Health.OK      // v3 budget: more next run
                else -> if (rep.entries + rep.treatments + rep.deletes + rep.sensorEvents + rep.deviceStatus > 0) Health.OK else Health.IDLE
            }
            return rep.copy(health = health, pending = pendingCount(),
                message = if (health == Health.CONFLICT) "another uploader is active: $conflictDevice" else rep.message)
        } finally {
            persist()
        }
    }

    /** Queue local own readings the server lacks; detect a second uploader. Null = the server could not be read. */
    private fun reconcile(from: Long, to: Long): Int? {
        val (server, o) = client.entriesBetween(from, to, max = ((to - from) / 30_000).toInt().coerceIn(100, 50_000))
        if (server == null) { lastOutcome = o; return null }
        val recent = server.filter { it.key >= to - 30 * NsConfig.MINUTE && it.value != cfg.device }
        conflictDevice = if (recent.isEmpty()) null else recent.values.toSortedSet().joinToString()
        var added = 0
        for (r in local.readings(from, to)) {
            if (!uploadable(r) || server.containsKey(r.epochMs)) continue
            if (pendingEntries.add(r.epochMs)) added++
        }
        lastReconcileMs = to
        log("reconcile ${(to - from) / NsConfig.HOUR} h: server has ${server.size}, queued $added missing")
        return added
    }

    private var lastOutcome: Outcome? = null

    private fun entryDocs(stamps: List<Long>): List<JSONObject> {
        if (stamps.isEmpty()) return emptyList()
        // one read covers the batch plus the reading before the first (for its delta)
        val rows = local.readings(stamps.first() - 15 * NsConfig.MINUTE, stamps.last()).filter(::uploadable)
        val wanted = stamps.toHashSet()
        val out = ArrayList<JSONObject>(stamps.size)
        var prev: Reading? = null
        for (r in rows) {
            if (r.epochMs in wanted) out += NsDocs.entry(r, prev, if (r.origin == Origin.FOLLOWED) cfg.device + " relay" else cfg.device, zone, cfg.api)
            prev = r
        }
        return out
    }

    private fun fail(rep: SyncReport, o: Outcome? = lastOutcome): SyncReport {
        val t = now()
        if (o is Outcome.AuthFailed) {
            authError = o.reason
            persist()
            return rep.copy(health = Health.AUTH_FAILED, pending = pendingCount(), message = o.reason)
        }
        failures++
        if (failingSinceMs == 0L) failingSinceMs = t
        val base = min(15 * NsConfig.MINUTE, 30_000L shl min(failures - 1, 10))
        val jittered = (base * (0.8 + 0.4 * random.nextDouble())).toLong()
        val wait = (o as? Outcome.Retry)?.afterMs?.coerceAtLeast(jittered) ?: jittered
        nextAttemptMs = t + wait
        persist()
        return rep.copy(health = Health.BACKING_OFF, pending = pendingCount(), nextAttemptMs = nextAttemptMs,
            message = (o as? Outcome.Retry)?.reason ?: o.toString())
    }

    // MARK: persistence

    private fun persist() {
        val j = JSONObject()
            .put("v", 1)
            .put("entries", JSONArray(pendingEntries.toList()))
            .put("treatments", JSONObject(pendingTreatments as Map<*, *>))
            .put("sensors", JSONArray(pendingSensors.map { JSONObject().put("serial", it.serial).put("t", it.epochMs) }))
            .put("battery", pendingBattery?.let { JSONObject().put("p", it.percent).put("c", it.charging).put("t", it.epochMs) })
            .put("sentBattery", JSONObject().put("p", sentBatteryPercent).put("t", sentBatteryMs))
            .put("failures", failures).put("next", nextAttemptMs).put("failingSince", failingSinceMs)
            .put("auth", authError).put("reconciled", lastReconcileMs)
            .put("conflict", conflictDevice).put("conflictAccepted", conflictAccepted)
        store.save(j.toString())
    }

    private fun restore() {
        val j = store.load()?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        j.optJSONArray("entries")?.let { a -> for (i in 0 until a.length()) pendingEntries.add(a.getLong(i)) }
        j.optJSONObject("treatments")?.let { o -> o.keys().forEach { pendingTreatments[it] = o.getLong(it) } }
        j.optJSONArray("sensors")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { pendingSensors += SensorStart(it.getString("serial"), it.getLong("t")) } }
        j.optJSONObject("battery")?.let { pendingBattery = UploaderBattery(it.getInt("p"), it.getBoolean("c"), it.getLong("t")) }
        j.optJSONObject("sentBattery")?.let { sentBatteryPercent = it.getInt("p"); sentBatteryMs = it.getLong("t") }
        failures = j.optInt("failures"); nextAttemptMs = j.optLong("next"); failingSinceMs = j.optLong("failingSince")
        authError = j.optString("auth").takeIf { it.isNotEmpty() && !j.isNull("auth") }
        lastReconcileMs = j.optLong("reconciled")
        conflictDevice = j.optString("conflict").takeIf { it.isNotEmpty() && !j.isNull("conflict") }
        conflictAccepted = j.optBoolean("conflictAccepted")
    }
}
