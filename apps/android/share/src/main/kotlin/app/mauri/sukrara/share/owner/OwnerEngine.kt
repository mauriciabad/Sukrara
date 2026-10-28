// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner

import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.GlucoseHub
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.core.model.storage.TreatmentsRepository
import app.mauri.sukrara.protocol.PublicKeyHex
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.AlertPayload
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.share.ble.NearbyServerCore
import app.mauri.sukrara.share.ble.NearbyStatus
import app.mauri.sukrara.share.owner.usecase.ApproveRequest
import app.mauri.sukrara.share.owner.usecase.ControlEvents
import app.mauri.sukrara.share.owner.usecase.CreateIdentity
import app.mauri.sukrara.share.owner.usecase.CreateInvite
import app.mauri.sukrara.share.owner.usecase.DenyRequest
import app.mauri.sukrara.share.owner.usecase.ExportRecovery
import app.mauri.sukrara.share.owner.usecase.HandleInbox
import app.mauri.sukrara.share.owner.usecase.IdentityConflictDetector
import app.mauri.sukrara.share.owner.usecase.InboxSignal
import app.mauri.sukrara.share.owner.usecase.MirroredSettingsSource
import app.mauri.sukrara.share.owner.usecase.OwnerContext
import app.mauri.sukrara.share.owner.usecase.PublishReadings
import app.mauri.sukrara.share.owner.usecase.PublishSettingsMirror
import app.mauri.sukrara.share.owner.usecase.RemoveFollower
import app.mauri.sukrara.share.owner.usecase.RestoreIdentity
import app.mauri.sukrara.share.owner.usecase.SetProfile
import app.mauri.sukrara.share.owner.usecase.SetWriter
import app.mauri.sukrara.share.owner.usecase.Step
import app.mauri.sukrara.share.owner.usecase.SyncTreatments
import app.mauri.sukrara.share.transport.RelayFilter
import app.mauri.sukrara.share.transport.RelayInbound
import app.mauri.sukrara.share.transport.RelayTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The wearer's sharing coordinator: loads the identity, keeps the relay subscriptions, feeds readings from the
 * [GlucoseHub] to [PublishReadings], the treatment log and the mirrored settings to followers ([SyncTreatments],
 * [PublishSettingsMirror]), runs every use-case one at a time (serial dispatcher + one lock held across the
 * suspending store / ledger calls, so no update overwrites another), persists before publishing, and
 * exposes [state]. Relay failures stay in the per-relay status. Logs nothing that holds keys or glucose values.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class OwnerEngine(
    private val ctx: OwnerContext,
    private val store: OwnerIdentityStore,
    private val transport: RelayTransport,
    private val hub: GlucoseHub,
    private val seen: SeenWraps,
    treatmentLog: TreatmentsRepository,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher,
    private val settings: MirroredSettingsSource = MirroredSettingsSource.None,
    private val nearby: OwnerNearby = OwnerNearby.None,
    /** Called for a new access request (the notification with Allow / Deny lives in the app layer). */
    private val onRequest: (PendingRequest) -> Unit = {},
) {
    private val serial = dispatcher.limitedParallelism(1)
    private val lock = Mutex()
    private val readings = PublishReadings(ctx)
    private val conflicts = IdentityConflictDetector(ctx)
    private val treatments = SyncTreatments(ctx, treatmentLog)
    private val settingsMirror = PublishSettingsMirror(ctx)
    private val _state = MutableStateFlow<OwnerShareState>(OwnerShareState.Loading)
    val state: StateFlow<OwnerShareState> = _state.asStateFlow()

    @Volatile private var owner: OwnerState? = null

    @Volatile private var jobs = emptyList<Job>()

    /** Whether sharing runs (started with an identity). */
    val running: Boolean get() = jobs.isNotEmpty()
    private var historyRepublishedAt = 0L

    private fun now() = clock.now().epochSecond

    /** Every entry point: one at a time, including across suspensions inside (SecureStore, Room). */
    private suspend fun <T> locked(block: suspend () -> T): T = withContext(serial) { lock.withLock { block() } }

    /** The identity key for NIP-42 AUTH (relays serve the wraps addressed to it only after AUTH). */
    fun identityKey(): app.mauri.sukrara.protocol.KeyPair? = owner?.identity(ctx.crypto)

    /** Loads the identity and, if there is one, starts sharing in [scope] (the foreground service's). */
    suspend fun start(scope: CoroutineScope) =
        locked {
            if (jobs.isNotEmpty()) return@locked
            when (val l = store.load()) {
                OwnerIdentityStore.Load.Missing -> _state.value = OwnerShareState.NoIdentity
                is OwnerIdentityStore.Load.Unreadable -> _state.value = OwnerShareState.IdentityUnreadable(l.transient)
                is OwnerIdentityStore.Load.Loaded -> run(scope, l.state)
            }
        }

    suspend fun stop() = locked { halt() }

    private fun halt() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        transport.stop()
        nearby.stop()
        runScope = null
    }

    @Volatile private var runScope: CoroutineScope? = null

    /** The identity's public key while running (the backup's `d`, the inbox's `#p`). */
    @Volatile private var identityPub: String? = null

    /** What the Bluetooth server asks the engine (from its own thread: reads the current state snapshot). */
    private val nearbyHost =
        object : OwnerNearby.Host {
            override fun sid(): String? = owner?.takeIf { it.conflict == null }?.sid

            override fun followers(): Collection<PublicKeyHex> =
                owner?.activeFollowers()?.mapNotNull { PublicKeyHex.parse(it.key) }.orEmpty()

            override fun onEvent(event: SignedEvent, peer: String) {
                runScope?.launch(serial) { locked { onNearby(event, peer) } }
            }
        }

    /**
     * An event a follower device wrote over Bluetooth, trusted only by its signatures like a relay event: gift wraps to
     * the identity go to the inbox (requests, rekeys, history), a writer's entry to the log (its echo is published and
     * the event forwarded to the other centrals, B§6).
     */
    private suspend fun onNearby(ev: SignedEvent, peer: String) {
        val s = owner ?: return
        when (ev.kind) {
            Wire.KIND_WRAP -> {
                // verified before it is marked seen: anyone in range could send a forged event with a real wrap's id
                val ours = ev.tag("p") == identityPub && s.conflict == null && ctx.crypto.verify(ev)
                if (ours && seen.markSeen(ev.id, now())) inbox(s, ev)
            }

            Wire.KIND_DATA -> {
                treatments.receive(s, ev, now(), NEARBY_VIA).echo?.let {
                    nearby.forward(ev, except = peer)
                    send(it)
                }
            }
        }
    }

    private suspend fun run(scope: CoroutineScope, s: OwnerState) {
        owner = s
        runScope = scope
        val identity = s.identity(ctx.crypto).public.hex
        identityPub = identity
        jobs =
            listOf(
                scope.launch(serial) { transport.inbound.collect { locked { onInbound(it) } } },
                scope.launch(serial) { readingsFrom(hub) },
                scope.launch(serial) { transport.status.collect { render() } },
                scope.launch(serial) {
                    while (true) {
                        delay(PublishReadings.CHUNK_EVERY * 1000)
                        locked {
                            owner?.let { publish(readings.tick(it, now())) }
                            refreshControl()
                            refreshShared()
                        }
                    }
                },
                scope.launch(serial) {
                    settings.settings().debounce(SETTINGS_DEBOUNCE_MS).collect { w ->
                        locked { owner?.let { apply(settingsMirror.onChange(it, w, now())) } }
                    }
                },
            )
        transport.subscribe(INBOX, listOf(RelayFilter(kinds = listOf(Wire.KIND_WRAP), p = listOf(identity))))
        transport.subscribe(OWN, listOf(RelayFilter(kinds = listOf(Wire.KIND_DATA), authors = listOf(identity), since = now() - Wire.DAY)))
        transport.start()
        s.pendingControl.mapNotNull { SignedEvent.parse(it.eventJson) }.forEach(::send)
        publish(readings.republish(s, now()))
        subscribeWriters(s)
        nearby.start(nearbyHost)
        republishShared()
        refreshControl()
        render()
    }

    private suspend fun readingsFrom(hub: GlucoseHub) {
        hub.state
            .filter { it.role == Role.Wearer }
            .map { it.recent }
            .distinctUntilChanged()
            .collect { recent -> locked { owner?.let { publish(readings.onReadings(it, recent, now())) } } }
    }

    private suspend fun onInbound(m: RelayInbound) {
        val s = owner ?: return
        when (m) {
            is RelayInbound.Ok -> {
                if (m.accepted && s.pendingControl.any { it.eventId == m.eventId }) {
                    commit(s.copy(pendingControl = s.pendingControl.filter { it.eventId != m.eventId }))
                }
            }

            is RelayInbound.Event -> {
                when (m.subscription) {
                    OWN -> conflicts.check(s, m.event, now())?.let { commit(it) }
                    INBOX -> if (s.conflict == null && seen.markSeen(m.event.id, now())) inbox(owner ?: s, m.event)
                    WRITERS -> treatments.receive(s, m.event, now(), m.relay).echo?.let(::send)
                }
            }

            else -> {
                Unit
            }
        }
    }

    private suspend fun inbox(s: OwnerState, wrap: SignedEvent) {
        val handled = HandleInbox(ctx)(s, wrap, now())
        apply(handled.step)
        when (val signal = handled.signal) {
            is InboxSignal.NewRequest -> {
                onRequest(signal.request)
            }

            is InboxSignal.HistoryWanted -> {
                if (now() - historyRepublishedAt >= HISTORY_MIN_GAP) {
                    historyRepublishedAt = now()
                    publish(readings.republish(handled.step.state, now(), signal.since))
                }
            }

            null -> {
                Unit
            }
        }
    }

    /** Roster and backup again when older than [CONTROL_REFRESH] (they expire after 30 / 90 days); never during a conflict. */
    private suspend fun refreshControl() {
        val s = owner ?: return
        if (s.conflict != null) return
        var step = Step(s)
        if (now() - s.rosterPublishedAt > CONTROL_REFRESH) step += { ControlEvents.roster(ctx, it, now()) }
        if (now() - s.backupPublishedAt > CONTROL_REFRESH) step += { ControlEvents.backup(ctx, it, now()) }
        if (step.publish.isNotEmpty()) apply(step)
    }

    /** The treatment log and the settings again under the current epoch (start, new epoch, weekly). */
    private suspend fun republishShared() {
        val s = owner ?: return
        val log = treatments.republish(s, now())
        val mirrored = settingsMirror.republish(log.state, now())
        apply(Step(mirrored.state, log.publish + mirrored.publish))
    }

    private suspend fun refreshShared() {
        val s = owner ?: return
        if (treatments.republishDue(s, now()) || settingsMirror.republishDue(s, now())) republishShared()
    }

    /** Writers' entries: one subscription for the current writers, closed when there are none. */
    private fun subscribeWriters(s: OwnerState) {
        treatments.writerFilter(s, now())?.let { transport.subscribe(WRITERS, listOf(it)) } ?: transport.close(WRITERS)
    }

    // ---------------------------------------------------------------------------------------------- actions

    /** A version of the treatment log stored on this phone (add, edit, delete, undo, pens) → followers at once. */
    suspend fun publishTreatment(record: TreatmentRecord) {
        locked { owner?.let { send(treatments.publish(it, record, now())) } }
    }

    /** First-run "share with followers": a new identity. Refuses while an identity exists or is unreadable. */
    suspend fun createIdentity(scope: CoroutineScope, profile: Profile?): Boolean =
        locked {
            if (owner != null || store.load() !is OwnerIdentityStore.Load.Missing) return@locked false
            val step = CreateIdentity(ctx)(profile, now())
            if (!store.save(step.state)) return@locked false
            run(scope, step.state)
            true
        }

    /**
     * Restore from a recovery code. The UI gates it (setup's `RestoreGate`: this phone's sensor runs). Replaces an unreadable
     * identity only on success, and a working one only with [replaceExisting] (the UI confirms first); nothing
     * changes on any error.
     */
    suspend fun restore(scope: CoroutineScope, code: String, password: String?, replaceExisting: Boolean = false): RestoreIdentity.Result =
        locked {
            if (owner != null && !replaceExisting) return@locked RestoreIdentity.Result.AlreadySharing
            val use = RestoreIdentity(ctx)
            val (identity, error) = use.parse(code, password)
            if (identity == null) return@locked error!!
            transport.start()
            val found = transport.fetch(use.identityEventsFilter(identity), FETCH_TIMEOUT_MS)
            val result = use.restore(identity, found.events, found.answered, now())
            if (result is RestoreIdentity.Result.Restored) {
                halt()
                // the code that restored it is somewhere besides this phone: no "Not backed up" card
                val restored = result.step.state.copy(restoredAt = now())
                store.save(restored, replaceUnreadable = true)
                // run() publishes the rotation from pendingControl (deliveries before the roster)
                run(scope, restored)
            }
            result
        }

    /** The identity the last recovery code was made for: [markRecoverySaved] counts only for that one. */
    @Volatile private var exportedFor: String? = null

    /** A recovery code (NIP-49, scrypt: 1–3 s, run off the engine's lock). Not a backup until [markRecoverySaved]. */
    suspend fun exportRecovery(password: String): String? {
        val s = owner ?: return null
        return withContext(dispatcher) { ExportRecovery(ctx)(s, password) }.also { exportedFor = s.identityHex }
    }

    /**
     * Hands the identity to the phone's password manager after the wearer tapped Save: [save] gets the entry's name
     * ("Sukrara · npub1abcd…wxyz", several identities stay apart) and the `nsec`, and returns whether the password
     * manager took it; then the "Not backed up" card goes away. The secret goes nowhere else and is never logged.
     * False without an identity or when [save] refused.
     */
    suspend fun saveToPasswordManager(save: suspend (id: String, secret: String) -> Boolean): Boolean {
        val s = owner ?: return false
        val identity = s.identity(ctx.crypto)
        val npub = ctx.crypto.npub(identity.public)
        if (!save("Sukrara · ${npub.take(NPUB_HEAD)}…${npub.takeLast(NPUB_TAIL)}", ctx.crypto.nsec(identity.secret))) return false
        // only for the identity that was saved (a restore may have replaced it meanwhile)
        locked { owner?.takeIf { it.identityHex == s.identityHex }?.let { commit(it.copy(passwordManagerSavedAt = now())) } }
        return true
    }

    /** The wearer kept the recovery code (copied it): the "Not backed up" card goes away. */
    suspend fun markRecoverySaved() =
        locked { owner?.takeIf { it.identityHex == exportedFor }?.let { commit(it.copy(recoveryExportedAt = now())) } }

    /** "How you appear": followers get it in a roster at once. False without an identity or during a conflict. */
    suspend fun setProfile(profile: Profile): Boolean =
        locked {
            val s = owner?.takeIf { it.conflict == null } ?: return@locked false
            SetProfile(ctx)(s, profile, now())?.let { apply(it) }
            true
        }

    /** A new single-use invite link, or null without an identity or during a conflict. */
    suspend fun createInvite(label: String): String? =
        locked {
            val s = owner?.takeIf { it.conflict == null } ?: return@locked null
            val created = CreateInvite(ctx)(s, label, now())
            // the invite wrap goes to the relays only (B§6: never over Bluetooth)
            apply(created.step, overBluetooth = false)
            created.link.link
        }

    /**
     * Allow: [ApproveResult.Allowed] only when [follower] is now an active follower, else why not, so the caller can
     * say it didn't work. When other phones also tried the invite (a leaked link) the wearer must type the code shown
     * on the follower's phone: [typedCode] must match.
     */
    suspend fun approve(follower: String, typedCode: String? = null): ApproveResult {
        if (owner?.conflict != null) return ApproveResult.Paused
        val request = pendingRequest(follower) ?: return ApproveResult.Gone
        if (request.others > 0 && typedCode != request.code) return ApproveResult.NeedsCode
        var refused = ApproveResult.Gone
        val s =
            locked {
                val st = owner ?: return@locked null
                // re-checked under the lock: a conflict, or another phone tried the link meanwhile
                val p = st.pending.firstOrNull { it.follower == follower }
                when {
                    st.conflict != null -> null.also { refused = ApproveResult.Paused }
                    p == null -> null
                    p.others > 0 && typedCode != p.code -> null.also { refused = ApproveResult.NeedsCode }
                    else -> ApproveRequest(ctx)(st, follower, now())?.also { apply(it) }?.state
                }
            } ?: return refused
        if (s.activeFollowers().none { it.key == follower }) return ApproveResult.Gone
        locked {
            publish(readings.republish(s, now()))
            owner?.let { apply(treatments.republish(it, now())) }
        }
        return ApproveResult.Allowed
    }

    /** The request [follower] has waiting, if any. */
    fun pendingRequest(follower: String): PendingRequest? = owner?.pending?.firstOrNull { it.follower == follower }

    /** "Can add entries" for an active follower (writer scope); refused during a conflict (it publishes a roster). */
    suspend fun setWriter(follower: String, allowed: Boolean) = act { SetWriter(ctx)(it, follower, allowed, now()) }

    suspend fun deny(follower: String) = act { DenyRequest(ctx)(it, follower, now()) }

    suspend fun remove(follower: String) =
        act { RemoveFollower(ctx)(it, follower, now()) }?.let { s ->
            locked { publish(readings.republish(s, now())) }
        }

    /**
     * "Forget this device" (Settings › Sharing › Nearby devices): the follower is removed (its trust ends, like Remove)
     * and its Bluetooth links drop. A device that connects again is no longer named or trusted.
     */
    suspend fun forget(follower: String) {
        remove(follower)
        nearby.forget(follower)
    }

    /** The Bluetooth server's state (Settings › Sharing). */
    val nearbyStatus: StateFlow<NearbyStatus> get() = nearby.status

    /** Connected follower devices named by their identity proof. */
    val nearbyLinks: StateFlow<List<NearbyServerCore.Named>> get() = nearby.links

    /** "I turned it off — retry". */
    suspend fun retryConflict() = locked { owner?.let { apply(conflicts.retry(it, now())) } }

    /** A fired or cleared alert, to every follower at once. Continues during a conflict (readings do too). */
    suspend fun publishAlert(alert: AlertPayload) = locked { owner?.let { send(readings.publishAlert(it, alert, now())) } }

    /** Runs a follower change; refused during a conflict (it would publish a backup / rotation). */
    private suspend fun act(use: (OwnerState) -> Step?): OwnerState? =
        locked {
            val s = owner?.takeIf { it.conflict == null } ?: return@locked null
            val step = use(s) ?: return@locked null
            apply(step)
            step.state
        }

    // ---------------------------------------------------------------------------------------------- plumbing

    /** Persist first, then publish: a crash in between re-sends the saved control events on the next start. */
    private suspend fun apply(step: Step, overBluetooth: Boolean = true) {
        val before = owner
        commit(step.state)
        step.publish.forEach { send(it, overBluetooth) }
        // a new epoch (rotation, conflict retry): the log and the settings again, encrypted to the new read key
        if (before != null && before.current.n != step.state.current.n) republishShared()
        if (before != null && before.activeWriters() != step.state.activeWriters()) subscribeWriters(step.state)
    }

    private suspend fun commit(s: OwnerState) {
        val changed = s != owner
        owner = s
        if (changed) store.save(s)
        render()
    }

    private fun publish(events: List<SignedEvent>) = events.forEach(::send)

    /** To the relays and, unless it's the owner backup (B§6) or [overBluetooth] is false, to nearby followers. */
    private fun send(ev: SignedEvent, overBluetooth: Boolean = true) {
        transport.publish(ev)
        val identity = identityPub
        val backup = ev.kind == Wire.KIND_DATA && (identity == null || ev.tag("d") == Wire.backupD(PublicKeyHex(identity)))
        if (overBluetooth && !backup) nearby.publish(ev)
    }

    private fun render() {
        val s = owner ?: return
        nearby.refresh()
        val relays = transport.status.value
        _state.value = s.conflict?.let {
            OwnerShareState.AnotherAppSharingAsYou(it.reason, IdentityConflictDetector.followersReceiving(it.reason), relays)
        } ?: OwnerShareState.Sharing(
            s.current.n,
            s.followers,
            s.pending,
            relays,
            s.backupPublishedAt,
            s.recoveryExportedAt,
            profile = ControlEvents.profile(s),
            writers = s.activeWriters().toSet(),
            passwordManagerSavedAt = s.passwordManagerSavedAt,
            restoredAt = s.restoredAt,
        )
    }

    private companion object {
        const val INBOX = "inbox"
        const val OWN = "own"
        const val WRITERS = "writers"
        const val NEARBY_VIA = "ble"
        const val SETTINGS_DEBOUNCE_MS = 3_000L
        const val CONTROL_REFRESH = 6 * Wire.DAY
        const val HISTORY_MIN_GAP = 600L
        const val FETCH_TIMEOUT_MS = 12_000L
        const val NPUB_HEAD = 12
        const val NPUB_TAIL = 4
    }
}
