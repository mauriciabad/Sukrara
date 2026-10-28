// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.follower

import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.LinkState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.ReadingSource
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.SourceStatus
import app.mauri.sukrara.core.model.storage.ReadingsRepository
import app.mauri.sukrara.core.model.storage.TreatmentDelivery
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.core.model.storage.TreatmentsRepository
import app.mauri.sukrara.protocol.KeyPair
import app.mauri.sukrara.protocol.NostrCrypto
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.ble.BleMessages
import app.mauri.sukrara.protocol.event.AlertPayload
import app.mauri.sukrara.protocol.event.EventFactory
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.protocol.event.Rumor
import app.mauri.sukrara.protocol.event.SensorLife
import app.mauri.sukrara.protocol.event.SettingsPayload
import app.mauri.sukrara.protocol.event.TreatmentCodec
import app.mauri.sukrara.protocol.event.WireReading
import app.mauri.sukrara.protocol.follow.FollowResult
import app.mauri.sukrara.protocol.follow.FollowStatus
import app.mauri.sukrara.protocol.follow.FollowerState
import app.mauri.sukrara.protocol.follow.Item
import app.mauri.sukrara.protocol.follow.StreamFollower
import app.mauri.sukrara.protocol.invite.InviteFailure
import app.mauri.sukrara.protocol.invite.InviteLink
import app.mauri.sukrara.protocol.invite.Invites
import app.mauri.sukrara.share.ble.NearbyEvent
import app.mauri.sukrara.share.ble.NearbyLink
import app.mauri.sukrara.share.ble.NearbyStatus
import app.mauri.sukrara.share.ble.NearbyTransport
import app.mauri.sukrara.share.ble.NearbyTransportFactory
import app.mauri.sukrara.share.owner.usecase.TreatmentWire
import app.mauri.sukrara.share.transport.RelayFilter
import app.mauri.sukrara.share.transport.RelayInbound
import app.mauri.sukrara.share.transport.RelayStatus
import app.mauri.sukrara.share.transport.RelayTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

/** Following as the person sees it. */
sealed interface FollowState {
    data object Loading : FollowState

    data object NotFollowing : FollowState

    /** The Keystore refused to open the follower record (often temporary). Nothing is wiped; the stored data stays. */
    data class Unreadable(val transient: Boolean) : FollowState

    /** The record is damaged: Stop following, then join again. */
    data object Damaged : FollowState

    data class Following(
        val status: FollowStatus,
        val wearer: Profile?,
        /** The 4 digits the wearer may be asked to type when the invite was tried by more than one phone. */
        val code: String,
        val canWrite: Boolean,
        val lastHeardAt: Instant?,
        val relays: Map<String, RelayStatus>,
    ) : FollowState
}

enum class JoinResult { Joined, InvalidLink, NotFound, Offline, Expired, WrongWearer, AlreadyFollowing, KeyStoreFailed }

/** What the wearer told this follower (a notification on the info channel). */
enum class FollowerNotice { InviteUsed, Declined, Revoked }

/** A relay pool for [relays] that answers NIP-42 AUTH as [authKey] (F, or the invite key while joining). */
fun interface RelayTransportFactory {
    fun create(relays: List<String>, authKey: () -> KeyPair?): RelayTransport
}

/**
 * The follower role (PROTOCOL-v2 §Follower): joins with an invite link, keeps the follower record (F, the read keys)
 * in the SecureStore, and — exactly while the monitoring service hosts it as the role's [ReadingSource] — keeps the
 * relay subscriptions, feeds every event to [StreamFollower] (the acceptance rules, pinned by the sync fixtures) and
 * hands what it accepts on: readings to the hub, the wearer's sensor, treatments to the log, `alert` events and the
 * mirrored settings to the alert runtime, notices to [onNotice]. Sends the access request, rekey and history
 * requests, and a writer's treatment entries. One use-case at a time (serial dispatcher + one lock). Logs nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowerEngine(
    private val crypto: NostrCrypto,
    private val store: FollowerStore,
    private val transports: RelayTransportFactory,
    private val readingsRepo: ReadingsRepository,
    private val treatments: TreatmentsRepository,
    private val clock: Clock,
    dispatcher: CoroutineDispatcher,
    /** The hub's link (`GlucoseHub.setLink`): wearer name, last heard, removed. */
    private val setLink: (LinkState?) -> Unit = {},
    private val onNotice: (FollowerNotice, Profile?) -> Unit = { _, _ -> },
    private val joinTimeoutMs: Long = JOIN_TIMEOUT_MS,
    /** The Bluetooth path to the wearer's phone (null: relays only). */
    private val nearbyTransports: NearbyTransportFactory? = null,
) : ReadingSource {
    private val serial = dispatcher.limitedParallelism(1)
    private val lock = Mutex()
    private val events = EventFactory(crypto)

    private val _state = MutableStateFlow<FollowState>(FollowState.Loading)
    val state: StateFlow<FollowState> = _state.asStateFlow()

    private val _sensor = MutableStateFlow<SensorState>(SensorState.None)
    override val sensor: StateFlow<SensorState> = _sensor.asStateFlow()

    private val _status = MutableStateFlow<SourceStatus>(SourceStatus.NotStarted)
    override val status: StateFlow<SourceStatus> = _status.asStateFlow()

    /**
     * Accepted `alert` events are remembered as seen at once (dedupe), so none may be dropped on the way: buffered
     * until the alert runtime (one collector) takes them.
     */
    private val _alerts = Channel<AlertPayload>(Channel.UNLIMITED)

    /** The wearer's `alert` events, for the follower's own alert runtime (which keeps its mute to itself). One collector. */
    val alerts: Flow<AlertPayload> = _alerts.receiveAsFlow()

    private val latestSettings = MutableStateFlow<SettingsPayload?>(null)
    private val loadedOnce = CompletableDeferred<Unit>()

    /**
     * The wearer's newest mirrored settings (null = none received). Nothing before the record is read, so the alert
     * runtime never judges with "none" for a moment at start.
     */
    val wearerSettings: Flow<SettingsPayload?> =
        flow {
            loadedOnce.await()
            emitAll(latestSettings)
        }

    private val _nearbyStatus = MutableStateFlow<NearbyStatus>(NearbyStatus.Stopped)

    /** The Bluetooth link to the wearer's phone (Following page); following goes on over the internet in every state. */
    val nearbyStatus: StateFlow<NearbyStatus> = _nearbyStatus.asStateFlow()

    @Volatile private var nearby: NearbyTransport? = null

    private var record: FollowerRecord? = null
    private var follower: StreamFollower? = null
    private var loaded = false
    private var persistedAt = 0L

    // the connection (while attached)
    @Volatile private var transport: RelayTransport? = null
    private var sink: ProducerScope<List<Reading>>? = null
    private var jobs = emptyList<Job>()
    private var subscribed = emptyMap<String, RelayFilter>()
    private var relayUrls = emptyList<String>()
    private var historyScheduled = false
    private val treatmentCreatedAt = HashMap<String, Long>()

    private fun now() = clock.now().epochSecond

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(serial) { lock.withLock { block() } }

    /** Whether a follower record exists (the monitoring service runs this source only then). */
    suspend fun isFollowing(): Boolean =
        locked {
            loadLocked()
            record != null || _state.value is FollowState.Unreadable
        }

    /** Reads the record once (the app's `ready`). */
    suspend fun load() = locked { loadLocked() }

    private suspend fun loadLocked() {
        if (loaded && _state.value !is FollowState.Unreadable) return
        loaded = true
        val l = store.load()
        loadedOnce.complete(Unit)
        when (l) {
            FollowerStore.Load.Missing -> {
                record = null
                _state.value = FollowState.NotFollowing
                setLink(null)
            }

            is FollowerStore.Load.Unreadable -> {
                _state.value = FollowState.Unreadable(l.transient)
            }

            FollowerStore.Load.Damaged -> {
                _state.value = FollowState.Damaged
            }

            is FollowerStore.Load.Loaded -> {
                adopt(l.record)
            }
        }
    }

    private fun adopt(r: FollowerRecord) {
        record = r
        follower = StreamFollower(crypto, r.stream)
        persistedAt = now()
        latestSettings.value = r.settings
        _sensor.value = sensorState(r.sensor)
        render()
    }

    // ------------------------------------------------------------------------------------------------ joining

    /**
     * Opens the invite behind [text] (its wrap on the default relays, AUTH as the invite key, up to [joinTimeoutMs]:
     * the wearer's phone may still be publishing it), then makes a new follower key F and saves the record. The access
     * request goes out once the monitoring service connects. A follow that ended (declined, used by another phone,
     * removed) is replaced; a running one isn't.
     */
    suspend fun join(text: String, me: Profile?): JoinResult {
        val link = InviteLink.parse(text) ?: return JoinResult.InvalidLink
        if (!canJoin()) return JoinResult.AlreadyFollowing
        val key = Invites.inviteKey(crypto, link)
        val lookup = transports.create(Wire.DEFAULT_RELAYS) { key }
        val opened: Invites.Result
        var anyAnswered = false
        lookup.start()
        try {
            val filter = RelayFilter(kinds = listOf(Wire.KIND_WRAP), p = listOf(key.public.hex))
            // waits between lookups (each lookup itself ends at its relays' end-of-stored)
            var waited = 0L
            var result: Invites.Result
            while (true) {
                val found = lookup.fetch(listOf(filter), FETCH_TIMEOUT_MS)
                anyAnswered = anyAnswered || found.answered > 0
                result = Invites.open(crypto, link, found.events, now())
                val failed = result as? Invites.Result.Failed ?: break
                if (failed.reason != InviteFailure.NothingFound && failed.reason != InviteFailure.DoesNotOpen) break
                if (waited + JOIN_RETRY_MS > joinTimeoutMs) break
                delay(JOIN_RETRY_MS)
                waited += JOIN_RETRY_MS
            }
            opened = result
        } finally {
            lookup.stop()
        }
        val invite =
            when (opened) {
                is Invites.Result.Opened -> {
                    opened.invite
                }

                is Invites.Result.Failed -> {
                    return when (opened.reason) {
                        InviteFailure.Expired -> JoinResult.Expired
                        InviteFailure.FingerprintMismatch -> JoinResult.WrongWearer
                        else -> if (anyAnswered) JoinResult.NotFound else JoinResult.Offline
                    }
                }
            }
        return locked {
            if (!canJoinLocked()) return@locked JoinResult.AlreadyFollowing
            val f = crypto.generate()
            val stream = FollowerState(follower = f, identity = invite.identity, relays = invite.relays.ifEmpty { Wire.DEFAULT_RELAYS })
            val r =
                FollowerRecord(
                    stream = stream,
                    token = invite.token,
                    code = Invites.matchCode(f.public, invite.token),
                    me = me,
                    wearer = invite.profile,
                    joinedAt = now(),
                )
            // never mix a previous person's history in
            wipeData()
            if (!store.save(r)) return@locked JoinResult.KeyStoreFailed
            loaded = true
            treatmentCreatedAt.clear()
            latestSettings.value = null
            adopt(r)
            // attached already (a follow that had ended): reconnect with the new key
            sink?.let { out ->
                disconnect()
                connect(out)
            }
            JoinResult.Joined
        }
    }

    private suspend fun canJoin() = locked { canJoinLocked() }

    private suspend fun canJoinLocked(): Boolean {
        loadLocked()
        return when (val s = _state.value) {
            is FollowState.Following -> s.status in ENDED
            FollowState.Unreadable(true) -> false
            else -> true
        }
    }

    // ------------------------------------------------------------------------------------------------ leaving

    /**
     * Stop following: the connection closes, the record (F, the read keys) and the wearer's readings and treatments go.
     * Nothing is sent: the wearer's next rotation leaves this key out. False when the Keystore refused the delete
     * (the person can try again).
     */
    suspend fun unfollow(): Boolean =
        locked {
            val out = sink
            disconnect()
            // the data first: a crash before the key delete is healed by the next unfollow / join
            wipeData()
            val deleted = store.delete()
            if (deleted) {
                record = null
                follower = null
                latestSettings.value = null
                _sensor.value = SensorState.None
                _status.value = SourceStatus.NotStarted
                _state.value = FollowState.NotFollowing
                setLink(null)
            } else {
                // still following: back online (the stored data is gone; the backfill brings it again)
                out?.let { connect(it) }
            }
            deleted
        }

    private suspend fun wipeData() {
        readingsRepo.deleteBefore(Instant.ofEpochSecond(Long.MAX_VALUE / 1000))
        treatments.deleteAll()
    }

    // ------------------------------------------------------------------------------------------------ the source

    /** Collected by the hub while the monitoring service runs: connected exactly that long. */
    override val readings: Flow<List<Reading>> =
        channelFlow {
            locked { connect(this) }
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    locked {
                        // what changed since the last save (last heard, alerts seen) survives the stop
                        record?.let { commit(it, force = true) }
                        disconnect()
                    }
                }
            }
        }

    /** The network came back: reconnect now instead of waiting out the backoff. */
    fun reconnectNow() {
        transport?.reconnectNow()
    }

    override fun onBluetoothOn() = Unit

    override fun onBluetoothOff() = Unit

    private suspend fun connect(out: ProducerScope<List<Reading>>) {
        loadLocked()
        sink = out
        val r = record
        if (r == null) {
            // an unreadable record: keep trying (5 s doubling to 5 min) while attached; nothing is wiped meanwhile
            if (_state.value is FollowState.Unreadable) {
                _status.value = SourceStatus.Connecting
                jobs = listOf(out.launch { retryLoad(out) })
            }
            return
        }
        val urls = r.stream.relays.ifEmpty { Wire.DEFAULT_RELAYS }
        val t = transports.create(urls) { record?.stream?.follower }
        transport = t
        relayUrls = urls
        historyScheduled = false
        subscribed = emptyMap()
        _status.value = SourceStatus.Connecting
        jobs =
            listOf(
                out.launch(start = CoroutineStart.UNDISPATCHED) { t.inbound.collect { m -> locked { safely { onInbound(m) } } } },
                out.launch { t.status.collect { locked { render() } } },
            )
        resubscribe()
        t.start()
        startNearby(out, r)
        if (r.stream.status == FollowStatus.New || r.stream.status == FollowStatus.Requested) sendRequest()
        resendPending()
        render()
    }

    /** Bluetooth runs beside the relays while attached; copies are deduplicated by event id (B§6 "Both transports"). */
    private fun startNearby(out: ProducerScope<List<Reading>>, r: FollowerRecord) {
        val nb = nearbyTransports?.create(nearbyIdentity(r)) ?: return
        nearby = nb
        jobs = jobs +
            out.launch(start = CoroutineStart.UNDISPATCHED) { nb.events.collect { e -> locked { safely { onNearby(e) } } } } +
            out.launch { nb.status.collect { _nearbyStatus.value = it } }
        nb.start()
    }

    private fun nearbyIdentity(r: FollowerRecord) = NearbyLink.Identity(r.stream.identity, r.stream.sid, r.stream.follower)

    private suspend fun onNearby(e: NearbyEvent) {
        when (e) {
            is NearbyEvent.Event -> receive(e.event, NEARBY_VIA)

            // another wearer's phone: leave it and keep looking (B§6 "Which owner")
            is NearbyEvent.Info -> if (e.owner == BleMessages.Owner.Other) nearby?.leave(LEAVE_OTHER_PHONE_S)
        }
    }

    private suspend fun retryLoad(out: ProducerScope<List<Reading>>) {
        var wait = RETRY_FIRST_MS
        while (true) {
            delay(wait)
            wait = minOf(wait * 2, RETRY_MAX_MS)
            val connected =
                locked {
                    loadLocked()
                    if (record == null) return@locked _state.value !is FollowState.Unreadable
                    // this job ends here: the connection's own jobs replace it
                    jobs = emptyList()
                    connect(out)
                    true
                }
            if (connected) return
        }
    }

    /** One message that fails (a store write) is dropped; it never ends the source (and the service with it). */
    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Unit
        }
    }

    private fun disconnect() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        transport?.stop()
        transport = null
        nearby?.stop()
        nearby = null
        _nearbyStatus.value = NearbyStatus.Stopped
        sink = null
        subscribed = emptyMap()
        if (_status.value != SourceStatus.NotStarted) _status.value = SourceStatus.NotStarted
    }

    /** The access request (gift wrap to I, 7 d): on every connect while waiting, as the Mac does. */
    private suspend fun sendRequest() {
        val r = record ?: return
        val identity = r.stream.identity ?: return
        val wrap = events.gift(r.stream.follower, identity, Rumor.Request(r.token, r.me), now(), now() + Wire.TTL_REQUEST)
        send(wrap)
        val f = follower ?: return
        f.requested()
        commit(r.copy(stream = f.state, requestedAt = now()), force = true)
    }

    /** The REQ filters (same as the Mac follower's `FollowerSession.filters`). */
    private suspend fun filters(): Map<String, RelayFilter> {
        val s = follower?.state ?: return emptyMap()
        val now = now()
        val out =
            mutableMapOf(
                INBOX to RelayFilter(kinds = listOf(Wire.KIND_WRAP), p = listOf(s.follower.public.hex), since = now - 3 * Wire.DAY),
            )
        val sid = s.sid
        val identity = s.identity
        if (sid == null || identity == null || s.status != FollowStatus.Active) return out
        out[ROSTER] = RelayFilter(kinds = listOf(Wire.KIND_DATA), authors = listOf(identity.hex), d = listOf(Wire.rosterD(sid)))
        if (s.devices.isEmpty()) return out
        val devices = s.devices.sorted()
        val newest = readingsRepo.range(Instant.ofEpochSecond(now - 7 * Wire.DAY), Instant.ofEpochSecond(now + Wire.HOUR)).lastOrNull()
        out[CHUNKS] =
            RelayFilter(
                kinds = listOf(Wire.KIND_DATA),
                authors = devices,
                c = listOf(Wire.glucoseC(sid)),
                since = chunkSince(newest?.t?.epochSecond, now),
            )
        out[LIVE] = RelayFilter(kinds = listOf(Wire.KIND_LIVE, Wire.KIND_ALERT), authors = devices, since = now - 120)
        out[SETTINGS] = RelayFilter(kinds = listOf(Wire.KIND_DATA), authors = devices, d = listOf(Wire.settingsD(sid)))
        out[TREATMENTS] =
            RelayFilter(
                kinds = listOf(Wire.KIND_DATA),
                authors = (devices + s.writers).distinct().sorted(),
                c = listOf(Wire.treatmentsC(sid)),
                since = now - Wire.TTL_TREATMENT,
            )
        return out
    }

    private suspend fun resubscribe() {
        val t = transport ?: return
        val next = filters()
        (subscribed.keys - next.keys).forEach(t::close)
        next.forEach { (id, f) -> if (subscribed[id] != f) t.subscribe(id, listOf(f)) }
        subscribed = next
    }

    // ------------------------------------------------------------------------------------------------ inbound

    private suspend fun onInbound(m: RelayInbound) {
        when (m) {
            is RelayInbound.Event -> receive(m.event, m.relay)
            is RelayInbound.Ok -> if (m.accepted) markDelivered(m.eventId)
            is RelayInbound.EndOfStored -> if (m.subscription == CHUNKS) scheduleHistoryCheck()
            else -> Unit
        }
    }

    /** Ids of our own pending treatment events → entry id (marked Delivered when a relay accepts). */
    private val pendingEvents = HashMap<String, Pair<String, Instant>>()

    private suspend fun receive(ev: SignedEvent, relay: String) {
        val f = follower ?: return
        val r = record ?: return
        val before = f.state
        val result = f.onEvent(ev, now())
        var next = r.copy(stream = f.state)
        var resubscribe = false
        when (result) {
            is FollowResult.Ignored -> {
                result.rekey?.let { rekey ->
                    r.stream.identity?.let {
                        send(
                            events.gift(
                                r.stream.follower,
                                it,
                                rekey,
                                now(),
                                now() + Wire.TTL_REQUEST,
                            ),
                        )
                    }
                }
            }

            is FollowResult.Rejected -> {
                Unit
            }

            is FollowResult.Accepted -> {
                when (val item = result.item) {
                    is Item.Key -> {
                        resubscribe = true
                    }

                    is Item.Roster -> {
                        next = next.copy(wearer = item.roster.profile ?: next.wearer)
                        resubscribe = before.devices.sorted() != f.state.devices.sorted() || before.writers != f.state.writers
                    }

                    is Item.Chunk -> {
                        next = heard(next, item.chunk.readings)
                    }

                    is Item.Live -> {
                        item.live.reading?.let { next = heard(next, listOf(it)) }
                        if (item.sensorChanged) {
                            next = next.copy(sensor = item.live.sensor)
                            _sensor.value = sensorState(item.live.sensor)
                        }
                    }

                    is Item.Alert -> {
                        _alerts.trySend(item.alert)
                    }

                    is Item.Treatment -> {
                        storeTreatment(item, relay)
                    }

                    is Item.Settings -> {
                        next = next.copy(settings = item.settings)
                        latestSettings.value = item.settings
                    }

                    Item.Revoked -> {
                        resubscribe = true
                        onNotice(FollowerNotice.Revoked, next.wearer)
                    }

                    Item.InviteUsed -> {
                        onNotice(FollowerNotice.InviteUsed, next.wearer)
                    }

                    Item.Declined -> {
                        onNotice(FollowerNotice.Declined, next.wearer)
                    }
                }
            }
        }
        val trustChanged =
            before.copy(seen = emptyMap(), rekeyAt = emptyMap(), alertsSeen = emptyMap(), sensorCreatedAt = 0) !=
                f.state.copy(seen = emptyMap(), rekeyAt = emptyMap(), alertsSeen = emptyMap(), sensorCreatedAt = 0)
        commit(next, force = trustChanged || next.wearer != r.wearer || next.settings != r.settings || next.sensor != r.sensor)
        // a first key or a new epoch: the Bluetooth link proves who we are and checks the phone with it
        if (before.sid != f.state.sid || before.epochs.keys != f.state.epochs.keys) nearby?.update(nearbyIdentity(next))
        val out = sink
        if (out != null && transport != null && next.stream.relays.isNotEmpty() && next.stream.relays != relayUrls) {
            // a key delivery or roster moved the stream to other relays: connect there (outside this collector,
            // which the reconnect cancels)
            out.launch {
                locked {
                    if (sink === out) {
                        disconnect()
                        connect(out)
                    }
                }
            }
        } else if (resubscribe) {
            resubscribe()
        }
    }

    /** Readings to the hub (it stores them; the stored history is the source of truth for every surface). */
    private suspend fun heard(r: FollowerRecord, wire: List<WireReading>): FollowerRecord {
        if (wire.isEmpty()) return r
        val batch = wire.sortedBy { it.t }.map { Reading(Instant.ofEpochSecond(it.t), it.sgv, it.rate, SourceId.Relay) }
        sink?.send(batch)
        val newest = batch.last().t
        if (_status.value !is SourceStatus.Live || (_status.value as SourceStatus.Live).lastReadingAt < newest) {
            _status.value = SourceStatus.Live(newest)
        }
        // the reading's time, not arrival: a re-fetched backfill after a restart never makes a silent wearer look fresh
        return r.copy(lastHeardAt = maxOf(r.lastHeardAt ?: 0, minOf(now(), newest.epochSecond)))
    }

    private suspend fun storeTreatment(item: Item.Treatment, relay: String) {
        val p = item.treatment
        if (p.kind !in TREATMENT_KINDS) return
        val stored = treatments.get(p.id)
        val record =
            TreatmentRecord(
                treatment = TreatmentWire.toTreatment(p),
                creator = record?.stream?.creators?.get(p.id) ?: item.author,
                author = item.author,
                via = relay,
                receivedAt = clock.now(),
                delivery = TreatmentDelivery.Delivered,
            )
        if (item.override && stored != null) treatments.replace(record) else treatments.upsertIfNewer(record)
    }

    // ------------------------------------------------------------------------------------------------ writer

    /** May add entries now (active, F in the roster's writers). */
    fun canWrite(): Boolean = record?.canWrite == true

    /** F's public key while following (a writer's entries carry it as creator). */
    fun followerKey(): String? =
        record
            ?.stream
            ?.follower
            ?.public
            ?.hex

    /**
     * A writer's entry stored on this phone (add / edit / delete): sent at once under the newest epoch, and again on
     * every connect until a relay accepts it. Ignored when not allowed to write.
     */
    suspend fun publishTreatment(entry: TreatmentRecord) =
        locked {
            val ev = treatmentEvent(entry) ?: return@locked
            pendingEvents[ev.id] = entry.treatment.id to entry.treatment.modifiedAt
            send(ev)
        }

    private fun treatmentEvent(entry: TreatmentRecord): SignedEvent? {
        val r = record?.takeIf { it.canWrite } ?: return null
        val s = r.stream
        val sid = s.sid ?: return null
        val newest = s.epochs.values.maxByOrNull { it.n } ?: return null
        val payload =
            TreatmentWire
                .toPayload(
                    entry,
                ).copy(origin = TreatmentCodec.ORIGIN_FOLLOWER, primed = null, pen = null, penDoses = null)
        val at = maxOf(now(), (treatmentCreatedAt[payload.id] ?: 0) + 1)
        treatmentCreatedAt[payload.id] = at
        return events.treatment(s.follower, newest.key.public, sid, payload, at)
    }

    private suspend fun resendPending() {
        if (record?.canWrite != true) return
        treatments
            .changedSince(Instant.ofEpochSecond(now() - Wire.TTL_TREATMENT))
            .filter { it.delivery == TreatmentDelivery.Pending }
            .forEach { entry ->
                treatmentEvent(entry)?.let { ev ->
                    pendingEvents[ev.id] = entry.treatment.id to entry.treatment.modifiedAt
                    send(ev)
                }
            }
    }

    private suspend fun markDelivered(eventId: String) {
        val (id, version) = pendingEvents.remove(eventId) ?: return
        val stored = treatments.get(id) ?: return
        // only the version that event carried: a newer edit stays pending until its own event is accepted
        if (stored.delivery == TreatmentDelivery.Pending &&
            stored.treatment.modifiedAt == version
        ) {
            treatments.replace(stored.copy(delivery = TreatmentDelivery.Delivered))
        }
    }

    // ------------------------------------------------------------------------------------------------ history

    /** After the chunk backfill (its first end-of-stored), 90 s later and once more 90 s after that. */
    private fun scheduleHistoryCheck() {
        val out = sink ?: return
        if (historyScheduled) return
        historyScheduled = true
        jobs = jobs +
            out.launch {
                repeat(2) {
                    delay(HISTORY_DELAY_MS)
                    locked { checkHistory() }
                }
            }
    }

    /**
     * PROTOCOL-v2 "Missing history": once after the backfill (90 s after connecting), the hours of the last 12 without a
     * stored reading, asked for at most every 30 min, and only while the stream is live (a real gap has nothing to ask).
     */
    private suspend fun checkHistory() {
        val r = record?.takeIf { it.stream.status == FollowStatus.Active } ?: return
        val identity = r.stream.identity ?: return
        val now = now()
        if (now - r.historyAskedAt < HISTORY_INTERVAL) return
        val stored =
            readingsRepo.range(Instant.ofEpochSecond(now - 13 * Wire.HOUR), Instant.ofEpochSecond(now + Wire.HOUR)).map {
                it.t.epochSecond
            }
        val since = missingHours(stored, now)?.minOrNull() ?: return
        send(events.gift(r.stream.follower, identity, Rumor.History(since), now, now + Wire.TTL_HISTORY_REQUEST))
        commit(r.copy(historyAskedAt = now), force = true)
    }

    // ------------------------------------------------------------------------------------------------ plumbing

    /** To the relays and the wearer's phone nearby (written only once it proved it's the wearer's). */
    private fun send(ev: SignedEvent) {
        transport?.publish(ev)
        val nb = nearby ?: return
        // a writer's entry the wearer's phone took over Bluetooth is sent, like a relay's OK
        sink?.launch { if (nb.send(ev)) locked { markDelivered(ev.id) } }
    }

    /** The record in memory at once; on disk when what it trusts changed, or every [PERSIST_EVERY] otherwise. */
    private suspend fun commit(r: FollowerRecord, force: Boolean) {
        record = r
        if (force || now() - persistedAt >= PERSIST_EVERY) {
            if (store.save(r)) persistedAt = now()
        }
        render()
    }

    private fun render() {
        val r = record ?: return
        val revoked = r.stream.status == FollowStatus.Revoked
        _state.value =
            FollowState.Following(
                status = r.stream.status,
                wearer = r.wearer,
                code = r.code,
                canWrite = r.canWrite,
                lastHeardAt = r.lastHeardAt?.let(Instant::ofEpochSecond),
                relays = transport?.status?.value.orEmpty(),
            )
        setLink(LinkState(r.wearer?.name, r.lastHeardAt?.let(Instant::ofEpochSecond), revoked))
    }

    private fun sensorState(life: SensorLife?): SensorState =
        when {
            life == null -> SensorState.None
            now() < life.warmupEnd -> SensorState.WarmingUp(Instant.ofEpochSecond(life.warmupEnd))
            now() >= life.end -> SensorState.Ended
            else -> SensorState.Active(Instant.ofEpochSecond(life.end))
        }

    companion object {
        const val JOIN_TIMEOUT_MS = 25_000L
        private const val JOIN_RETRY_MS = 3_000L
        private const val FETCH_TIMEOUT_MS = 8_000L
        private const val HISTORY_DELAY_MS = 90_000L
        private const val HISTORY_INTERVAL = 30 * 60L
        private const val PERSIST_EVERY = 300L
        private const val RETRY_FIRST_MS = 5_000L
        private const val RETRY_MAX_MS = 300_000L
        private val ENDED = setOf(FollowStatus.Declined, FollowStatus.InviteUsed, FollowStatus.Revoked)
        private val TREATMENT_KINDS = setOf("rapid", "long", "carbs", "note")

        const val NEARBY_VIA = "ble"
        private const val LEAVE_OTHER_PHONE_S = 60L

        const val INBOX = "inbox"
        const val ROSTER = "roster"
        const val CHUNKS = "chunks"
        const val LIVE = "live"
        const val SETTINGS = "settings"
        const val TREATMENTS = "treatments"

        /** Chunk backfill: an hour before the newest stored reading, at least the last 13 h, at most 7 days. */
        fun chunkSince(newestStored: Long?, now: Long): Long =
            maxOf(
                now - 7 * Wire.DAY,
                minOf(
                    now - 13 * Wire.HOUR,
                    (newestStored ?: now) - Wire.HOUR,
                ),
            )

        /** Hour starts of the last 12 whole hours without a stored reading; null while the newest is > 15 min old. */
        fun missingHours(stored: List<Long>, now: Long): List<Long>? {
            val newest = stored.maxOrNull() ?: return null
            if (now - newest > 15 * 60) return null
            val current = Wire.hourOf(now)
            val have = stored.map(Wire::hourOf).toSet()
            return (1..12).map { current - it * Wire.HOUR }.filter { it !in have }
        }
    }
}
