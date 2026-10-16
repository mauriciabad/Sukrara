// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.follower

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import app.mauri.sukrara.core.EventLog
import app.mauri.sukrara.core.Notifications
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.RelayPool
import app.mauri.sukrara.core.RelayStats
import app.mauri.sukrara.core.SecureStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.nostrdevkit.sdk.Event
import org.nostrdevkit.sdk.Keys

data class FollowerUi(
    val status: String = "none",          // none | requested | active | revoked
    val code: String? = null,
    val ownerPub: String? = null,
    val fPub: String? = null,
    val name: String? = null,
    val last: FollowedReading? = null,
    val chart: List<FollowedReading> = emptyList(),
    val count: Int = 0,
    val rejects: Int = 0,
    val lastReject: String? = null,
    val epoch: Int = 0,
    val devices: List<String> = emptyList(),
    val low: Int = 65,
    val high: Int = 185,
    val noDataMin: Int = 20,
    val alarmsOn: Boolean = true,
    val testUnfiltered: Boolean = false,
    val canWrite: Boolean = false,
    val ble: String = "off",
    val lastVia: String? = null,
)

/**
 * The follower role ("I follow someone"). Trusts exactly one thing from the invite: the identity key I.
 * Accepts key deliveries only if the NIP-59 seal is signed by I, rosters only if authored by I, and data only if
 * authored by a roster device, tagged with a known epoch and decryptable. Everything lands in its own `follower.db`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowerEngine(private val ctx: Context) {
    val log = EventLog("follower")
    private val store = SecureStore(File(ctx.filesDir, "follower-state.bin"), "cgmshare.follower")
    private val db = FollowerDb(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serial = Dispatchers.IO.limitedParallelism(1)
    private val wake = ctx.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sukrara:follower").apply { setReferenceCounted(false) }

    private var st: FollowerState? = runCatching { store.read()?.let(FollowerState::fromJson) }
        .onFailure { log.i("could not read follower state: $it") }.getOrNull()
    private var pool: RelayPool? = null
    private var running = false
    private val seen = object : LinkedHashMap<String, Unit>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 4000
    }
    private val rekeyAsked = HashMap<String, Long>()
    private var lastAcceptedAt = 0L
    private var subscribedDevices: List<String> = emptyList()
    private var alarmKind: String? = null
    private var alarmAt = 0L
    private var noDataAlerted = false
    private var lastRejectLogAt = 0L

    private val _ui = MutableStateFlow(FollowerUi())
    val ui: StateFlow<FollowerUi> = _ui
    private val _relays = MutableStateFlow<Map<String, RelayStats>>(emptyMap())
    val relayStats: StateFlow<Map<String, RelayStats>> = _relays

    init { scope.launch(serial) { publishUi() } }

    /**
     * Every batch of newly stored readings (live or backfilled chunks), oldest first. [FollowerExtras] feeds the
     * home-screen widgets and the local Garmin / Nightscout server from it, so they work in the follower role too.
     */
    @Volatile var onReadings: ((List<app.mauri.sukrara.core.Reading>, String) -> Unit)? = null

    /** The follower DB's readings since [fromS] (oldest first), for seeding widgets / the local API. */
    fun history(fromS: Long): List<FollowedReading> = db.since(fromS)

    // ============================================================================================ Bluetooth (inv. 07)

    val ble by lazy { app.mauri.sukrara.core.BleFollower(ctx, log::i, ::onEvent).also { b ->
        attach(b)
        scope.launch { b.state.collect { v -> _ui.update { it.copy(ble = v) } } }
    } }

    fun scanNearby(onFound: (app.mauri.sukrara.core.NearbyOwner) -> Unit) = ble.scan(onFound)
    fun stopScan() = ble.stopScan()

    /** Nearby pairing: connect over BLE, take the invite (I, token, relays) from the owner's INFO, send the request over BLE. */
    suspend fun joinNearby(address: String, name: String): String? {
        ble.stopScan()
        val got = kotlinx.coroutines.CompletableDeferred<JSONObject>()
        ble.onInfo = { if (!got.isCompleted) got.complete(it) }
        ble.connect(address)
        val info = kotlinx.coroutines.withTimeoutOrNull(20_000) { got.await() } ?: return "Could not reach that phone over Bluetooth"
        ble.onInfo = null
        val pair = info.optJSONObject("pair") ?: return "The owner has not turned on \"Pair nearby\""
        val link = Proto.inviteLink(info.getString("i"), pair.getString("t"), pair.getLong("x"),
            Proto.strings(info.optJSONArray("relays")))
        log.i("nearby: INFO from $address (owner ${Proto.shortNpub(info.getString("i"))})")
        val err = join(link, name)
        if (err == null) withContext(serial) { st?.bleAddr = address; save() }
        return err
    }

    /** Add a treatment (only if the roster lists this follower as a writer); goes out over BLE + relays. */
    fun addTreatment(k: String, a: Double, note: String) = scope.launch(serial) {
        val s = st ?: return@launch
        val sid = s.sid ?: return@launch
        if (s.fPub !in s.writers) log.i("NOTE: not in roster writers — the owner will reject this entry")
        val ep = s.epochs.values.maxByOrNull { it.n } ?: return@launch
        val now = Proto.nowS()
        val tr = JSONObject().put("v", 1).put("id", java.util.UUID.randomUUID().toString()).put("k", k).put("a", a).put("t", now)
            .put("n", note.take(100)).put("u", System.currentTimeMillis()).put("del", false).put("o", "follower")
        publish(Proto.treatmentEvent(s.f, Proto.pub(ep.g), sid, tr, now), app.mauri.sukrara.core.Lane.CONTROL)
        log.i("treatment sent: $k $a \"$note\" (epoch ${ep.n}, ble ${ble.state.value})")
    }

    fun hasJoined() = st != null

    private fun save() { st?.let { store.write(it.toJson()) } }

    private fun pool(): RelayPool {
        val s = st!!
        return pool ?: RelayPool(s.relays, log::i) { st?.f }.also { p ->
            pool = p
            scope.launch { p.stats.collect { _relays.value = it } }
        }
    }

    // ============================================================================================ join

    /** Parse the invite, make F, send the access request (gift wrap to I). Returns an error or null. */
    suspend fun join(link: String, name: String): String? = withContext(serial) {
        val inv = Proto.parseInvite(link) ?: return@withContext "Not a Sukrara invite link"
        if (inv.expiry < Proto.nowS()) return@withContext "This invite expired — ask for a new one"
        if (inv.relays.isEmpty()) return@withContext "Invite has no relays"
        val f = Keys.generate()
        val code = Proto.matchCode(Proto.pub(f), inv.token)
        st = FollowerState(f, name.ifBlank { "Follower" }.take(40), inv.iPub, inv.token, inv.relays, code, Proto.nowS())
        save()
        log.i("joining ${Proto.shortNpub(inv.iPub)} via ${inv.relays.size} relays as '${st!!.name}' F=${Proto.pub(f)} code $code")
        startLocked()
        sendRequest()
        publishUi()
        null
    }

    private fun sendRequest() {
        val s = st ?: return
        val body = JSONObject().put("v", 2).put("type", "request").put("token", s.token).put("name", s.name)
        publish(Proto.gift(s.f, s.iPub, Proto.RUMOR_TO_OWNER, body, ttlS = 7 * Proto.DAY), app.mauri.sukrara.core.Lane.CONTROL)
        log.i("access request sent to ${Proto.shortNpub(s.iPub)}")
    }

    /** Forget everything (cancel a pending request / stop following). */
    suspend fun leave() = withContext(serial) {
        pool?.close(); pool = null
        ble.disconnect()
        running = false
        st = null
        store.wipe()
        db.wipe()   // clear in place: deleting the file under an open helper would keep the old rows alive
        cancelWatchdog()
        app.mauri.sukrara.widgets.SukraraHooks.clear(ctx)   // widgets must not keep showing the old wearer's values
        log.i("left: follower state wiped, follower.db now has ${db.count()} readings")
        publishUi()
    }

    // ============================================================================================ lifecycle

    fun start() = scope.launch(serial) { startLocked() }

    private fun startLocked() {
        val s = st ?: return
        if (running) return
        running = true
        pool().subscribe("inbox", { listOf(JSONObject()
            .put("kinds", JSONArray().put(Proto.WRAP))
            .put("#p", JSONArray().put(s.fPub))
            .put("since", Proto.nowS() - 3 * Proto.DAY)) }, ::onEvent)
        if (s.status == FollowerState.ACTIVE) subscribeData()
        s.bleAddr?.let { ble.connect(it) }
        lastAcceptedAt = db.latest()?.received ?: 0
        scheduleWatchdog(6 * 60_000L)
        log.i("follower started: status ${s.status}, ${s.epochs.size} epoch key(s), ${s.devices.size} device(s), ${db.count()} readings stored")
    }

    fun stop() = scope.launch(serial) {
        running = false
        ble.disconnect()
        pool?.close(); pool = null
        subscribedDevices = emptyList()
    }

    private fun authorsOrAll(j: JSONObject, authors: List<String>): JSONObject =
        if (st?.testUnfiltered == true) j else j.put("authors", JSONArray(authors))

    private fun subscribeData() {
        val s = st ?: return
        val sid = s.sid ?: return
        val p = pool()
        p.subscribe("roster", { listOf(authorsOrAll(JSONObject()
            .put("kinds", JSONArray().put(Proto.CHUNK))
            .put("#d", JSONArray().put(Proto.rosterD(sid))), listOf(s.iPub))) }, ::onEvent)
        if (s.devices.isEmpty()) return
        subscribedDevices = s.devices
        p.subscribe("chunks", {
            val now = Proto.nowS()
            val since = maxOf(now - 13 * 3600, lastAcceptedAt - 900)
            listOf(authorsOrAll(JSONObject()
                .put("kinds", JSONArray().put(Proto.CHUNK))
                .put("#c", JSONArray().put(Proto.chunkC(sid)))
                .put("since", since), st?.devices ?: emptyList()))
        }, ::onEvent)
        p.subscribe("live", { listOf(authorsOrAll(JSONObject()
            .put("kinds", JSONArray().put(Proto.LIVE))
            .put("since", Proto.nowS() - 120), st?.devices ?: emptyList())) }, ::onEvent)
        log.i("subscribed: roster + chunks (#c) + live, authors ${s.devices.map { it.take(12) }}${if (s.testUnfiltered) " [TEST: no authors filter]" else ""}")
    }

    private fun unsubscribeData() {
        pool?.unsubscribe("roster"); pool?.unsubscribe("chunks"); pool?.unsubscribe("live")
        subscribedDevices = emptyList()
    }

    /** One-shot backfill (after a new key): the last 13 h of chunks + recent live. */
    /**
     * One-shot chunk fetch. After a new key / roster: the full 13 h (older events were unreadable before).
     * Watchdog: only chunks (re)published since shortly before the last accepted event — chunks are republished
     * every 5 min, so that covers the gap without re-downloading 13 h of history on every stale check.
     */
    private fun backfill(why: String, full: Boolean = true) {
        val s = st ?: return
        val sid = s.sid ?: return
        if (s.devices.isEmpty()) return
        scope.launch {
            wake.acquire(30_000)
            try {
                val now = Proto.nowS()
                val got = pool().fetch(listOf(authorsOrAll(JSONObject()
                    .put("kinds", JSONArray().put(Proto.CHUNK))
                    .put("#c", JSONArray().put(Proto.chunkC(sid)))
                    .put("since", if (full) now - 13 * 3600 else maxOf(now - 13 * 3600, lastAcceptedAt - 900)), s.devices)), 12_000)
                withContext(serial) {
                    val before = db.count()
                    got.forEach { (ev, via) -> onData(ev, via, force = true) }
                    log.i("backfill ($why): ${got.size} chunk event(s), +${db.count() - before} readings")
                }
            } finally { if (wake.isHeld) wake.release() }
        }
    }

    // ============================================================================================ transport seam

    /** Extra transports (e.g. BLE, next spike). Relays are always there; see [Transport]. */
    private val extra = java.util.concurrent.CopyOnWriteArrayList<app.mauri.sukrara.core.Transport>()

    fun attach(t: app.mauri.sukrara.core.Transport) { extra += t }

    private fun publish(ev: Event, lane: app.mauri.sukrara.core.Lane, key: String? = null) {
        pool().publish(ev, lane, key)
        extra.forEach { it.publish(ev, lane, key) }
    }

    /**
     * THE entry point for every incoming event, from any transport. [via] = `relay:<host>` / `ble:<peer>`.
     * All acceptance rules live behind this call (wraps → [onWrap], data/roster → [onData]); transports trust nothing.
     */
    fun onEvent(ev: JSONObject, via: String) {
        scope.launch(serial) {
            when (ev.optInt("kind")) {
                Proto.WRAP -> onWrap(ev, via)
                Proto.LIVE, Proto.CHUNK -> onData(ev, via)
            }
        }
    }

    // ============================================================================================ gift wraps

    private fun onWrap(ev: JSONObject, via: String) {
        val s = st ?: return
        val id = ev.optString("id")
        if (seen.put("w$id", Unit) != null) return
        val e = runCatching { Event.fromJson(ev.toString()) }.getOrNull() ?: return
        if (!e.verify()) return reject("wrap: bad signature", null, via)
        val u = Proto.unwrap(s.f, e) ?: return
        if (u.kind != Proto.RUMOR_TO_FOLLOWER || u.body.optInt("v") != 2) return
        if (u.sender != s.iPub) return reject("key delivery not sealed by the identity key", u.sender, via)
        when (u.body.optString("type")) {
            "key" -> {
                val g = runCatching { Keys.parse(u.body.getString("g")) }.getOrNull() ?: return reject("delivery: bad key", u.sender, via)
                val n = u.body.optInt("epoch")
                val sid = u.body.optString("sid")
                if (s.sid != null && s.sid != sid) return reject("delivery for another stream id", u.sender, via)
                val ep = FollowerEpoch(n, g)
                val isNew = s.epochs.put(ep.gk, ep) == null
                s.sid = sid
                s.lastKeyAt = maxOf(s.lastKeyAt, u.createdAt)
                if (n > s.rosterEpoch) {
                    s.devices = Proto.strings(u.body.optJSONArray("devices"))
                    s.rosterEpoch = n
                    s.rosterCreatedAt = 0
                }
                Proto.strings(u.body.optJSONArray("relays")).takeIf { it.isNotEmpty() }?.let { s.relays = it; pool().setRelays(it) }
                val wasRevoked = s.status == FollowerState.REVOKED
                if (s.revokedAt < s.lastKeyAt) s.status = FollowerState.ACTIVE
                save()
                if (!isNew && !(wasRevoked && s.status == FollowerState.ACTIVE)) return
                log.i("KEY accepted from identity via ${short(via)}: epoch $n gk ${ep.gk}, devices ${s.devices.map { it.take(12) }}")
                if (s.status == FollowerState.ACTIVE) {
                    Notifications.cancel(ctx, Notifications.ID_STATUS)
                    subscribeData()
                    backfill("new key epoch $n")
                    if (s.bleAddr != null || via.startsWith("ble:")) ble.hello()   // inv. 07: BLE backlog is now readable
                }
                publishUi()
            }
            "revoked" -> {
                if (u.createdAt < s.lastKeyAt) return log.i("old revoked notice (before the latest key) ignored")
                if (s.status == FollowerState.REVOKED) return
                s.revokedAt = u.createdAt
                s.status = FollowerState.REVOKED
                save()
                unsubscribeData()
                log.i("REVOKED by the owner (notice via ${short(via)}): no further data will be decrypted")
                Notifications.post(ctx, Notifications.ID_STATUS, Notifications.STATUS, "Access removed",
                    "The owner stopped sharing glucose with this phone")
                publishUi()
            }
        }
    }

    // ============================================================================================ data

    private fun onData(ev: JSONObject, via: String, force: Boolean = false) {
        val s = st ?: return
        val id = ev.optString("id")
        if (seen.put("d$id", Unit) != null && !force) return
        if (s.status == FollowerState.REVOKED) return
        val kind = ev.optInt("kind")
        val author = ev.optString("pubkey")
        // Not ours at all (no gk / unknown gk from a stranger): ignore silently. Unknown gk from our own identity or
        // a roster device means we missed a rotation → rekey.
        if (kind == Proto.CHUNK && s.sid != null && Proto.tagValue(ev, "c") == Proto.treatmentsC(s.sid!!)) return  // treatments: owner's job
        val gkTag = Proto.tagValue(ev, "gk") ?: return
        val epoch = s.epochs[gkTag]
        if (epoch == null) {
            if (author == s.iPub || author in s.devices) maybeRekey(gkTag)
            return
        }
        val e = runCatching { Event.fromJson(ev.toString()) }.getOrNull() ?: return reject("malformed event", author, via)
        if (!e.verify()) return reject("bad signature", author, via)
        if (kind == Proto.CHUNK && Proto.tagValue(ev, "d") == s.sid?.let { Proto.rosterD(it) }) return onRoster(ev, e, epoch, via)
        if (author !in s.devices) return reject("author not in roster", author, via)
        val body = runCatching { Proto.dec(epoch.g, author, ev.getString("content")) }.getOrNull()
            ?: return reject("decrypt failed", author, via)
        if (body.optInt("v") != 2) return reject("not v2", author, via)
        val rows = mutableListOf<Triple<Long, Int, Double?>>()
        when (kind) {
            Proto.LIVE -> rows += Triple(body.getLong("t"), body.getInt("sgv"), if (body.isNull("r")) null else body.getDouble("r"))
            Proto.CHUNK -> {
                val h = body.getLong("h")
                if (Proto.tagValue(ev, "d") != Proto.chunkD(s.sid!!, h)) return reject("chunk d tag does not match its hour", author, via)
                val a = body.getJSONArray("r")
                for (i in 0 until a.length()) {
                    val row = a.getJSONArray(i)
                    rows += Triple(h + row.getLong(0), row.getInt(1), if (row.isNull(2)) null else row.getInt(2) / 100.0)
                }
            }
            else -> return
        }
        val prevLatest = db.latest()?.t ?: 0
        val added = db.insert(rows, epoch.n, via)
        lastAcceptedAt = Proto.nowS()
        if (added == 0) return
        val newest = rows.maxBy { it.first }
        onReadings?.let { cb ->
            runCatching { cb(rows.sortedBy { it.first }.map { app.mauri.sukrara.core.Reading(it.first, it.second, it.third) },
                if (kind == Proto.LIVE) "reading" else "history") }.onFailure { log.i("onReadings: $it") }
        }
        if (kind == Proto.LIVE) {
            log.i("reading ${newest.second} mg/dL rate ${newest.third} t=${newest.first} epoch ${epoch.n} via ${short(via)} " +
                "(+${Proto.nowS() - newest.first}s)")
        } else {
            log.i("chunk h=${body.getLong("h")} +$added reading(s) epoch ${epoch.n} via ${short(via)}")
        }
        if (newest.first > prevLatest) onNewest(newest.first, newest.second)
        publishUi()
    }

    private fun onRoster(ev: JSONObject, e: Event, epoch: FollowerEpoch, via: String) {
        val s = st ?: return
        if (e.author().toHex() != s.iPub) return reject("roster not signed by identity", e.author().toHex(), via)
        val body = runCatching { Proto.dec(epoch.g, s.iPub, ev.getString("content")) }.getOrNull()
            ?: return reject("roster decrypt failed", s.iPub, via)
        val n = body.optInt("epoch")
        val created = e.createdAt().asSecs().toLong()
        if (n < s.rosterEpoch || (n == s.rosterEpoch && created <= s.rosterCreatedAt)) return
        val devices = Proto.strings(body.optJSONArray("devices"))
        val writers = Proto.strings(body.optJSONArray("writers"))
        if (writers != s.writers) log.i("roster writers: ${writers.map { it.take(12) }} — this phone ${if (s.fPub in writers) "MAY" else "may not"} add entries")
        s.writers = writers
        val changed = devices != s.devices
        s.devices = devices
        s.rosterEpoch = n
        s.rosterCreatedAt = created
        Proto.strings(body.optJSONArray("relays")).takeIf { it.isNotEmpty() && it != s.relays }?.let { s.relays = it; pool().setRelays(it) }
        save()
        log.i("roster accepted via ${short(via)}: epoch $n, devices ${devices.map { it.take(12) }}${if (changed) " (CHANGED)" else ""}")
        if (changed || subscribedDevices != devices) { subscribeData(); backfill("roster changed") }
        publishUi()
    }

    /** Unknown gk ⇒ we missed a rotation. Wait 30 s (the delivery may be in flight), then ask; ≤ 1 per gk per 5 min. */
    private fun maybeRekey(gkTag: String) {
        val s = st ?: return
        if (s.status != FollowerState.ACTIVE) return
        val now = Proto.nowS()
        if (now - (rekeyAsked[gkTag] ?: 0) < 300) return
        rekeyAsked[gkTag] = now
        scope.launch {
            delay(30_000)
            withContext(serial) {
                val cur = st ?: return@withContext
                if (cur.status != FollowerState.ACTIVE || cur.epochs.containsKey(gkTag)) return@withContext
                val body = JSONObject().put("v", 2).put("type", "rekey").put("have", cur.epochs.values.maxByOrNull { it.n }?.gk ?: "")
                publish(Proto.gift(cur.f, cur.iPub, Proto.RUMOR_TO_OWNER, body, ttlS = 7 * Proto.DAY), app.mauri.sukrara.core.Lane.CONTROL)
                log.i("unknown gk $gkTag → rekey request sent")
            }
        }
    }

    private fun reject(why: String, author: String?, via: String?) {
        db.reject(why, author, via)
        val now = System.currentTimeMillis()
        if (now - lastRejectLogAt > 2_000) {
            lastRejectLogAt = now
            log.i("REJECT: $why (author ${author?.take(12)}, via $via)")
        }
        _ui.update { it.copy(rejects = db.rejectCount(), lastReject = why) }
    }

    // ============================================================================================ alarms

    private fun onNewest(t: Long, sgv: Int) {
        val s = st ?: return
        if (noDataAlerted) { noDataAlerted = false; Notifications.cancel(ctx, Notifications.ID_NO_DATA) }
        scheduleWatchdog(((t + 6 * 60) - Proto.nowS()).coerceAtLeast(60) * 1000)
        if (!s.alarmsOn || Proto.nowS() - t > 15 * 60) return
        val kind = when { sgv <= s.low -> "LOW"; sgv >= s.high -> "HIGH"; else -> null }
        if (kind == null) {
            if (alarmKind != null) { alarmKind = null; Notifications.cancel(ctx, Notifications.ID_HIGH_LOW) }
            return
        }
        val now = Proto.nowS()
        if (kind != alarmKind || now - alarmAt > 30 * 60) {
            alarmKind = kind
            alarmAt = now
            Notifications.post(ctx, Notifications.ID_HIGH_LOW, Notifications.ALARMS, "$kind glucose: $sgv mg/dL",
                "Threshold ${if (kind == "LOW") s.low else s.high} mg/dL")
            log.i("ALARM $kind $sgv mg/dL")
        }
    }

    private fun watchdogIntent() = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, WatchdogReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * One inexact, idle-allowed alarm, always re-armed to "last reading + 6 min". While data flows it never fires
     * (each reading pushes it out), so the follower causes no periodic wake-ups of its own.
     */
    private fun scheduleWatchdog(inMs: Long) {
        ctx.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + inMs, watchdogIntent())
    }

    private fun cancelWatchdog() = ctx.getSystemService(AlarmManager::class.java).cancel(watchdogIntent())

    /** Called by [WatchdogReceiver]: stale ⇒ reconnect + backfill; ≥ noDataMin ⇒ "no data" alarm. */
    suspend fun watchdog() = withContext(serial) {
        val s = st ?: return@withContext
        if (s.status != FollowerState.ACTIVE) return@withContext
        val latest = db.latest()?.t ?: s.requestedAt
        val age = Proto.nowS() - latest
        log.i("watchdog: last reading ${age / 60} min ago")
        if (age >= 5 * 60) {
            if (!running) startLocked()
            pool?.reconnect()
            backfill("watchdog", full = false)
        }
        if (age >= s.noDataMin * 60 && s.alarmsOn && !noDataAlerted) {
            noDataAlerted = true
            Notifications.post(ctx, Notifications.ID_NO_DATA, Notifications.ALARMS, "No glucose data",
                "Nothing received for ${age / 60} min")
            log.i("ALARM no data for ${age / 60} min")
        }
        // next check in 5 min, or exactly when the no-data threshold is crossed if that comes sooner
        val untilAlarm = (latest + s.noDataMin * 60 - Proto.nowS()) * 1000
        scheduleWatchdog(if (untilAlarm in 60_000L until 5 * 60_000L) untilAlarm else 5 * 60_000L)
    }

    fun setAlarms(low: Int, high: Int, on: Boolean) = scope.launch(serial) {
        st?.let { it.low = low; it.high = high; it.alarmsOn = on; save() }
        publishUi()
    }

    fun setTestUnfiltered(on: Boolean) = scope.launch(serial) {
        val s = st ?: return@launch
        s.testUnfiltered = on
        save()
        log.i("TEST mode (no relay-side authors filter): $on")
        if (s.status == FollowerState.ACTIVE) subscribeData()
        publishUi()
    }

    fun refreshUi() = scope.launch(serial) { publishUi() }

    private fun publishUi() {
        val s = st
        val chart = db.since(Proto.nowS() - 12 * 3600)
        _ui.update {
            it.copy(
                status = s?.status ?: "none",
                code = s?.code,
                ownerPub = s?.iPub,
                fPub = s?.fPub,
                name = s?.name,
                last = chart.lastOrNull() ?: db.latest(),
                chart = chart,
                count = db.count(),
                rejects = db.rejectCount(),
                epoch = s?.maxEpoch ?: 0,
                devices = s?.devices ?: emptyList(),
                low = s?.low ?: 65,
                high = s?.high ?: 185,
                noDataMin = s?.noDataMin ?: 20,
                alarmsOn = s?.alarmsOn ?: true,
                testUnfiltered = s?.testUnfiltered ?: false,
                canWrite = s != null && s.fPub in s.writers,
                lastVia = chart.lastOrNull()?.via,
            )
        }
    }

    private fun short(via: String) = via.removePrefix("relay:")
}
