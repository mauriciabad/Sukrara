// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.follower

import app.mauri.sukrara.R
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import app.mauri.sukrara.alerts.Alerts
import app.mauri.sukrara.alerts.Kind
import app.mauri.sukrara.core.EventLog
import app.mauri.sukrara.core.Notifications
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.RelayPool
import app.mauri.sukrara.core.RelayStats
import app.mauri.sukrara.core.SecureStore
import app.mauri.sukrara.treatments.Treatment
import app.mauri.sukrara.treatments.TreatmentFeed
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
    val me: app.mauri.sukrara.core.Profile? = null,
    val wearer: app.mauri.sukrara.core.Profile? = null,
    val nearby: Boolean = false,
    val last: FollowedReading? = null,
    val chart: List<FollowedReading> = emptyList(),
    val count: Int = 0,
    val rejects: Int = 0,
    val lastReject: String? = null,
    val epoch: Int = 0,
    val devices: List<String> = emptyList(),
    val low: Int = 65,       // the alert thresholds, for the chart's range lines
    val high: Int = 185,
    val testUnfiltered: Boolean = false,
    val canWrite: Boolean = false,
    val ble: String = "off",
    val lastVia: String? = null,
    val treatments: List<FollowerEntry> = emptyList(),
)

/**
 * One row of the follower's treatment list (last 24 h). [status] only for entries this follower sent as a writer:
 * `sending` → `sent` (relay OK / BLE write response) → `accepted` (echoed by the wearer's device key with u ≥ ours),
 * or `waiting` = no echo after [FollowerEngine.ACCEPT_WAIT_S] (still listening).
 */
data class FollowerEntry(val tr: Treatment, val status: String?)

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
    private var subscribedWriters: List<String> = emptyList()
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

    /** The follower DB's readings since [fromS] (oldest first), for the widgets and the local API. */
    fun history(fromS: Long): List<FollowedReading> = db.since(fromS)

    // ============================================================================================ Bluetooth (inv. 07)

    val ble by lazy { app.mauri.sukrara.core.BleFollower(ctx, log::i, ::onEvent).also { b ->
        attach(b)
        b.onSent = { onSent(it) }
        scope.launch { b.state.collect { v -> _ui.update { it.copy(ble = v) } } }
    } }

    fun scanNearby(onFound: (app.mauri.sukrara.core.NearbyOwner) -> Unit) = ble.scan(onFound)
    fun stopScan() = ble.stopScan()

    /** Nearby pairing: connect over BLE, take the invite (I, token, relays, profile) from the owner's INFO, send the request over BLE. */
    suspend fun joinNearby(address: String, me: app.mauri.sukrara.core.Profile): String? {
        ble.stopScan()
        val got = kotlinx.coroutines.CompletableDeferred<JSONObject>()
        ble.onInfo = { if (!got.isCompleted) got.complete(it) }
        ble.connect(address)
        val info = kotlinx.coroutines.withTimeoutOrNull(20_000) { got.await() } ?: return ctx.getString(R.string.follower_error_ble_unreachable)
        ble.onInfo = null
        val pair = info.optJSONObject("pair") ?: return ctx.getString(R.string.follower_error_pairing_off)
        val inv = app.mauri.sukrara.core.Invite(info.getString("i"), pair.getString("t"), pair.getLong("x"),
            Proto.strings(info.optJSONArray("relays")), app.mauri.sukrara.core.Profile.of(pair.optJSONObject("profile")))
        log.i("nearby: INFO from $address (owner ${Proto.shortNpub(inv.iPub)}, '${inv.profile?.name}')")
        val err = join(inv, me, nearby = true)
        if (err == null) withContext(serial) { st?.bleAddr = address; save() }
        return err
    }

    /**
     * Add a treatment — only while the newest roster lists this follower as a writer (the UI hides the form otherwise);
     * goes out over BLE + relays, shown at once as *sending*, accepted when the wearer's device key echoes it.
     */
    fun addTreatment(k: String, a: Double, note: String, at: Long = Proto.nowS()) = scope.launch(serial) {
        val s = st ?: return@launch
        val sid = s.sid ?: return@launch
        if (s.fPub !in s.writers) return@launch log.i("treatment NOT sent: this phone is not in the roster writers")
        if (k !in Treatment.KINDS) return@launch
        val ep = s.epochs.values.maxByOrNull { it.n } ?: return@launch
        val now = Proto.nowS()
        val tr = Treatment(id = java.util.UUID.randomUUID().toString(), k = k, t = minOf(at, now), n = Treatment.clip(note),
            a = when (k) { "carbs" -> Math.round(a).toDouble(); "note" -> 0.0; else -> Math.round(a * 10) / 10.0 },
            u = System.currentTimeMillis(), o = "follower")
        val ev = Proto.treatmentEvent(s.f, Proto.pub(ep.g), sid, tr.wire(), now)
        db.mergeTreatment(tr, s.fPub, "local", fromOwner = false)
        db.addOutbox(tr.id, tr.u, ev.id().toHex())
        publish(ev, app.mauri.sukrara.core.Lane.CONTROL)
        log.i("treatment sent: $k ${tr.a} \"${tr.n}\" id ${tr.id.take(8)} (epoch ${ep.n}, ble ${ble.state.value})")
        publishUi()
        // re-render once the "not accepted yet" threshold passes (the echo re-renders earlier)
        scope.launch { delay((ACCEPT_WAIT_S + 1) * 1000); withContext(serial) { publishUi() } }
    }

    /** Event id confirmed by a relay (OK) or the owner's GATT write response → *sending* becomes *sent*. */
    private fun onSent(eventId: String) = scope.launch(serial) { if (db.markSent(eventId)) publishUi() }

    /** The received treatments (last 30 d, tombstones included), for the follower's Nightscout API. */
    fun treatments(): List<Treatment> = db.treatments(Proto.nowS() - 30 * Proto.DAY).map { it.tr }

    fun hasJoined() = st != null

    /** The newest roster lists this phone as a writer (it may add treatments). */
    fun canWrite() = st?.let { it.fPub in it.writers } == true

    private fun save() { st?.let { store.write(it.toJson()) } }

    private fun pool(): RelayPool {
        val s = st!!
        return pool ?: RelayPool(s.relays, log::i) { st?.f }.also { p ->
            pool = p
            p.onAccepted = { onSent(it) }
            scope.launch { p.stats.collect { _relays.value = it } }
        }
    }

    // ============================================================================================ join

    /**
     * Open a v3 short link: derive its invite key, fetch the gift wrap from the default relays (AUTH as that key
     * where a relay wants it), check the seal against the link's fingerprint. Shows who is inviting before asking.
     */
    suspend fun resolveInvite(link: String): Result<app.mauri.sukrara.core.Invite> = withContext(Dispatchers.IO) {
        val l = app.mauri.sukrara.core.Derive.parse(link) ?: return@withContext Result.failure(IllegalArgumentException(ctx.getString(R.string.follower_error_not_invite)))
        val k = Proto.inviteKeys(l)
        val tmp = RelayPool(Proto.RELAYS, log::i) { k }
        val filter = listOf(JSONObject().put("kinds", JSONArray().put(Proto.WRAP)).put("#p", JSONArray().put(Proto.pub(k))))
        // the share screen shows the link before its wrap reaches the relays: a quick scan may need another look
        var got = emptyList<Pair<JSONObject, String>>()
        try {
            for (attempt in 0 until 5) {
                got = tmp.fetch(filter, 10_000)
                if (got.isNotEmpty() || attempt == 4) break
                kotlinx.coroutines.delay(5_000)
            }
        } finally { tmp.close() }
        Proto.openInvite(l, got.map { it.first }).also { r ->
            log.i("invite: ${got.size} wrap(s) via ${got.map { it.second.removePrefix("relay:") }.distinct()} → " +
                r.fold({ "from ${Proto.shortNpub(it.iPub)} '${it.profile?.name}'" }, { "rejected: ${it.message}" }))
        }
    }

    /** Make F and send the access request (gift wrap to I) with our [me] profile. Returns an error or null. */
    suspend fun join(inv: app.mauri.sukrara.core.Invite, me: app.mauri.sukrara.core.Profile, nearby: Boolean = false): String? = withContext(serial) {
        if (inv.expiry < Proto.nowS()) return@withContext ctx.getString(R.string.follower_error_invite_expired)
        if (inv.relays.isEmpty()) return@withContext ctx.getString(R.string.follower_error_invite_no_relays)
        val f = Keys.generate()
        val code = Proto.matchCode(Proto.pub(f), inv.token)
        st = FollowerState(f, me.name.trim().ifBlank { ctx.getString(R.string.follower_default_name) }.take(app.mauri.sukrara.core.Profile.MAX_NAME), inv.iPub,
            inv.token, inv.relays, code, Proto.nowS(), avatar = me.avatar, wearer = inv.profile, nearby = nearby)
        save()
        log.i("joining ${Proto.shortNpub(inv.iPub)} ('${inv.profile?.name}') via ${inv.relays.size} relays as '${st!!.name}' F=${Proto.pub(f)} code $code")
        startLocked()
        sendRequest()
        publishUi()
        null
    }

    private fun sendRequest() {
        val s = st ?: return
        val body = JSONObject().put("v", 2).put("type", "request").put("token", s.token).put("profile", s.me.toJson())
        publish(Proto.gift(s.f, s.iPub, Proto.RUMOR_TO_OWNER, body, ttlS = 7 * Proto.DAY), app.mauri.sukrara.core.Lane.CONTROL)
        log.i("access request sent to ${Proto.shortNpub(s.iPub)}")
    }

    /** Change how the wearer sees us: a `profile` gift wrap (PROTOCOL-v2), applied by the wearer if we're approved. */
    fun setProfile(me: app.mauri.sukrara.core.Profile) = scope.launch(serial) {
        val s = st ?: return@launch
        s.name = me.name.trim().ifBlank { s.name }.take(app.mauri.sukrara.core.Profile.MAX_NAME)
        s.avatar = me.avatar
        save()
        val body = JSONObject().put("v", 2).put("type", "profile").put("profile", s.me.toJson())
        publish(Proto.gift(s.f, s.iPub, Proto.RUMOR_TO_OWNER, body, ttlS = 7 * Proto.DAY), app.mauri.sukrara.core.Lane.CONTROL)
        log.i("profile sent to the wearer: '${s.name}'")
        publishUi()
    }

    /** Forget everything (cancel a pending request / stop following). */
    suspend fun leave() = withContext(serial) {
        pool?.close(); pool = null
        ble.disconnect()
        running = false
        st = null
        store.wipe()
        db.wipe()   // clear in place: deleting the file under an open helper would keep the old rows alive
        TreatmentFeed.update(emptyList())
        cancelWatchdog()
        app.mauri.sukrara.widgets.SukraraHooks.reload(ctx, "left")   // widgets must not keep showing the old wearer's values
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
            .put("kinds", JSONArray().put(Proto.LIVE).put(Proto.ALERT))
            .put("since", Proto.nowS() - 120), st?.devices ?: emptyList())) }, ::onEvent)
        subscribeTreatments()
        log.i("subscribed: roster + chunks (#c) + live + treatments, authors ${s.devices.map { it.take(12) }}${if (s.testUnfiltered) " [TEST: no authors filter]" else ""}")
    }

    /** SPEC §5: treatments signed by the wearer's device key(s) or a roster writer, last 30 days. */
    private fun subscribeTreatments() {
        val s = st ?: return
        val sid = s.sid ?: return
        subscribedWriters = s.writers
        pool().subscribe("treatments", { listOf(authorsOrAll(JSONObject()
            .put("kinds", JSONArray().put(Proto.CHUNK))
            .put("#c", JSONArray().put(Proto.treatmentsC(sid)))
            .put("since", Proto.nowS() - 31 * Proto.DAY), ((st?.devices ?: emptyList()) + (st?.writers ?: emptyList())).distinct())) }, ::onEvent)
    }

    private fun unsubscribeData() {
        pool?.unsubscribe("treatments")
        pool?.unsubscribe("roster"); pool?.unsubscribe("chunks"); pool?.unsubscribe("live")
        subscribedDevices = emptyList()
    }

    /** One-shot backfill (after a new key): the last 13 h of chunks + recent live. */
    /**
     * One-shot chunk fetch. After a new key / roster: the full 13 h (older events were unreadable before).
     * Watchdog: only chunks (re)published since shortly before the last accepted event — chunks are republished
     * every 5 min, so that covers the gap without re-downloading 13 h of history on every stale check.
     */
    private fun backfill(why: String, full: Boolean = true, attempt: Int = 0) {
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
                    if (full) checkHistory(attempt)
                }
            } finally { if (wake.isHeld) wake.release() }
        }
    }

    private var historyAskedAt = 0L

    /**
     * After a full backfill: hours of the last 12 without a reading while the stream is live ⇒ look once more in 90 s
     * (the wearer may still be pacing its chunks out: our key delivery jumped ahead of them), then ask the wearer to
     * republish (PROTOCOL-v2 `history` request), at most once per 30 min. A real gap (sensor warm-up, phone off) costs
     * one request; the wearer only republishes what it has.
     */
    private fun checkHistory(attempt: Int) {
        val s = st ?: return
        if (s.status != FollowerState.ACTIVE) return
        val now = Proto.nowS()
        val newest = db.latest()?.t ?: return
        if (now - newest > 15 * 60) return
        val cur = Proto.hourOf(now)
        val have = db.since(cur - 12 * 3600).map { Proto.hourOf(it.t) }.toSet()
        val missing = (1..12).map { cur - it * 3600L }.filter { it !in have }
        if (missing.isEmpty()) return
        if (attempt == 0) {
            log.i("history: ${missing.size} of the last 12 h missing → backfill again in 90 s")
            scope.launch { delay(90_000); withContext(serial) { backfill("history check", attempt = 1) } }
            return
        }
        if (now - historyAskedAt < 30 * 60) return
        historyAskedAt = now
        val body = JSONObject().put("v", 2).put("type", "history").put("since", missing.min())
        publish(Proto.gift(s.f, s.iPub, Proto.RUMOR_TO_OWNER, body, ttlS = Proto.DAY), app.mauri.sukrara.core.Lane.CONTROL)
        log.i("history: ${missing.size} of the last 12 h still missing → asked the wearer to republish from h=${missing.min()}")
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
                Proto.LIVE, Proto.ALERT, Proto.CHUNK -> onData(ev, via)
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
            // single-use invites: someone else used it first, or the wearer said no. Only meaningful while we wait.
            "invite-used", "declined" -> {
                if (s.status != FollowerState.REQUESTED) return
                val used = u.body.optString("type") == "invite-used"
                s.status = if (used) FollowerState.USED else FollowerState.DECLINED
                save()
                val name = s.wearer?.name?.ifBlank { null }
                log.i(if (used) "INVITE ALREADY USED by another phone (notice via ${short(via)})" else "DECLINED by the wearer (via ${short(via)})")
                Notifications.post(ctx, Notifications.ID_STATUS, Notifications.STATUS,
                    when {
                        used -> ctx.getString(R.string.follower_notif_invite_used_title)
                        name != null -> ctx.getString(R.string.follower_notif_declined_title, name)
                        else -> ctx.getString(R.string.follower_notif_declined_title_unnamed)
                    },
                    when {
                        used && name != null -> ctx.getString(R.string.follower_notif_invite_used_text, name)
                        used -> ctx.getString(R.string.follower_notif_invite_used_text_unnamed)
                        name != null -> ctx.getString(R.string.follower_notif_declined_text, name)
                        else -> ctx.getString(R.string.follower_notif_declined_text_unnamed)
                    })
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
                Notifications.post(ctx, Notifications.ID_STATUS, Notifications.STATUS, ctx.getString(R.string.follower_notif_revoked_title),
                    ctx.getString(R.string.follower_notif_revoked_text))
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
        if (kind == Proto.CHUNK && s.sid != null && Proto.tagValue(ev, "c") == Proto.treatmentsC(s.sid!!)) return onTreatment(ev, via)
        val gkTag = Proto.tagValue(ev, "gk") ?: return
        val epoch = s.epochs[gkTag]
        if (epoch == null) {
            // Only events newer than our latest key mean a missed rotation: a new follower's backfill also returns the
            // same device's chunks from before it joined (older epoch, never ours), which are just skipped.
            if ((author == s.iPub || author in s.devices) && ev.optLong("created_at") > s.lastKeyAt) maybeRekey(gkTag)
            return
        }
        val e = runCatching { Event.fromJson(ev.toString()) }.getOrNull() ?: return reject("malformed event", author, via)
        if (!e.verify()) return reject("bad signature", author, via)
        if (kind == Proto.CHUNK && Proto.tagValue(ev, "d") == s.sid?.let { Proto.rosterD(it) }) return onRoster(ev, e, epoch, via)
        if (author !in s.devices) return reject("author not in roster", author, via)
        val body = runCatching { Proto.dec(epoch.g, author, ev.getString("content")) }.getOrNull()
            ?: return reject("decrypt failed", author, via)
        if (body.optInt("v") != 2) return reject("not v2", author, via)
        if (kind == Proto.ALERT) {
            if (body.optString("type") != "alert") return reject("alert: wrong type", author, via)
            log.i("alert ${body.optString("k")} ${body.optString("state")} via ${short(via)}")
            return Alerts.onRemote(ctx, body)
        }
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
        if (newest.first > prevLatest) onNewest(newest.first, newest.second, newest.third)
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
        app.mauri.sukrara.core.Profile.of(body.optJSONObject("profile"))?.takeIf { it != s.wearer }?.let {
            log.i("wearer profile: '${it.name}'")
            s.wearer = it
        }
        save()
        log.i("roster accepted via ${short(via)}: epoch $n, devices ${devices.map { it.take(12) }}${if (changed) " (CHANGED)" else ""}")
        if (changed || subscribedDevices != devices) { subscribeData(); backfill("roster changed") }
        else if (subscribedWriters != writers && s.status == FollowerState.ACTIVE) subscribeTreatments()
        publishUi()
    }

    /**
     * A treatment event (SPEC §5, PROTOCOL-v2 "Treatments"): signed by a roster device (the wearer's merged log) or a
     * roster writer, known epoch, decrypts, `d` matches the id → §1 merge into follower.db → charts + Nightscout API.
     */
    private fun onTreatment(ev: JSONObject, via: String) {
        val s = st ?: return
        val sid = s.sid ?: return
        val author = ev.optString("pubkey")
        val fromOwner = author in s.devices
        if (!fromOwner && author !in s.writers) return reject("treatment author not in roster", author, via)
        val gkTag = Proto.tagValue(ev, "gk") ?: return
        val epoch = s.epochs[gkTag] ?: return if (fromOwner) maybeRekey(gkTag) else Unit
        val e = runCatching { Event.fromJson(ev.toString()) }.getOrNull() ?: return reject("malformed event", author, via)
        if (!e.verify()) return reject("treatment: bad signature", author, via)
        val body = runCatching { Proto.dec(epoch.g, author, ev.getString("content")) }.getOrNull()
            ?: return reject("treatment: decrypt failed", author, via)
        val tr = Treatment.fromWire(body) ?: return reject("treatment: malformed body", author, via)
        if (Proto.tagValue(ev, "d") != Proto.treatmentD(sid, tr.id)) return reject("treatment: d tag mismatch", author, via)
        if (!db.mergeTreatment(tr, author, via, fromOwner)) return
        if (tr.k == "rapid" || tr.k == "carbs") Alerts.check(ctx)   // the insulin and carbs mutes (own rules)
        log.i("treatment ${if (tr.del) "DELETED" else tr.k + " " + tr.a} \"${tr.n}\" id ${tr.id.take(8)} from " +
            "${if (fromOwner) "wearer" else "writer " + author.take(8)} via ${short(via)}")
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

    // ============================================================================================ alerts + liveness

    /** Newest reading: alerts (investigation 15, [Alerts]) and the liveness watchdog. */
    private fun onNewest(t: Long, sgv: Int, rate: Double?) {
        scheduleWatchdog(((t + 6 * 60) - Proto.nowS()).coerceAtLeast(60) * 1000)
        Alerts.onReading(ctx, app.mauri.sukrara.core.Reading(t, sgv, rate))
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

    /** Called by [WatchdogReceiver]: stale ⇒ reconnect + backfill. (The "no data" alert is [Alerts]' own timer.) */
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
        scheduleWatchdog(5 * 60_000L)
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
        val now = Proto.nowS()
        val all = db.treatments(now - 30 * Proto.DAY)
        if (s != null) TreatmentFeed.update(all.map { it.tr })
        val mine = db.outbox().associateBy { it.id }
        val entries = all.filter { it.tr.t >= now - 24 * 3600 || mine[it.tr.id]?.let { o -> now - o.createdAt < 3600 } == true }
            .map { f ->
                FollowerEntry(f.tr, mine[f.tr.id]?.let { o ->
                    when {
                        f.ownerU >= o.u -> "accepted"
                        now - o.createdAt >= ACCEPT_WAIT_S -> "waiting"
                        o.sentAt == 0L -> "sending"
                        else -> "sent"
                    }
                })
            }
        _ui.update {
            it.copy(
                status = s?.status ?: "none",
                code = s?.code,
                ownerPub = s?.iPub,
                fPub = s?.fPub,
                name = s?.name,
                me = s?.me,
                wearer = s?.wearer,
                nearby = s?.nearby ?: false,
                last = chart.lastOrNull() ?: db.latest(),
                chart = chart,
                count = db.count(),
                rejects = db.rejectCount(),
                epoch = s?.maxEpoch ?: 0,
                devices = s?.devices ?: emptyList(),
                low = Alerts.settings.value.cfg(Kind.LOW).value.toInt(),
                high = Alerts.settings.value.cfg(Kind.HIGH).value.toInt(),
                testUnfiltered = s?.testUnfiltered ?: false,
                canWrite = s != null && s.fPub in s.writers,
                lastVia = chart.lastOrNull()?.via,
                treatments = entries,
            )
        }
    }

    private fun short(via: String) = via.removePrefix("relay:")

    companion object {
        /** No echo from the wearer this long after sending ⇒ shown as "not accepted yet" (still listening). */
        const val ACCEPT_WAIT_S = 120L
    }
}
