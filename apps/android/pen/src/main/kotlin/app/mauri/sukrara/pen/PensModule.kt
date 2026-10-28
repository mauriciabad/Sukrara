// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.pen.engine.Ledger
import app.mauri.sukrara.pen.engine.PenEngine
import app.mauri.sukrara.pen.engine.PenRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * The pens area (`AppGraph` composes it with one line): [pens] (the runtime), the read card's notifications, the tap
 * component that is enabled only while pens can be read, and the pen timer.
 */
class PensModule(
    private val context: Context,
    store: StateStore<Ledger>,
    log: PenLog,
    private val settings: SettingsRepository,
    clock: Clock,
    private val roleSource: Flow<Role?>,
    val scope: CoroutineScope,
    ports: PenNotificationPorts,
) {
    private val hasNfc = context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)
    private val log = log

    /** Debug builds only (a made-up read on an emulator without NFC): show the pen surfaces anyway. */
    val fakeNfc = kotlinx.coroutines.flow.MutableStateFlow(false)

    private val role: StateFlow<Role?> = roleSource.stateIn(scope, SharingStarted.Eagerly, null)

    /** Pens exist only on the wearer's own phone with NFC (a follower would log into someone else's log). */
    fun available(): Boolean = role.value == Role.Wearer && (hasNfc || fakeNfc.value)

    /** [available] once the stored role is loaded (a cold start by a pen tap or the timer must not read "no role"). */
    suspend fun availableNow(): Boolean = roleSource.first() == Role.Wearer && (hasNfc || fakeNfc.value)

    val notices = PenNotices(context, ports)

    val pens =
        Pens(
            store = store,
            log = log,
            clock = clock,
            rules = { rules() },
            available = { availableNow() },
            reading = { reading() },
            setReading = { settings.set(Reading, it.value) },
            changed = { l -> changed(l) },
            diagnostic = { Log.i(TAG, it) },
        )

    /** The reading setting (Ask / On / Off). */
    val readingFlow: Flow<PenReading> = settings.flow(Reading).map { PenReading.of(it) }

    suspend fun reading(): PenReading = PenReading.of(settings.flow(Reading).first())

    suspend fun notifyAfterRead(): Boolean = settings.flow(Notify).first()

    suspend fun rules(): PenRules =
        PenRules(
            primeBelow = settings.flow(PrimeBelow).first(),
            cluster = (settings.flow(Cluster).first() * 60).toLong(),
            primeBefore = (settings.flow(PrimeBefore).first() * 60).toLong(),
            pending = (settings.flow(Pending).first() * 60).toLong(),
            match = (settings.flow(Match).first() * 60).toLong(),
            matchUnits = settings.flow(MatchUnits).first(),
            matchShare = settings.flow(MatchShare).first() / 100.0,
            offer = (settings.flow(Offer).first() * 3600).toLong(),
        )

    /** Loads the ledger; the notification and the timer follow it. Called from `AppGraph.ready`. */
    suspend fun ready() {
        pens.load()
    }

    /** Keeps the tap component in step with the role and the setting, and reposts what waits (app start). */
    fun start() {
        scope.launch {
            combine(roleSource, readingFlow, fakeNfc) { r, reading, fake ->
                r == Role.Wearer && (hasNfc || fake) &&
                    reading != PenReading.Off
            }.distinctUntilChanged()
                .collect { on -> runCatching { setComponents(on) } }
        }
        scope.launch {
            roleSource.distinctUntilChanged().collect { r ->
                if (r == Role.Wearer) {
                    ready()
                    syncNotices()
                    schedule(pens.ledger.value)
                } else {
                    // not the wearer: nothing about pens may stay on screen or run
                    notices.cancelRead()
                    notices.sync(Ledger(), emptyList())
                    runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK) }
                }
            }
        }
    }

    private fun setComponents(on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        for (c in listOf(PenTapActivity::class.java, PenReadActivity::class.java)) {
            context.packageManager.setComponentEnabledSetting(ComponentName(context, c), state, PackageManager.DONT_KILL_APP)
        }
    }

    private suspend fun changed(l: Ledger) {
        if (!availableNow()) return
        notices.sync(l, log.all().map(PenRecords::entry))
        schedule(l)
    }

    suspend fun syncNotices() {
        if (!availableNow()) return
        notices.sync(pens.ledger.value, log.all().map(PenRecords::entry))
    }

    /** The notification's *Undo*: the card's read, while nothing changed since. */
    suspend fun undoLast() {
        val undone =
            pens.read.value
                ?.undo
                ?.let { pens.undo(it) } == true
        if (undone) notices.cancelRead() else notices.undoFailed()
    }

    /** The timer: pending small doses become a question; then the next wake-up. */
    suspend fun tick() {
        ready()
        pens.refresh()
        if (availableNow()) schedule(pens.ledger.value)
    }

    /** NFC is off (the pens page offers Android's NFC settings). */
    fun nfcOff(): Boolean = hasNfc && NfcAdapter.getDefaultAdapter(context)?.isEnabled == false

    /** Debug builds only (`DebugFakes`): the emulator shows the taps-blocked state. */
    val fakeTagsBlocked = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * Android lets the user block an app's tag intents (Settings › Apps › Sukrara, or Android muting them until
     * allowed): then no pen tap reaches Sukrara. Checked again whenever a screen comes back.
     */
    fun tagsBlocked(): Boolean =
        fakeTagsBlocked.value ||
            (hasNfc && runCatching { NfcAdapter.getDefaultAdapter(context)?.isTagIntentAllowed == false }.getOrDefault(false))

    fun taps() =
        app.mauri.sukrara.core.model.PenTaps(
            blocked = tagsBlocked(),
            nfcOff = nfcOff(),
            notifyBlocked = runCatching { notices.readBlocked() }.getOrDefault(false),
        )

    /** Home's banner: reading On on the wearer's phone, and Android blocks the taps (re-read on every resume). */
    suspend fun tapsBlockedWhileOn(): Boolean = availableNow() && reading() == PenReading.On && tagsBlocked()

    /** Home banner's *Turn off*: pen reading off (Settings › Insulin pens turns it on again). */
    fun turnOff() {
        scope.launch { settings.set(Reading, PenReading.Off.value) }
    }

    /** Every pen entry of the log as the engine sees it (tombstones included). */
    suspend fun penEntries() = log.all().map(PenRecords::entry)

    /** Settings › Insulin pens over this module. */
    val port: app.mauri.sukrara.core.model.PensPort by lazy { PensPortImpl(context, this) }

    /**
     * The treatment dialog's *Already logged by your pen?*: a live pen entry of either insulin within the match window
     * of the typed time, read from the (synced) log on any role, so a follower typing into the wearer's log is warned
     * too. The typed kind first; a pen set to the other kind still counts (a long pen left on the default *rapid*).
     */
    val duplicates =
        app.mauri.sukrara.core.model.PenDuplicates { kind, at ->
            val entries = penEntries()
            val window = (settings.flow(Match).first() * 60).toLong()
            val e =
                PenEngine.near(entries, kind, at, window = window)
                    ?: PenEngine.near(entries, if (kind == "long") "rapid" else "long", at, window = window)
            e?.let {
                app.mauri.sukrara.core.model
                    .PenNearEntry(it.id, it.a, it.t)
            }
        }

    private suspend fun schedule(l: Ledger) {
        val wake = PenEngine.nextWake(l, rules()) ?: return
        val nowS = System.currentTimeMillis() / 1000
        val req =
            OneTimeWorkRequestBuilder<PenWork>()
                .setInitialDelay(maxOf(0, wake - nowS + 5), TimeUnit.SECONDS)
                .build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req) }
    }

    companion object {
        private const val TAG = "SukraraPen"
        private const val WORK = "pen"
        val Reading = SchemaSettings.text("pens.reading", PenReading.Ask.value, PenReading.entries.map { it.value }.toSet())
        val Notify = SchemaSettings.bool("pens.notify", default = false)
        val PrimeBelow = SchemaSettings.number("pens.primeBelow")
        val Cluster = SchemaSettings.number("pens.cluster")
        val PrimeBefore = SchemaSettings.number("pens.primeBefore")
        val Pending = SchemaSettings.number("pens.pending")
        val Match = SchemaSettings.number("pens.match")
        val MatchUnits = SchemaSettings.number("pens.matchUnits")
        val MatchShare = SchemaSettings.number("pens.matchShare")
        val Offer = SchemaSettings.number("pens.offer")

        /** The detection numbers, in the order of Settings › Insulin pens › Automatic detection. */
        val Detection = listOf(PrimeBelow, PrimeBefore, Cluster, Pending, Offer, Match, MatchUnits, MatchShare)
    }
}

/** The `Application` gives the pen components (read card, notification buttons, timer) their module. */
interface PensHost {
    val pens: PensModule
}

/** The pen timer: pending small doses become a question when their wait ends. */
class PenWork(
    ctx: Context,
    p: WorkerParameters,
) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        (applicationContext as PensHost).pens.tick()
        return Result.success()
    }
}
