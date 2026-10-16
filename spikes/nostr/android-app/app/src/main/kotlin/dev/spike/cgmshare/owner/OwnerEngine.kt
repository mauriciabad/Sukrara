// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.cgmshare.owner

import android.content.Context
import android.os.PowerManager
import dev.spike.cgmshare.BuildConfig
import dev.spike.cgmshare.core.EventLog
import dev.spike.cgmshare.core.Lane
import dev.spike.cgmshare.core.Notifications
import dev.spike.cgmshare.core.Proto
import dev.spike.cgmshare.core.Reading
import dev.spike.cgmshare.core.RelayPool
import dev.spike.cgmshare.core.RelayStats
import dev.spike.cgmshare.core.SecureStore
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
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cgmshare:owner").apply { setReferenceCounted(false) }
    private val inboxWake = ctx.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cgmshare:inbox").apply { setReferenceCounted(false) }

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

    private val _ui = MutableStateFlow(OwnerUi())
    val ui: StateFlow<OwnerUi> = _ui
    private val _relays = MutableStateFlow<Map<String, RelayStats>>(emptyMap())
    val relayStats: StateFlow<Map<String, RelayStats>> = _relays

    init { publishUi() }

    fun hasIdentity() = st != null

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
        pool()
        if (Proto.nowS() - s.rosterPublishedAt > 6 * Proto.DAY) publishRoster()
        if (Proto.nowS() - s.backupPublishedAt > 6 * Proto.DAY) publishBackup()
        startSource()
        tick = scope.launch {
            delay(5_000)
            checkInbox("startup")
            while (isActive) {
                delay(CHUNK_EVERY_S * 1000)
                withContext(serial) { onTick() }
            }
        }
        publishUi()
    }

    fun stop() = scope.launch(serial) {
        running = false
        tick?.cancel()
        source?.stop()
        source = null
    }

    /** While the UI is visible: a persistent subscription → access requests show up immediately. */
    fun setForeground(on: Boolean) = scope.launch(serial) {
        foreground = on
        val s = st ?: return@launch
        if (on) {
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

    private fun startSource() {
        source?.stop()
        val s = st ?: return
        val src = if (BuildConfig.DEBUG && s.synthetic) SyntheticSource() else JugglucoSource(JUGGLUCO, http)
        source = src
        src.start(scope, object : GlucoseSource.Sink {
            override fun onReading(r: Reading) {
                wake.acquire(10_000)
                scope.launch(serial) {
                    runCatching { handleReading(r) }.onFailure { log.i("publish failed: $it") }
                    delay(3_000)   // live leaves at once; a due chunk 2 s later
                    if (wake.isHeld) wake.release()
                }
            }

            override fun onHistory(readings: List<Reading>) {
                if (readings.isEmpty()) return
                wake.acquire(90_000)
                scope.launch(serial) { runCatching { handleHistory(readings) }.onFailure { log.i("backfill failed: $it") } }
            }

            override fun onStatus(state: String, error: String?) {
                _ui.update { it.copy(source = state, sourceError = error) }
                if (error != null) log.i("source: $state — $error")
            }
        })
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

    suspend fun createIdentity() = withContext(serial) {
        st = OwnerState.create(synthetic = BuildConfig.DEBUG && isEmulator())
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

    fun credentialId(): String = "CGM Share · " + (st?.let { Proto.shortNpub(it.iPub) } ?: "?")

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
            publish(Proto.liveEvent(s.d, s.current.pub, r, now), Lane.LIVE)
            liveSent++
        }
        prune(now)
        flush(force = false)
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
            val due = force || h < current || now - (lastChunkSentAt[h] ?: 0) >= CHUNK_EVERY_S
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
        if (Proto.nowS() - lastInboxCheck > CHUNK_EVERY_S - 30) scope.launch { checkInbox("tick") }
        db.prune(Proto.nowS() - 8 * Proto.DAY)
    }

    private fun markRecentDirty() {
        val cut = Proto.nowS() - 24 * 3600
        readings.keys.filter { it >= cut }.forEach { dirty += Proto.hourOf(it) }
    }

    // ============================================================================================ control events

    private fun publishRoster() {
        val s = st ?: return
        val e = s.current
        // created_at must strictly increase per (author, kind, d), or relays keep the older version
        val at = maxOf(Proto.nowS(), s.rosterPublishedAt + 1)
        publish(Proto.rosterEvent(s.i, e.pub, s.sid, e.n, listOf(s.dPub), s.relays, at), Lane.CONTROL, "roster")
        s.rosterPublishedAt = at
        save()
        log.i("roster published: epoch ${e.n} gk ${e.gk}, devices [${s.dPub.take(12)}]")
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
        markRecentDirty()
        flush(force = true)
        log.i("rotated to epoch ${s.current.n} gk ${s.current.gk} ($why); ${s.active().size} follower(s) re-keyed, " +
            "republishing ${lastChunkSentAt.size} recent chunk(s)")
    }

    // ============================================================================================ sharing

    fun newInvite(): String? {
        val s = st ?: return null
        val token = Proto.newToken()
        val x = Proto.nowS() + INVITE_TTL_S
        val link = Proto.inviteLink(s.iPub, token, x, s.relays)
        invite = link to x
        scope.launch(serial) {
            s.invites.removeIf { it.x < Proto.nowS() }
            s.invites += InviteToken(token, x)
            save()
            publishBackup()
            log.i("invite created (expires in ${INVITE_TTL_S / 3600} h): $link")
            publishUi()
        }
        _ui.update { it.copy(invite = link, inviteExpiry = x) }
        return link
    }

    suspend fun checkInbox(why: String) {
        val s = st ?: return
        if (foreground && pool?.hasSubscription("inbox") == true && why != "manual") return   // live subscription covers it
        if (why != "manual" && why != "startup" && Proto.nowS() - lastInboxCheck < 240) return
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
    private val extra = java.util.concurrent.CopyOnWriteArrayList<dev.spike.cgmshare.core.Transport>()

    fun attach(t: dev.spike.cgmshare.core.Transport) { extra += t }

    private fun publish(ev: Event, lane: Lane, key: String? = null) {
        pool().publish(ev, lane, key)
        extra.forEach { it.publish(ev, lane, key) }
    }

    /** THE entry point for every incoming event, from any transport ([via] = `relay:<host>` / `ble:<peer>`). */
    fun onEvent(ev: JSONObject, via: String) {
        scope.launch(serial) { if (ev.optInt("kind") == Proto.WRAP) onWrap(ev, via) }
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
        val name = u.body.optString("name", "?").take(40)
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
                val p = PendingRequest(u.sender, name, token, Proto.matchCode(u.sender, token), Proto.nowS())
                s.pending += p
                save()
                log.i("access request from '$name' (${u.sender.take(12)}) code ${p.code} via ${via.removePrefix("relay:")}")
                if (!foreground) {
                    Notifications.post(ctx, Notifications.ID_REQUEST, Notifications.REQUESTS,
                        "$name wants to see your glucose", "Code ${p.code} · open CGM Share to allow or deny")
                }
                publishUi()
            }
            "rekey" -> when {
                known != null && known.revoked == null -> { log.i("rekey request from ${known.name} (has ${u.body.optString("have")})"); deliverKey(u.sender) }
                known != null -> { log.i("rekey request from revoked ${known.name} → revoked notice"); sendRevoked(u.sender) }
                else -> log.i("rekey request from unknown ${u.sender.take(12)} ignored")
            }
        }
    }

    fun approve(f: String) = scope.launch(serial) {
        val s = st ?: return@launch
        val p = s.pending.firstOrNull { it.f == f } ?: return@launch
        s.pending.remove(p)
        s.invites.removeIf { it.t == p.token }                                // single-use
        s.followers.removeIf { it.f == f }
        s.followers += Follower(f, p.name, Proto.nowS())
        save()
        deliverKey(f)
        publishBackup()
        Notifications.cancel(ctx, Notifications.ID_REQUEST)
        log.i("APPROVED '${p.name}' (${f.take(12)}) code ${p.code} at epoch ${s.current.n}")
        publishUi()
    }

    fun deny(f: String) = scope.launch(serial) {
        val s = st ?: return@launch
        val p = s.pending.firstOrNull { it.f == f } ?: return@launch
        s.pending.remove(p)
        s.invites.removeIf { it.t == p.token }                                // a denied token is burnt too
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
    }
}
