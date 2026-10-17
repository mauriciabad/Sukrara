// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.owner

import android.content.Context
import android.os.PowerManager
import app.mauri.sukrara.BuildConfig
import app.mauri.sukrara.core.EventLog
import app.mauri.sukrara.core.Lane
import app.mauri.sukrara.core.Notifications
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.RelayPool
import app.mauri.sukrara.core.RelayStats
import app.mauri.sukrara.core.SecureStore
import app.mauri.sukrara.features.FeatureState
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.treatments.Treat
import app.mauri.sukrara.treatments.Treatment
import app.mauri.sukrara.wearer.Live
import java.io.File
import java.util.TreeMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.nostrdevkit.sdk.EncryptedSecretKey
import org.nostrdevkit.sdk.Event
import org.nostrdevkit.sdk.KeySecurity
import org.nostrdevkit.sdk.Keys

data class OwnerUi(
    val hasIdentity: Boolean = false,
    val profile: app.mauri.sukrara.core.Profile? = null,
    val iPub: String? = null,
    val dPub: String? = null,
    val epoch: Int = 0,
    val last: Reading? = null,
    val chart: List<Reading> = emptyList(),
    val source: String = "not started",
    val sourceError: String? = null,
    val synthetic: Boolean = false,
    val followers: List<Follower> = emptyList(),
    val pending: List<PendingRequest> = emptyList(),
    val invite: String? = null,
    val inviteExpiry: Long = 0,
    val backupSavedAt: Long = 0,
    val backupPublishedAt: Long = 0,
    val liveSent: Int = 0,
    val chunksSent: Int = 0,
    val lastInboxCheck: Long = 0,
    val inboxLive: Boolean = false,
    val busy: String? = null,
    // investigation 07: Bluetooth
    val bleOn: Boolean = false,
    val bleCentrals: Int = 0,
    val nearbyUntil: Long = 0,
    val writers: Set<String> = emptySet(),
    val treatments: List<String> = emptyList(),
)

/**
 * The owner role ("I wear the sensor"): glucose source → live + hourly chunks under the current epoch, roster and
 * owner backup, access requests (gift wraps to I), approvals, revocation by rotation, phone-change restore.
 *
 * All state changes run on one serial dispatcher. The relay pool lives as long as the engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnerEngine(private val ctx: Context) {
    val log = EventLog("owner")
    private val store = SecureStore(File(ctx.filesDir, "owner-state.bin"), "cgmshare.owner")
    private val db = OwnerDb(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serial = Dispatchers.IO.limitedParallelism(1)
    private val http = OkHttpClient.Builder().build()
    private val wake = ctx.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sukrara:owner").apply { setReferenceCounted(false) }
    private val inboxWake = ctx.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sukrara:inbox").apply { setReferenceCounted(false) }

    private var st: OwnerState? = runCatching { store.read()?.let(OwnerState::fromJson) }
        .onFailure { log.i("could not read owner state: $it") }.getOrNull()
    private var pool: RelayPool? = null
    private var source: GlucoseSource? = null
    private var tick: Job? = null
    private var running = false
    private var foreground = false

    // publisher state (from the PoC 3 NostrPublisher)
    private val readings = TreeMap<Long, Reading>()          // t → reading, last ~25 h
    private val dirty = sortedSetOf<Long>()                  // hours with unpublished changes
    private val lastChunkSentAt = HashMap<Long, Long>()
    private val lastChunkCreatedAt = HashMap<Long, Long>()
    private var newestLiveT = 0L
    private var liveSent = 0
    private var chunksSent = 0
    private var lastInboxCheck = 0L
    private var invite: Pair<String, Long>? = null
    private var nearby: InviteToken? = null           // investigation 07: short-lived token handed out over BLE INFO
    private var ble: app.mauri.sukrara.core.BleOwner? = null

    private val _ui = MutableStateFlow(OwnerUi())
    val ui: StateFlow<OwnerUi> = _ui
    private val _relays = MutableStateFlow<Map<String, RelayStats>>(emptyMap())
    val relayStats: StateFlow<Map<String, RelayStats>> = _relays

    init { publishUi() }

    fun hasIdentity() = st != null

    /** Stored readings from [fromS] on, oldest first (the widgets' and the notification graph's source). */
    fun history(fromS: Long): List<Reading> = db.range(fromS, Long.MAX_VALUE)

    private fun pool(): RelayPool = pool ?: RelayPool(st?.relays ?: Proto.RELAYS, log::i) { st?.i }.also { p ->
        pool = p
        scope.launch { p.stats.collect { _relays.value = it } }
    }

    private fun save() { st?.let { store.write(it.toJson()) } }

    // ============================================================================================ lifecycle

    /** Called by [OwnerService]. Idempotent. */
    fun start() = scope.launch(serial) {
        val s = st ?: return@launch
        if (running) return@launch
        running = true
        db.range(Proto.nowS() - 25 * 3600, Long.MAX_VALUE).forEach { readings[it.t] = it }
        newestLiveT = readings.lastEntry()?.key ?: 0
        // which chunks reached the relays isn't persisted: republish the last 24 h once per start (paced, ~50 KB)
        markRecentDirty()
        log.i("owner ${Proto.shortNpub(s.iPub)} device ${s.dPub.take(12)} epoch ${s.current.n} gk ${s.current.gk}, " +
            "${s.active().size} follower(s), relays ${s.relays.size}")
        if (Features.value.nostr) pool()
        if (Proto.nowS() - s.rosterPublishedAt > 6 * Proto.DAY) publishRoster()
        if (Proto.nowS() - s.backupPublishedAt > 6 * Proto.DAY) publishBackup()
        startSource()
        Treat.init(ctx)
        Treat.store.onChange.addIfAbsent(onTreatmentChange)
        if (Features.value.bleFollowers) startBle()
        subscribeTreatments()
        if (Proto.nowS() - s.treatmentsPublishedAt > 7 * Proto.DAY) republishTreatments("weekly")
        tick = scope.launch {
            delay(5_000)
            checkInbox("startup")
            while (isActive) {
                delay(chunkEvery() * 1000)
                withContext(serial) { onTick() }
            }
        }
        features = scope.launch {
            var old = Features.value
            Features.state.collect { f -> if (f != old) { val o = old; old = f; withContext(serial) { applyFeatures(o, f) } } }
        }
        publishUi()
    }

    private var features: Job? = null

    /** Seconds between chunk publishes: 5 min, or the batch interval when live events are off. */
    private fun chunkEvery(): Long = Features.value.nostrIntervalMin.let { if (it > 0) it * 60L else CHUNK_EVERY_S }

    /** A feature switch changed (settings screen or DebugReceiver): apply without restarting anything else. */
    private fun applyFeatures(old: FeatureState, f: FeatureState) {
        val s = st ?: return
        if (old.source != f.source) { log.i("glucose source → ${f.source}"); startSource() }
        if (old.bleFollowers != f.bleFollowers) {
            if (f.bleFollowers) startBle() else stopBle()
        }
        if (old.nostr != f.nostr) {
            if (f.nostr) {
                log.i("Nostr sharing ON (interval ${f.nostrIntervalMin} min)")
                pool()
                publishRoster()
                if (Proto.nowS() - s.backupPublishedAt > Proto.DAY) publishBackup()
                subscribeTreatments()
                if (Proto.nowS() - s.treatmentsPublishedAt > 7 * Proto.DAY) republishTreatments("weekly")
                if (foreground) pool().subscribe("inbox", { listOf(inboxFilter(s)) }, ::onEvent)
                markRecentDirty()
                flush(force = true)
                scope.launch { checkInbox("startup") }
            } else {
                log.i("Nostr sharing OFF: relays closed")
                pool?.close(); pool = null
                _relays.value = emptyMap()
            }
        }
        if (old.nostrIntervalMin != f.nostrIntervalMin) {
            log.i("Nostr interval → ${if (f.nostrIntervalMin == 0) "every reading" else "${f.nostrIntervalMin} min batches"}")
            tick?.cancel()
            tick = scope.launch {
                while (isActive) {
                    delay(chunkEvery() * 1000)
                    withContext(serial) { onTick() }
                }
            }
        }
        publishUi()
    }

    private fun stopBle() {
        val b = ble ?: return
        extra.remove(b)
        b.close()
        ble = null
        log.i("BLE followers transport OFF")
        _ui.update { it.copy(bleOn = false, bleCentrals = 0) }
    }

    fun stop() = scope.launch(serial) {
        running = false
        tick?.cancel()
        features?.cancel()
        source?.stop()
        source = null
        Treat.store.onChange.remove(onTreatmentChange)
        stopBle()
    }

    /** While the UI is visible: a persistent subscription → access requests show up immediately. */
    fun setForeground(on: Boolean) = scope.launch(serial) {
        foreground = on
        val s = st ?: return@launch
        if (on && Features.value.nostr) {
            pool().subscribe("inbox", { listOf(inboxFilter(s)) }, ::onEvent)
        } else if (pool?.hasSubscription("inbox") == true) {
            pool?.unsubscribe("inbox")
        }
        publishUi()
    }

    private fun inboxFilter(s: OwnerState) = JSONObject()
        .put("kinds", JSONArray().put(Proto.WRAP))
        .put("#p", JSONArray().put(s.iPub))
        .put("since", Proto.nowS() - 3 * Proto.DAY)   // NIP-59 wraps are back-dated up to 2 days

    /** The sensor's pairing changed (NFC scan, forget): reconnect with the new one. */
    fun restartSource() = scope.launch { startSource() }

    private fun startSource() {
        source?.stop()
        val s = st ?: return
        val src = when {
            BuildConfig.DEBUG && s.synthetic -> SyntheticSource()
            Features.value.source == "direct" -> DirectAdapter(ctx)
            else -> JugglucoSource(JUGGLUCO, http)
        }
        source = src
        src.start(scope, object : GlucoseSource.Sink {
            override fun onReading(r: Reading) {
                Live.reading(r)
                wake.acquire(10_000)
                scope.launch(serial) {
                    runCatching { handleReading(r) }.onFailure { log.i("publish failed: $it") }
                    delay(3_000)   // live leaves at once; a due chunk 2 s later
                    if (wake.isHeld) wake.release()
                }
            }

            override fun onHistory(readings: List<Reading>) {
                if (readings.isEmpty()) return
                Live.history(readings)
                wake.acquire(90_000)
                scope.launch(serial) { runCatching { handleHistory(readings) }.onFailure { log.i("backfill failed: $it") } }
            }

            override fun onStatus(state: String, error: String?) {
                _ui.update { it.copy(source = state, sourceError = error) }
                Live.sourceStatus(state, error)
                if (error != null) log.i("source: $state — $error")
            }
        })
    }

    // ============================================================================================ Bluetooth (inv. 07)

    private fun startBle() {
        val s = st ?: return
        if (ble != null) return
        val granted = listOf(android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_ADVERTISE)
            .all { ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (!granted) return log.i("BLE: permissions not granted, Bluetooth transport off")
        val b = app.mauri.sukrara.core.BleOwner(ctx, log::i, ::onEvent) {
            val n = nearby?.takeIf { it.x > Proto.nowS() }
            app.mauri.sukrara.core.Ble.info(s.iPub, s.relays, n?.t, n?.x ?: 0, s.profile)
        }
        if (!b.start()) return
        ble = b
        attach(b)
        publishRoster()   // seeds the BLE backlog cache (the roster is otherwise only re-published weekly)
        recentTreatments().forEach { publishTreatment(it, relays = false) }   // …and its treatment backlog
        scope.launch { b.connected.collect { n -> _ui.update { it.copy(bleOn = true, bleCentrals = n) } } }
    }

    /** Retry BLE after the user granted the permissions. */
    fun retryBle() = scope.launch(serial) { if (running) startBle(); publishUi() }

    /** "Pair nearby": a 10-minute invite token offered to anyone connected over BLE (owner still Allows each request). */
    fun nearbyPairing() = scope.launch(serial) {
        val s = st ?: return@launch
        val tok = InviteToken(Proto.newToken(), Proto.nowS() + 600)
        nearby = tok
        s.invites.removeIf { it.x < Proto.nowS() }
        s.invites += tok
        save()
        log.i("nearby pairing on for 10 min (token offered over BLE INFO)")
        publishUi()
    }

    /** The wearer's own name + picture: followers get it with the next roster (now), new invites carry it. */
    fun setProfile(p: app.mauri.sukrara.core.Profile) = scope.launch(serial) {
        val s = st ?: return@launch
        s.profile = p.copy(name = p.name.trim().take(app.mauri.sukrara.core.Profile.MAX_NAME))
        save()
        publishRoster()
        publishBackup()
        log.i("profile: '${s.profile.name}', avatar ${s.profile.avatar}")
        publishUi()
    }

    fun setWriter(f: String, on: Boolean) = scope.launch(serial) {
        val s = st ?: return@launch
        if (on) s.writers += f else s.writers -= f
        save()
        log.i("writer ${if (on) "ADDED" else "removed"}: ${s.followers.lastOrNull { it.f == f }?.name} (${f.take(12)})")
        publishRoster()
        publishBackup()
        subscribeTreatments()
        publishUi()
    }

    private fun subscribeTreatments() {
        val s = st ?: return
        if (s.writers.isEmpty() || !Features.value.nostr) { pool?.unsubscribe("treatments"); return }
        pool().subscribe("treatments", { listOf(JSONObject()
            .put("kinds", JSONArray().put(Proto.CHUNK))
            .put("authors", JSONArray(st?.writers?.toList() ?: emptyList<String>()))
            .put("#c", JSONArray().put(Proto.treatmentsC(s.sid)))
            .put("since", Proto.nowS() - 2 * Proto.DAY)) }, ::onEvent)
    }

    /** Treatment from a follower (any transport): accepted only if signed by a roster writer and decryptable with a known epoch. */
    private fun onTreatment(ev: JSONObject, via: String) {
        val s = st ?: return
        val author = ev.optString("pubkey")
        val name = s.followers.lastOrNull { it.f == author }?.name ?: author.take(12)
        val e = runCatching { Event.fromJson(ev.toString()) }.getOrNull() ?: return log.i("treatment: malformed, ignored")
        if (!e.verify()) return log.i("treatment REJECTED: bad signature (via $via)")
        if (author !in s.writers || s.followers.none { it.f == author && it.revoked == null }) {
            return log.i("treatment REJECTED: author $name (${author.take(12)}) is not in roster writers (via $via)")
        }
        val epoch = s.epochs.firstOrNull { it.gk == Proto.tagValue(ev, "gk") }
            ?: return log.i("treatment REJECTED: unknown gk from $name")
        val tr = runCatching { Proto.dec(epoch.g, author, ev.getString("content")) }.getOrNull()
            ?: return log.i("treatment REJECTED: decrypt failed from $name")
        val id = tr.optString("id")
        if (id.isEmpty() || Proto.tagValue(ev, "d") != Proto.treatmentD(s.sid, id)) return log.i("treatment REJECTED: d tag mismatch from $name")
        if (!db.upsertTreatment(tr, author, via)) return
        Live.followerTreatment(tr)
        log.i("TREATMENT accepted from $name via ${via.removePrefix("relay:")}: ${tr.optString("k")} ${tr.optDouble("a")} " +
            "\"${tr.optString("n")}\" t=${tr.optLong("t")} (+${Proto.nowS() - tr.optLong("t")}s)")
        if (via.startsWith("ble:")) ble?.forward(ev, via.removePrefix("ble:"))
        publishUi()
    }

    // ============================================================================================ treatments → followers

    /** Every change of the merged log (phone, accepted writer entries, deletes) → followers. */
    private val onTreatmentChange: (Treatment, Long) -> Unit = { tr, _ ->
        // a LibreView upload only moves `lv`, which isn't on the wire: nothing new for followers
        scope.launch(serial) {
            if (trPublished[tr.id] != tr.u to tr.del) publishTreatment(tr)
            if (tr.del) publishUi()                     // "Entries from followers" drops deleted entries
        }
    }
    private val trCreatedAt = HashMap<String, Long>()
    private val trPublished = HashMap<String, Pair<Long, Boolean>>()

    private fun recentTreatments(): List<Treatment> {
        Treat.init(ctx)   // restore() rotates before start()
        val cut = Proto.nowS() - 30 * Proto.DAY
        return Treat.store.all().filter { it.t >= cut || it.u / 1000 >= cut }.sortedBy { it.t }
    }

    /**
     * SPEC §5 / PROTOCOL-v2 "Treatments": one event per entry, signed by the device key D (the key in roster
     * `devices`), encrypted to the current epoch. A single change goes out at once (CONTROL lane); a republish
     * (rotation, new follower, weekly) goes in the paced CHUNK lane after the chunks. `created_at` strictly increases
     * per entry, or relays would keep the older version.
     */
    private fun publishTreatment(tr: Treatment, relays: Boolean = true, lane: Lane = Lane.CONTROL) {
        val s = st ?: return
        if (!relays && ble == null) return
        val at = maxOf(Proto.nowS(), (trCreatedAt[tr.id] ?: 0) + 1)
        trCreatedAt[tr.id] = at
        if (relays) trPublished[tr.id] = tr.u to tr.del
        publish(Proto.treatmentEvent(s.d, s.current.pub, s.sid, tr.wire(), at), lane, "tr:${tr.id}", relays)
    }

    private fun republishTreatments(why: String) {
        val s = st ?: return
        val list = recentTreatments()
        list.forEach { publishTreatment(it, lane = Lane.CHUNK) }
        s.treatmentsPublishedAt = Proto.nowS()
        save()
        log.i("treatments republished ($why): ${list.size} entr${if (list.size == 1) "y" else "ies"} of the last 30 d, " +
            "epoch ${s.current.n}")
    }

    fun setSynthetic(on: Boolean) = scope.launch(serial) {
        val s = st ?: return@launch
        s.synthetic = on
        save()
        log.i("glucose source → ${if (on) "synthetic (debug)" else "Juggluco"}")
        if (running) startSource()
        publishUi()
    }

    // ============================================================================================ identity

    suspend fun createIdentity(profile: app.mauri.sukrara.core.Profile? = null) = withContext(serial) {
        st = OwnerState.create(synthetic = BuildConfig.DEBUG && isEmulator()).also { s -> profile?.let { s.profile = it } }
        save()
        log.i("new identity ${Proto.shortNpub(st!!.iPub)}, sid tag ${Proto.chunkC(st!!.sid).take(8)}…, epoch 1")
        publishUi()
    }

    /**
     * Phone change / reinstall: identity from the password manager (or pasted nsec / ncryptsec) → owner backup from
     * the relays → new device key D', rotate to a new epoch, deliveries to every follower, roster with [D'].
     * Returns an error message or null.
     */
    suspend fun restore(secret: String, password: String?): String? = withContext(serial) {
        val keys = runCatching {
            val x = secret.trim()
            if (x.startsWith("ncryptsec1")) Keys(EncryptedSecretKey.fromBech32(x).decrypt(password ?: ""))
            else Keys.parse(x)
        }.getOrElse { return@withContext "Not a valid nsec / ncryptsec (or wrong password)" }
        val iPub = Proto.pub(keys)
        _ui.update { it.copy(busy = "Looking for your backup on the relays…") }
        val tmp = RelayPool(Proto.RELAYS, log::i) { keys }
        val found = try {
            tmp.fetch(listOf(JSONObject()
                .put("authors", JSONArray().put(iPub))
                .put("kinds", JSONArray().put(Proto.CHUNK))
                .put("#d", JSONArray().put(Proto.backupD(iPub)))), 12_000)
        } finally { tmp.close() }
        val newest = found.mapNotNull { (j, _) -> runCatching { Event.fromJson(j.toString()) }.getOrNull() }
            .filter { it.verify() && it.author().toHex() == iPub }
            .maxByOrNull { it.createdAt().asSecs() }
        if (newest == null) {
            _ui.update { it.copy(busy = null) }
            return@withContext "No owner backup for ${Proto.shortNpub(iPub)} found on ${Proto.RELAYS.size} relays"
        }
        val body = runCatching { Proto.dec(keys, iPub, newest.content()) }.getOrElse {
            _ui.update { it.copy(busy = null) }
            return@withContext "Backup found but could not be decrypted"
        }
        val s = OwnerState.fromBackup(keys, body, synthetic = BuildConfig.DEBUG && isEmulator())
        st = s
        save()
        pool?.close(); pool = null
        log.i("restored ${Proto.shortNpub(iPub)} from backup (${found.size} copies, newest ${newest.createdAt().asSecs()}): " +
            "epoch ${s.current.n}, ${s.active().size} active follower(s); new device ${s.dPub.take(12)}")
        rotate("restored on a new device")
        _ui.update { it.copy(busy = null) }
        publishUi()
        null
    }

    fun credentialId(): String = "Sukrara · " + (st?.let { Proto.shortNpub(it.iPub) } ?: "?")

    /** Only for handing to Credential Manager / an explicit export. Never logged. */
    fun identityNsec(): String? = st?.i?.secretKey()?.toBech32()

    /** NIP-49 recovery string (log_n 18 → 256 MiB scrypt, ~1-3 s: call off the main thread). */
    suspend fun identityNcryptsec(password: String): String? = withContext(Dispatchers.Default) {
        st?.i?.secretKey()?.let { EncryptedSecretKey(it, password, 18u, KeySecurity.MEDIUM).toBech32() }
    }

    fun markBackupSaved() = scope.launch(serial) {
        st?.backupSavedAt = Proto.nowS()
        save()
        log.i("identity saved to a password manager (Credential Manager)")
        publishUi()
    }

    // ============================================================================================ data publishing

    private fun handleHistory(list: List<Reading>) {
        val now = Proto.nowS()
        val minT = Proto.hourOf(now) - (BACKFILL_HOURS - 1) * 3600
        val recent = list.filter { it.t >= minT && it.t <= now + 60 }
        val changedList = db.upsertAll(recent)
        val changed = changedList.size
        changedList.forEach { r -> readings[r.t] = r; dirty += Proto.hourOf(r.t) }
        recent.forEach { r -> if (readings[r.t] == null) { readings[r.t] = r; dirty += Proto.hourOf(r.t) } }
        newestLiveT = maxOf(newestLiveT, readings.lastEntry()?.key ?: 0L)
        prune(now)
        log.i("history: ${list.size} entries, $changed new, ${dirty.size} chunk(s) to publish")
        flush(force = true)
    }

    private fun handleReading(r: Reading) {
        val now = Proto.nowS()
        val s = st ?: return
        if (db.upsert(r)) { readings[r.t] = r; dirty += Proto.hourOf(r.t) }
        if (r.t > newestLiveT && now - r.t < LIVE_MAX_AGE_S) {
            newestLiveT = r.t
            publish(Proto.liveEvent(s.d, s.current.pub, r, now), Lane.LIVE, relays = Features.value.nostrIntervalMin == 0)
            liveSent++
        }
        prune(now)
        flush(force = false)
    }

    /** Investigation 15: a fired or cleared alert to every follower, at once (relays + BLE), even with batching on. */
    fun publishAlert(body: JSONObject) = scope.launch(serial) {
        val s = st ?: return@launch
        publish(Proto.alertEvent(s.d, s.current.pub, body), Lane.LIVE, key = "alert")
        log.i("alert ${body.optString("k")} ${body.optString("state")} published")
    }

    private fun prune(now: Long) {
        val cutoff = Proto.hourOf(now) - BACKFILL_HOURS * 3600
        readings.headMap(cutoff).clear()
        dirty.removeIf { it < cutoff }
        lastChunkSentAt.keys.removeIf { it < cutoff - 3600 }
        lastChunkCreatedAt.keys.removeIf { it < cutoff - 3600 }
    }

    /** Publish dirty chunks; newest hour first. A published current-hour chunk also triggers the inbox check. */
    private fun flush(force: Boolean) {
        val s = st ?: return
        val now = Proto.nowS()
        val current = Proto.hourOf(now)
        var published = 0
        for (h in dirty.descendingSet().toList()) {
            val due = force || h < current || now - (lastChunkSentAt[h] ?: 0) >= chunkEvery()
            if (!due) continue
            val rows = readings.subMap(h, h + 3600).values
            if (rows.isEmpty()) { dirty -= h; continue }
            val createdAt = maxOf(now, (lastChunkCreatedAt[h] ?: 0) + 1)
            publish(Proto.chunkEvent(s.d, s.current.pub, s.sid, h, rows, createdAt), Lane.CHUNK, Proto.chunkD(s.sid, h))
            lastChunkSentAt[h] = now
            lastChunkCreatedAt[h] = createdAt
            dirty -= h
            chunksSent++
            published++
        }
        if (published > 0 && !force) scope.launch { checkInbox("chunk publish") }
        publishUi()
    }

    private fun onTick() {
        val s = st ?: return
        flush(force = false)
        if (Proto.nowS() - s.rosterPublishedAt > 7 * Proto.DAY) publishRoster()
        if (Proto.nowS() - s.backupPublishedAt > 7 * Proto.DAY) publishBackup()
        if (Proto.nowS() - s.treatmentsPublishedAt > 7 * Proto.DAY) republishTreatments("weekly")
        if (Proto.nowS() - lastInboxCheck > chunkEvery() - 30) scope.launch { checkInbox("tick") }
        db.prune(Proto.nowS() - 31 * Proto.DAY)   // 30-day slots and report (investigations 16, 18)
    }

    private fun markRecentDirty() {
        val cut = Proto.nowS() - 24 * 3600
        readings.keys.filter { it >= cut }.forEach { dirty += Proto.hourOf(it) }
    }

    private var historyRepublishedAt = 0L

    /**
     * Republish the hourly chunks since [fromS] (at most the last 24 h) under the current epoch: on a new approval, on
     * a rotation and on a follower's `history` request (PROTOCOL-v2). Paced by the relay writer (CHUNK lane, ≥ 2 s
     * apart per relay, newest hour first: ~50 s and ~35 KB per relay for 24 h), so it never looks like a burst. A new
     * follower's first backfill can race chunks that are still queued (its key delivery jumps ahead in the CONTROL
     * lane), or meet a relay that lost some; afterwards its open `#c` subscription receives a complete fresh copy.
     */
    private fun republishHistory(why: String, fromS: Long = 0) {
        val now = Proto.nowS()
        val from = Proto.hourOf(maxOf(fromS, now - 24 * 3600))
        val hours = readings.keys.filter { it >= from }.map { Proto.hourOf(it) }.toSortedSet()
        historyRepublishedAt = now
        dirty += hours
        flush(force = true)
        log.i("history republished ($why): ${hours.size} hourly chunk(s) from h=$from, paced")
    }

    // ============================================================================================ control events

    private fun publishRoster() {
        val s = st ?: return
        val e = s.current
        // created_at must strictly increase per (author, kind, d), or relays keep the older version
        val at = maxOf(Proto.nowS(), s.rosterPublishedAt + 1)
        val writers = s.writers.filter { w -> s.followers.any { it.f == w && it.revoked == null } }
        publish(Proto.rosterEvent(s.i, e.pub, s.sid, e.n, listOf(s.dPub), s.relays, at, writers, s.profile), Lane.CONTROL, "roster")
        s.rosterPublishedAt = at
        save()
        log.i("roster published: epoch ${e.n} gk ${e.gk}, devices [${s.dPub.take(12)}], writers ${writers.map { it.take(12) }}")
    }

    private fun publishBackup() {
        val s = st ?: return
        val at = maxOf(Proto.nowS(), s.backupPublishedAt + 1)
        publish(Proto.backupEvent(s.i, s.backupBody(), at), Lane.CONTROL, "backup")
        s.backupPublishedAt = at
        save()
        log.i("owner backup published: ${s.epochs.size} epoch(s), ${s.followers.size} follower record(s), ${s.invites.size} invite(s)")
    }

    private fun deliverKey(f: String) {
        val s = st ?: return
        val e = s.current
        val body = JSONObject().put("v", 2).put("type", "key").put("sid", s.sid).put("epoch", e.n)
            .put("g", e.g.secretKey().toBech32()).put("devices", JSONArray().put(s.dPub)).put("relays", JSONArray(s.relays))
        publish(Proto.gift(s.i, f, Proto.RUMOR_TO_FOLLOWER, body), Lane.CONTROL)
        log.i("key delivery → ${f.take(12)} (epoch ${e.n}, gk ${e.gk})")
    }

    private fun sendRevoked(f: String) {
        val s = st ?: return
        publish(Proto.gift(s.i, f, Proto.RUMOR_TO_FOLLOWER, JSONObject().put("v", 2).put("type", "revoked")), Lane.CONTROL)
        log.i("revoked notice → ${f.take(12)}")
    }

    /** New epoch: deliveries first (so followers have the key before they see the roster), then roster, backup, chunks. */
    private fun rotate(why: String) {
        val s = st ?: return
        s.epochs += Epoch(s.current.n + 1, Keys.generate())
        save()
        s.active().forEach { deliverKey(it.f) }
        publishRoster()
        publishBackup()
        republishHistory("epoch ${s.current.n}")
        republishTreatments("epoch ${s.current.n}")
        log.i("rotated to epoch ${s.current.n} gk ${s.current.gk} ($why); ${s.active().size} follower(s) re-keyed, " +
            "republishing ${lastChunkSentAt.size} recent chunk(s)")
    }

    // ============================================================================================ sharing

    /**
     * A v3 short invite: the link (fingerprint + secret) and a gift wrap on the relays with the rest (expiry, relays,
     * our profile), sealed by I. The secret's base64url is the access-request token.
     */
    fun newInvite(): String? {
        val s = st ?: return null
        val x = Proto.nowS() + INVITE_TTL_S
        val (link, wrap) = Proto.newInvite(s.i, x, s.relays, s.profile)
        val token = app.mauri.sukrara.core.Derive.parse(link)!!.token
        invite = link to x
        scope.launch(serial) {
            s.invites.removeIf { it.x < Proto.nowS() }
            s.invites += InviteToken(token, x)
            save()
            publish(wrap, Lane.CONTROL, "invite")
            publishBackup()
            log.i("invite created (expires in ${INVITE_TTL_S / 3600} h, ${link.length} chars), wrap ${wrap.id().toHex().take(8)}" +
                if (Features.value.nostr) "" else " NOT published: Nostr is off")
            publishUi()
        }
        _ui.update { it.copy(invite = link, inviteExpiry = x) }
        return link
    }

    suspend fun checkInbox(why: String) {
        val s = st ?: return
        if (foreground && pool?.hasSubscription("inbox") == true && why != "manual") return   // live subscription covers it
        if (why != "manual" && why != "startup" && Proto.nowS() - lastInboxCheck < 240) return
        if (why != "manual" && !Features.value.nostr) return
        inboxWake.acquire(20_000)
        try {
            lastInboxCheck = Proto.nowS()
            val got = pool().fetch(listOf(inboxFilter(s)), 10_000)
            withContext(serial) { got.forEach { (ev, via) -> onWrap(ev, via) } }
            _ui.update { it.copy(lastInboxCheck = lastInboxCheck) }
        } finally {
            if (inboxWake.isHeld) inboxWake.release()
        }
    }

    // ============================================================================================ transport seam

    /** Extra transports (e.g. BLE for nearby pairing, next spike). Relays are always there; see [Transport]. */
    private val extra = java.util.concurrent.CopyOnWriteArrayList<app.mauri.sukrara.core.Transport>()

    fun attach(t: app.mauri.sukrara.core.Transport) { extra += t }

    private fun publish(ev: Event, lane: Lane, key: String? = null, relays: Boolean = true) {
        if (relays && Features.value.nostr) pool().publish(ev, lane, key)
        if (key != "backup" && key != "invite") extra.forEach { it.publish(ev, lane, key) }   // relays only
    }

    /** THE entry point for every incoming event, from any transport ([via] = `relay:<host>` / `ble:<peer>`). */
    fun onEvent(ev: JSONObject, via: String) {
        scope.launch(serial) {
            when {
                ev.optInt("kind") == Proto.WRAP -> onWrap(ev, via)
                ev.optInt("kind") == Proto.CHUNK && st?.let { Proto.tagValue(ev, "c") == Proto.treatmentsC(it.sid) } == true -> onTreatment(ev, via)
            }
        }
    }

    private fun onWrap(ev: JSONObject, via: String) {
        val s = st ?: return
        val id = ev.optString("id")
        if (id.isEmpty() || !db.markSeen(id)) return
        val e = runCatching { Event.fromJson(ev.toString()) }.getOrNull() ?: return
        if (!e.verify()) return log.i("wrap $id: bad signature, ignored")
        val u = Proto.unwrap(s.i, e) ?: return log.i("wrap ${id.take(8)}: cannot unwrap, ignored")
        if (u.kind != Proto.RUMOR_TO_OWNER || u.body.optInt("v") != 2) return
        val known = s.followers.lastOrNull { it.f == u.sender }
        val profile = app.mauri.sukrara.core.Profile.of(u.body.optJSONObject("profile"))
        val name = profile?.name?.ifBlank { null } ?: "?"
        when (u.body.optString("type")) {
            "request" -> {
                if (known != null && known.revoked == null) {
                    log.i("repeat request from approved follower ${known.name} → re-deliver key")
                    return deliverKey(u.sender)
                }
                val token = u.body.optString("token")
                val inv = s.invites.firstOrNull { it.t == token }
                if (inv == null || inv.x < Proto.nowS()) {
                    return log.i("request from '$name' (${u.sender.take(12)}) REJECTED: unknown or expired invite token")
                }
                if (s.pending.any { it.f == u.sender }) return
                val p = PendingRequest(u.sender, name, token, Proto.matchCode(u.sender, token), Proto.nowS(), profile?.avatar)
                s.pending += p
                save()
                log.i("access request from '$name' (${u.sender.take(12)}) code ${p.code} via ${via.removePrefix("relay:")}")
                if (!foreground) {
                    Notifications.post(ctx, Notifications.ID_REQUEST, Notifications.REQUESTS,
                        "$name wants to see your glucose", "Code ${p.code} · open Sukrara to allow or deny")
                }
                publishUi()
            }
            "profile" -> when {
                profile == null -> log.i("profile update from ${u.sender.take(12)} ignored (empty)")
                known == null || known.revoked != null -> log.i("profile update from ${u.sender.take(12)} ignored (not an approved follower)")
                else -> {
                    val idx = s.followers.indexOfLast { it.f == u.sender }
                    s.followers[idx] = known.copy(name = name, avatar = profile.avatar)
                    save()
                    publishBackup()
                    log.i("profile update: ${known.name} is now '$name'")
                    publishUi()
                }
            }
            "rekey" -> when {
                known != null && known.revoked == null -> { log.i("rekey request from ${known.name} (has ${u.body.optString("have")})"); deliverKey(u.sender) }
                known != null -> { log.i("rekey request from revoked ${known.name} → revoked notice"); sendRevoked(u.sender) }
                else -> log.i("rekey request from unknown ${u.sender.take(12)} ignored")
            }
            "history" -> when {
                known == null || known.revoked != null -> log.i("history request from ${known?.name ?: u.sender.take(12)} ignored (not an approved follower)")
                Proto.nowS() - u.createdAt > 3600 -> log.i("history request from ${known.name} ignored (stale)")
                Proto.nowS() - historyRepublishedAt < HISTORY_MIN_GAP_S ->
                    log.i("history request from ${known.name} ignored (republished ${Proto.nowS() - historyRepublishedAt} s ago)")
                else -> republishHistory("asked by ${known.name}", u.body.optLong("since"))
            }
        }
    }

    fun approve(f: String) = scope.launch(serial) {
        val s = st ?: return@launch
        val p = s.pending.firstOrNull { it.f == f } ?: return@launch
        if (s.invites.none { it.t == p.token && it.x > Proto.nowS() }) {
            s.pending.removeIf { it.token == p.token }
            save()
            publishUi()
            return@launch log.i("NOT approved '${p.name}': its invite was already used or expired")
        }
        // single-use: the invite and every other request made with it are gone (a second request on one invite
        // means the link reached someone else too)
        s.pending.removeIf { it.token == p.token }
        s.invites.removeIf { it.t == p.token }
        s.followers.removeIf { it.f == f }
        s.followers += Follower(f, p.name, Proto.nowS(), avatar = p.avatar)
        save()
        deliverKey(f)
        publishBackup()
        republishHistory("new follower ${p.name}")
        republishTreatments("new follower")
        Notifications.cancel(ctx, Notifications.ID_REQUEST)
        log.i("APPROVED '${p.name}' (${f.take(12)}) code ${p.code} at epoch ${s.current.n}")
        publishUi()
    }

    fun deny(f: String) = scope.launch(serial) {
        val s = st ?: return@launch
        val p = s.pending.firstOrNull { it.f == f } ?: return@launch
        s.pending.removeIf { it.token == p.token }                            // a denied token is burnt, with its other requests
        s.invites.removeIf { it.t == p.token }
        save()
        publishBackup()
        Notifications.cancel(ctx, Notifications.ID_REQUEST)
        log.i("DENIED '${p.name}' (${f.take(12)}) code ${p.code} — nothing sent")
        publishUi()
    }

    /** Revoke = revoked notice + rotation (new epoch, deliveries to the rest, roster, backup, last 24 h re-encrypted). */
    fun revoke(f: String) = scope.launch(serial) {
        val s = st ?: return@launch
        val idx = s.followers.indexOfLast { it.f == f && it.revoked == null }
        if (idx < 0) return@launch
        val fol = s.followers[idx]
        s.followers[idx] = fol.copy(revoked = Proto.nowS())
        s.writers -= f
        save()
        _ui.update { it.copy(busy = "Removing ${fol.name}: rotating keys…") }
        sendRevoked(f)
        rotate("revoked ${fol.name}")
        _ui.update { it.copy(busy = null) }
        publishUi()
    }

    private fun publishUi() {
        val s = st
        val now = Proto.nowS()
        _ui.update {
            it.copy(
                hasIdentity = s != null,
                profile = s?.profile,
                iPub = s?.iPub,
                dPub = s?.dPub,
                epoch = s?.current?.n ?: 0,
                last = readings.lastEntry()?.value ?: it.last,
                chart = readings.tailMap(now - 12 * 3600).values.toList(),
                synthetic = s?.synthetic ?: false,
                followers = s?.followers?.toList() ?: emptyList(),
                pending = s?.pending?.toList() ?: emptyList(),
                invite = invite?.takeIf { (_, x) -> x > now }?.first,
                inviteExpiry = invite?.second ?: 0,
                backupSavedAt = s?.backupSavedAt ?: 0,
                backupPublishedAt = s?.backupPublishedAt ?: 0,
                liveSent = liveSent,
                chunksSent = chunksSent,
                inboxLive = foreground && pool?.hasSubscription("inbox") == true,
                bleOn = ble != null,
                nearbyUntil = nearby?.x ?: 0,
                writers = s?.writers?.toSet() ?: emptySet(),
                // deleted since (by anyone) → no longer listed; the merged log keeps the tombstone
                treatments = db.treatments().also { Treat.init(ctx) }.filter { (tr, _, _) -> Treat.store.entry(tr.optString("id"))?.second?.del != true }
                    .map { (tr, author, via) ->
                    val who = s?.followers?.lastOrNull { it.f == author }?.name ?: author.take(8)
                    "${java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(tr.optLong("t") * 1000))} " +
                        "${tr.optString("k")} ${tr.optDouble("a")} ${tr.optString("n")} — $who via ${via.substringBefore(":")}"
                },
            )
        }
    }

    private fun isEmulator() = android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.HARDWARE.contains("ranchu")

    companion object {
        const val JUGGLUCO = "http://127.0.0.1:17580"
        const val BACKFILL_HOURS = 25L
        const val CHUNK_EVERY_S = 300L
        const val LIVE_MAX_AGE_S = 600L
        const val INVITE_TTL_S = 24 * 3600L
        const val HISTORY_MIN_GAP_S = 600L
    }
}
