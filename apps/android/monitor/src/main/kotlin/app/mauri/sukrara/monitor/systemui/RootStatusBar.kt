// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.systemui

import android.annotation.SuppressLint
import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.util.Log
import app.mauri.sukrara.core.data.settings.HookPosition
import app.mauri.sukrara.core.data.settings.SurfaceSettings
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.graph.CardSurface
import app.mauri.sukrara.graph.Freshness
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.GlucoseLook
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.graph.stats.Bands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.Writer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The root status bar's settings ([SurfaceSettings]). */
data class RootPrefs(
    val on: Boolean,
    val position: String,
    val color: Boolean,
    val hideIcon: Boolean,
)

/**
 * The value in the status bar with root, two ways (Settings › Status bar, "With root"):
 *  - **system icons** (a `slot:` placement): number + trend beside Wi-Fi and the battery, set by [SlotDaemon] running
 *    as root; System UI draws them itself (tinted). Runs only while the setting is on and root is granted.
 *  - **the System UI module** ([SystemUiHook]): the value as text beside the clock / the icons, range colour, our
 *    icons' order and colour, hiding Sukrara's notification icon. Fed by [HookState] broadcasts only this app can send.
 * Follows the hub (wearer or follower), the settings, the ranges and the freshness steps. Every change is conflated:
 * only the newest state is ever sent, on one background thread with timeouts, so nothing queues behind a stuck `su`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootStatusBar(
    private val context: Context,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val access: SystemAccess,
    private val hub: Flow<GlucoseState>,
    private val settings: SettingsRepository,
    private val graphConfig: Flow<GraphConfig>,
    private val freshnessTicks: Flow<Unit>,
    /** Posts the glucose notification again (the module filters its icon when System UI rebuilds the icon list). */
    private val repostNotification: () -> Unit,
) {
    private val pkg = context.packageName
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val pokes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Whether the last broadcast said "on" (one "off" goes out when it turns off, then nothing). */
    private var sentOn = false
    private var asked = false

    @Volatile private var moduleOrders = false

    private var daemon: Daemon? = null

    /** What the daemon reported last, for Settings' footer (debug info, not translated). */
    @Volatile var daemonState: String = "off"
        private set

    val prefs: Flow<RootPrefs> =
        combine(
            settings.flow(SurfaceSettings.RootValue),
            settings.flow(SurfaceSettings.RootPosition),
            settings.flow(SurfaceSettings.RootColor),
            settings.flow(SurfaceSettings.HideNotificationIcon),
            ::RootPrefs,
        )

    fun start() {
        val ticks = merge(freshnessTicks, pokes, everyMinute()).onStart { emit(Unit) }
        scope.launch(io) {
            combine(
                hub,
                prefs,
                graphConfig.map { it.bands },
                access.state,
                ticks,
            ) { s, p, bands, a, _ -> Input(s, p, bands, a.root == true) }
                .conflate()
                .catch { Log.w(TAG, "inputs failed: ${it.javaClass.simpleName}") }
                .collect { apply(it) }
        }
        // only someone who turned the value on is asked for root (su shows KernelSU's / Magisk's prompt)
        scope.launch { if (settings.flow(SurfaceSettings.RootValue).first()) runCatching { access.checkRoot() } }
    }

    /** Settings changed outside the flows (the hello): send again now. */
    fun poke() {
        pokes.tryEmit(Unit)
    }

    /** System UI (re)started with the module and asks for the state; [iconOrder] = it orders our icons itself. */
    fun onHello(
        code: String?,
        iconOrder: Boolean,
    ) {
        access.onHello(code)
        sentOn = false
        if (iconOrder) {
            moduleOrders = true
            scope.launch(io) { daemon?.command(DaemonProtocol.ORDER_MODULE) }
        }
        poke()
        scope.launch {
            delay(1_500) // after the state has landed: System UI rebuilds the icon list on a notification change
            repostNotification()
        }
    }

    private data class Input(
        val state: GlucoseState,
        val prefs: RootPrefs,
        val bands: Bands,
        val root: Boolean,
    )

    private fun apply(input: Input) {
        val now = clock.now().epochSecond
        val hook = hookState(input.state, input.prefs, input.bands)
        if (hook.enabled || sentOn) send(hook, ask = !asked)
        if (hook.enabled) asked = true
        sentOn = hook.enabled
        val wantIcons = input.prefs.on && input.root && HookPosition.isSystemIcons(input.prefs.position)
        if (!wantIcons) {
            stopDaemon()
            return
        }
        val l = look(input.state, now)
        val since = input.state.latest?.let { Freshness.timeText(it.t.epochSecond, now) }
        show(SlotIcons.plan(l, since))
    }

    private fun send(
        s: HookState,
        ask: Boolean,
    ) {
        val i =
            Intent(SystemUi.actionState(pkg))
                .setPackage(SystemUi.PACKAGE)
                .putExtras(s.toBundle())
                .putExtra(SystemUi.EXTRA_ASK, ask)
        // the module checks who sent it (getSentFromPackage): no other app can feed System UI a value
        val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
        // without the module loaded nobody listens: a no-op
        runCatching { context.sendBroadcast(i, null, options) }.onFailure { Log.w(TAG, "state: ${it.javaClass.simpleName}") }
    }

    private fun show(plan: SlotIcons.Plan) {
        val d = daemon?.takeIf { it.alive } ?: startDaemon() ?: return
        for (cmd in SlotIcons.commands(plan, ::resId)) {
            val reply = d.command(cmd)
            daemonState = "on ($reply)"
            if (reply != "ok") {
                if (reply == null) stopDaemon()
                return
            }
        }
    }

    @SuppressLint("DiscouragedApi") // one drawable per value, chosen by name
    private fun resId(name: String) = context.resources.getIdentifier(name, "drawable", pkg)

    private fun startDaemon(): Daemon? {
        val cmd = "CLASSPATH=${context.applicationInfo.sourceDir} exec app_process /system/bin ${SlotDaemon::class.java.name} $pkg"
        val d =
            runCatching { Daemon(ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()) }.getOrElse {
                daemonState = "no root: ${it.javaClass.simpleName}"
                return null
            }
        // the first start may wait on the root prompt
        val hello = d.read(SU_WAIT_S)
        if (hello != "ready") {
            d.stop()
            daemonState = "daemon failed: $hello"
            return null
        }
        daemon = d
        daemonState = "on"
        if (moduleOrders) d.command(DaemonProtocol.ORDER_MODULE)
        return d
    }

    private fun stopDaemon() {
        daemon?.stop() // the daemon removes its icons on EOF
        daemon = null
        daemonState = "off"
    }

    private fun everyMinute(): Flow<Unit> =
        flow {
            while (true) {
                delay(60_000)
                emit(Unit) // also the daemon's keep-alive (its watchdog removes the icons after 10 min of silence)
            }
        }

    /** One running daemon: request → reply with a timeout; a reader thread fills [lines]. */
    private class Daemon(
        private val p: Process,
    ) {
        private val stdin: Writer = p.outputStream.writer()
        private val stdout: BufferedReader = p.inputStream.bufferedReader()
        private val lines = LinkedBlockingQueue<String>()

        init {
            Thread {
                runCatching {
                    while (true) lines.put(stdout.readLine() ?: break)
                }
            }.apply { isDaemon = true }.start()
        }

        val alive: Boolean get() = p.isAlive

        fun read(waitS: Long): String? = lines.poll(waitS, TimeUnit.SECONDS)

        /** The reply, or null when the daemon is gone or didn't answer in time (then it's stopped). */
        fun command(cmd: String): String? =
            runCatching {
                stdin.write(cmd + "\n")
                stdin.flush()
                read(REPLY_WAIT_S)
            }.getOrNull().also { if (it == null) stop() }

        fun stop() {
            runCatching { stdin.close() }
            if (!runCatching { p.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) p.destroyForcibly()
        }
    }

    companion object {
        private const val TAG = "Sukrara.statusbar"
        private const val SU_WAIT_S = 30L
        private const val REPLY_WAIT_S = 5L

        /** The card's decision for the system icons. */
        fun look(
            state: GlucoseState,
            nowS: Long,
        ): GlucoseLook {
            val h = hookState(state, RootPrefs(true, HookPosition.DEFAULT, true, true), Bands())
            return GlucoseCard.face(h.latest(), nowS, HookText.sensorLook(h, nowS), CardSurface.STATUS_ICONS)
        }

        /** The state the module gets for the hub state [s] with the settings [p] and ranges [bands]. */
        fun hookState(
            s: GlucoseState,
            p: RootPrefs,
            bands: Bands,
        ): HookState {
            val latest = s.latest
            val sensor = s.sensor
            return HookState(
                enabled = p.on,
                position = p.position,
                color = p.color,
                hideIcon = p.hideIcon,
                bands = bands,
                mgdl = latest?.mgdl ?: 0,
                atS = latest?.t?.epochSecond ?: 0,
                rate = latest?.rateMgdlPerMin?.toDouble(),
                sensorEnded = sensor == SensorState.Ended,
                readyAtS = (sensor as? SensorState.WarmingUp)?.readyAt?.epochSecond ?: 0,
            )
        }
    }
}
