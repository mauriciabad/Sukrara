// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner

import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.GlucoseHub
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.AlertPayload
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.share.owner.usecase.ApproveRequest
import app.mauri.sukrara.share.owner.usecase.ControlEvents
import app.mauri.sukrara.share.owner.usecase.CreateIdentity
import app.mauri.sukrara.share.owner.usecase.CreateInvite
import app.mauri.sukrara.share.owner.usecase.DenyRequest
import app.mauri.sukrara.share.owner.usecase.ExportRecovery
import app.mauri.sukrara.share.owner.usecase.HandleInbox
import app.mauri.sukrara.share.owner.usecase.IdentityConflictDetector
import app.mauri.sukrara.share.owner.usecase.InboxSignal
import app.mauri.sukrara.share.owner.usecase.OwnerContext
import app.mauri.sukrara.share.owner.usecase.PublishReadings
import app.mauri.sukrara.share.owner.usecase.RemoveFollower
import app.mauri.sukrara.share.owner.usecase.RestoreIdentity
import app.mauri.sukrara.share.owner.usecase.Step
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The wearer's sharing coordinator: loads the identity, keeps the relay subscriptions, feeds readings from the
 * [GlucoseHub] to [PublishReadings], runs every use-case one at a time (serial dispatcher + one lock held across the
 * suspending store / ledger calls, so no update overwrites another), persists before publishing, and
 * exposes [state]. Relay failures stay in the per-relay status. Logs nothing that holds keys or glucose values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnerEngine(
    private val ctx: OwnerContext,
    private val store: OwnerIdentityStore,
    private val transport: RelayTransport,
    private val hub: GlucoseHub,
    private val seen: SeenWraps,
    private val clock: Clock,
    dispatcher: CoroutineDispatcher,
    private val nearby: OwnerNearby = OwnerNearby.None,
    /** Called for a new access request (the notification with Allow / Deny lives in the app layer). */
    private val onRequest: (PendingRequest) -> Unit = {},
) {
    private val serial = dispatcher.limitedParallelism(1)
    private val lock = Mutex()
    private val readings = PublishReadings(ctx)
    private val conflicts = IdentityConflictDetector(ctx)
    private val _state = MutableStateFlow<OwnerShareState>(OwnerShareState.Loading)
    val state: StateFlow<OwnerShareState> = _state.asStateFlow()

    @Volatile private var owner: OwnerState? = null
    private var jobs = emptyList<Job>()
    private var historyRepublishedAt = 0L

    private fun now() = clock.now().epochSecond

    /** Every entry point: one at a time, including across suspensions inside (SecureStore, Room). */
    private suspend fun <T> locked(block: suspend () -> T): T = withContext(serial) { lock.withLock { block() } }

    /** The identity key for NIP-42 AUTH (relays serve the wraps addressed to it only after AUTH). */
    fun identityKey(): app.mauri.sukrara.protocol.KeyPair? = owner?.identity(ctx.crypto)

    /** Loads the identity and, if there is one, starts sharing in [scope] (the foreground service's). */
    suspend fun start(scope: CoroutineScope) = locked {
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
    }

    private suspend fun run(scope: CoroutineScope, s: OwnerState) {
        owner = s
        val identity = s.identity(ctx.crypto).public.hex
        jobs = listOf(
            scope.launch(serial) { transport.inbound.collect { locked { onInbound(it) } } },
            scope.launch(serial) { readingsFrom(hub) },
            scope.launch(serial) { transport.status.collect { render() } },
            scope.launch(serial) {
                while (true) {
                    delay(PublishReadings.CHUNK_EVERY * 1000)
                    locked {
                        owner?.let { publish(readings.tick(it, now())) }
                        refreshControl()
                    }
                }
            },
        )
        transport.subscribe(INBOX, listOf(RelayFilter(kinds = listOf(Wire.KIND_WRAP), p = listOf(identity))))
        transport.subscribe(OWN, listOf(RelayFilter(kinds = listOf(Wire.KIND_DATA), authors = listOf(identity), since = now() - Wire.DAY)))
        transport.start()
        s.pendingControl.mapNotNull { SignedEvent.parse(it.eventJson) }.forEach(::send)
        publish(readings.republish(s, now()))
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
            is RelayInbound.Ok -> if (m.accepted && s.pendingControl.any { it.eventId == m.eventId }) {
                commit(s.copy(pendingControl = s.pendingControl.filter { it.eventId != m.eventId }))
            }
            is RelayInbound.Event -> when (m.subscription) {
                OWN -> conflicts.check(s, m.event, now())?.let { commit(it) }
                INBOX -> if (s.conflict == null && seen.markSeen(m.event.id, now())) inbox(owner ?: s, m.event)
            }
            else -> Unit
        }
    }

    private suspend fun inbox(s: OwnerState, wrap: SignedEvent) {
        val handled = HandleInbox(ctx)(s, wrap, now())
        apply(handled.step)
        when (val signal = handled.signal) {
            is InboxSignal.NewRequest -> onRequest(signal.request)
            is InboxSignal.HistoryWanted -> if (now() - historyRepublishedAt >= HISTORY_MIN_GAP) {
                historyRepublishedAt = now()
                publish(readings.republish(handled.step.state, now(), signal.since))
            }
            null -> Unit
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

    // ---------------------------------------------------------------------------------------------- actions

    /** First-run "share with followers": a new identity. Refuses while an identity exists or is unreadable. */
    suspend fun createIdentity(scope: CoroutineScope, profile: Profile?): Boolean = locked {
        if (owner != null || store.load() !is OwnerIdentityStore.Load.Missing) return@locked false
        val step = CreateIdentity(ctx)(profile, now())
        if (!store.save(step.state)) return@locked false
        run(scope, step.state)
        true
    }

    /**
     * Restore from a recovery code. The UI gates it with [RestoreIdentity.restoreAllowed]. Replaces an unreadable
     * identity only on success, and a working one only with [replaceExisting] (the UI confirms first); nothing
     * changes on any error.
     */
    suspend fun restore(scope: CoroutineScope, code: String, password: String?, replaceExisting: Boolean = false): RestoreIdentity.Result = locked {
        if (owner != null && !replaceExisting) return@locked RestoreIdentity.Result.AlreadySharing
        val use = RestoreIdentity(ctx)
        val (identity, error) = use.parse(code, password)
        if (identity == null) return@locked error!!
        transport.start()
        val found = transport.fetch(use.identityEventsFilter(identity), FETCH_TIMEOUT_MS)
        val result = use.restore(identity, found.events, found.answered, now())
        if (result is RestoreIdentity.Result.Restored) {
            halt()
            store.save(result.step.state, replaceUnreadable = true)
            // run() publishes the rotation from pendingControl (deliveries before the roster)
            run(scope, result.step.state)
        }
        result
    }

    suspend fun exportRecovery(password: String): String? = locked {
        val s = owner ?: return@locked null
        ExportRecovery(ctx)(s, password).also { commit(s.copy(recoveryExportedAt = now())) }
    }

    /** A new single-use invite link, or null without an identity or during a conflict. */
    suspend fun createInvite(label: String): String? = locked {
        val s = owner?.takeIf { it.conflict == null } ?: return@locked null
        val created = CreateInvite(ctx)(s, label, now())
        apply(created.step)
        created.link.link
    }

    suspend fun approve(follower: String) = act { ApproveRequest(ctx)(it, follower, now()) }?.let { s ->
        locked { publish(readings.republish(s, now())) }
    }

    suspend fun deny(follower: String) = act { DenyRequest(ctx)(it, follower, now()) }

    suspend fun remove(follower: String) = act { RemoveFollower(ctx)(it, follower, now()) }?.let { s ->
        locked { publish(readings.republish(s, now())) }
    }

    /** "I turned it off — retry". */
    suspend fun retryConflict() = locked { owner?.let { apply(conflicts.retry(it, now())) } }

    /** A fired or cleared alert, to every follower at once. Continues during a conflict (readings do too). */
    suspend fun publishAlert(alert: AlertPayload) = locked { owner?.let { send(readings.publishAlert(it, alert, now())) } }

    /** Runs a follower change; refused during a conflict (it would publish a backup / rotation). */
    private suspend fun act(use: (OwnerState) -> Step?): OwnerState? = locked {
        val s = owner?.takeIf { it.conflict == null } ?: return@locked null
        val step = use(s) ?: return@locked null
        apply(step)
        step.state
    }

    // ---------------------------------------------------------------------------------------------- plumbing

    /** Persist first, then publish: a crash in between re-sends the saved control events on the next start. */
    private suspend fun apply(step: Step) {
        commit(step.state)
        step.publish.forEach(::send)
    }

    private suspend fun commit(s: OwnerState) {
        val changed = s != owner
        owner = s
        if (changed) store.save(s)
        render()
    }

    private fun publish(events: List<SignedEvent>) = events.forEach(::send)

    private fun send(ev: SignedEvent) {
        transport.publish(ev)
        nearby.publish(ev)
    }

    private fun render() {
        val s = owner ?: return
        val relays = transport.status.value
        _state.value = s.conflict?.let {
            OwnerShareState.AnotherAppSharingAsYou(it.reason, IdentityConflictDetector.followersReceiving(it.reason), relays)
        } ?: OwnerShareState.Sharing(s.current.n, s.followers, s.pending, relays, s.backupPublishedAt, s.recoveryExportedAt)
    }

    private companion object {
        const val INBOX = "inbox"
        const val OWN = "own"
        const val CONTROL_REFRESH = 6 * Wire.DAY
        const val HISTORY_MIN_GAP = 600L
        const val FETCH_TIMEOUT_MS = 12_000L
    }
}
