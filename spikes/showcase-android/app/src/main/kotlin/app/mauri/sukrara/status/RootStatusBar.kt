// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.status

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.mauri.sukrara.core.Range
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.Trend
import app.mauri.sukrara.features.FeatureState
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.graph.Freshness
import app.mauri.sukrara.graph.GlucoseLook
import app.mauri.sukrara.graph.TrendMark
import java.io.BufferedReader
import java.io.Writer
import java.util.concurrent.Executors

/**
 * The two status-bar surfaces that need root (investigation 05, ported from spikes/statusbar-android):
 *  - **system icons** (`systemIcons`): number + trend arrow next to Wi-Fi/battery, set through
 *    `IStatusBarService.setIcon` by [SlotDaemon] running as root. SystemUI draws them itself (tinted, no colour).
 *  - **SystemUI hook** (`hook`): the LSPosed module ([app.mauri.sukrara.hook.SystemUiHook]) draws a coloured value
 *    next to the clock / the icons; fed by a broadcast only this app may send ([PERMISSION]).
 * Both follow every reading via [update] (called from WearerExtras.render) and their Features switches.
 */
object RootStatusBar {
    private const val TAG = "Sukrara.statusbar"
    const val PKG = "app.mauri.sukrara.poc"
    const val ACTION = "$PKG.STATUS_BAR_GLUCOSE"
    const val ACTION_HELLO = "$PKG.HOOK_HELLO"
    const val PERMISSION = "$PKG.permission.STATUS_BAR_GLUCOSE"
    const val SLOT_NUMBER = "glucose"
    const val SLOT_TREND = "glucose_trend"

    /**
     * How our system icons' TalkBack text starts ([iconPlan]): the module finds our icons in SystemUI's row by it
     * (SystemUiHook.hookIconFit keeps them together and first in line; "Glucose …" also gets the range colour).
     */
    private val OUR_DESCRIPTIONS = listOf("Glucose ", "No glucose readings since ", "Sensor ended")   // i18n-ok: SystemUI icon descriptions
    fun isOurDescription(d: String) = OUR_DESCRIPTIONS.any(d::startsWith)

    /** The trend slot's text beside a number ("Glucose trend ↗"): tells the module which of the pair it is (hookIconFit's gap). */
    const val TREND_DESCRIPTION = "Glucose trend "   // i18n-ok: SystemUI icon description
    fun isTrendDescription(d: String) = d.startsWith(TREND_DESCRIPTION)

    /** One thread: the daemon's stdin/stdout protocol is strictly request → reply. */
    private val io = Executors.newSingleThreadExecutor()
    @Volatile var state: String = "off"; private set

    /** Whether `su` works for Sukrara (KernelSU grant). null = not checked yet. Checked off the main thread. */
    val root = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)

    fun checkRoot() = io.execute {
        root.value = runCatching {
            val p = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.inputStream.bufferedReader().readText().trim() == "0"
        }.getOrDefault(false)
    }
    @Volatile private var last: Reading? = null

    private var proc: Process? = null
    private var stdin: Writer? = null
    private var stdout: BufferedReader? = null

    /** Every reading / switch change. Cheap when both switches are off. */
    fun update(ctx: Context, f: FeatureState, r: Reading?) {
        last = r
        sendToHook(ctx, f, r)
        val app = ctx.applicationContext
        io.execute {
            runCatching {
                if (!f.systemIcons) stopDaemon() else show(app, iconPlan(look(r, System.currentTimeMillis() / 1000), r))
            }.onFailure { Log.w(TAG, "system icons", it) }
        }
    }

    fun stop(ctx: Context) {
        sendToHook(ctx, Features.value.copy(hook = false), null)
        io.execute { stopDaemon() }
    }

    /** The switches changed outside the wearer pipeline (Settings, any role): tell the module now. */
    fun pushState(ctx: Context) = sendToHook(ctx, Features.value, last)

    // --- 3b: the SystemUI hook --------------------------------------------------------------------------

    private fun sendToHook(ctx: Context, f: FeatureState, r: Reading?) {
        val i = Intent(ACTION).setPackage("com.android.systemui")
            .putExtra("enabled", f.hook)
            .putExtra("position", f.hookPosition)
            .putExtra("color", f.hookColor)
            .putExtra("hideIcon", f.hideNotifIcon)
            .putExtra("low", Range.LOW).putExtra("high", Range.HIGH)
            .putExtra("lockWidget", f.lockWidget)
            .putExtra("lockBg", f.lockBg)
            .putExtra("lockLook", f.lockLook)
            .putExtra("sensorEnd", app.mauri.sukrara.core.SensorLife.current.value?.endEpoch ?: 0L)   // the flag once it has ended
            // the warm-up's time left, as the card shows it (GlucoseCard.face in the hook)
            .putExtra("sensorStart", app.mauri.sukrara.core.SensorLife.current.value?.startEpoch ?: 0L)
            .putExtra("sensorWarmup", app.mauri.sukrara.core.SensorLife.current.value?.warmupEndEpoch ?: 0L)
        if (r != null) {
            i.putExtra("mgdl", r.sgv).putExtra("epochSec", r.t)
            r.rate?.let { i.putExtra("rate", it) }
        }
        // Without the module loaded nobody listens: a no-op.
        ctx.sendBroadcast(i)
    }

    /** SystemUI (re)started with the hook loaded and asks for the current value. */
    class HelloReceiver : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Features.init(c)
            if (i.hasExtra("lockWidgetId")) {
                app.mauri.sukrara.widgets.LockscreenWidget.remember(c, listOf(i.getIntExtra("lockWidgetId", 0)))
                app.mauri.sukrara.widgets.SukraraHooks.refresh(c, "lock screen widget", force = true)
            }
            sendToHook(c, Features.value, last)
            if (i.getBooleanExtra("iconOrder", false)) moduleHello()
            if (Features.value.hook && Features.value.hideNotifIcon) repostLater(c)
        }
    }

    /**
     * The module filters our notification icon when SystemUI rebuilds its status-bar icon list, which happens on a
     * notification change. After the module learns about `hideIcon` (SystemUI start, the switch) re-post the current
     * notification once, a moment later so the broadcast has landed first.
     */
    fun repostLater(ctx: Context) {
        val app = ctx.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { GlucoseNotification.repost(app) }.onFailure { Log.w(TAG, "re-post", it) }
        }, 1_500)
    }

    // --- 3a: system icons through the root daemon ---------------------------------------------------------

    /** The status bar's look for [r] at [nowS]: the card's decision ([GlucoseCard.face]) with the sensor's state. */
    fun look(r: Reading?, nowS: Long): GlucoseLook = app.mauri.sukrara.graph.GlucoseCard.face(r, nowS,
        app.mauri.sukrara.graph.SensorBadge.look(app.mauri.sukrara.core.SensorLife.current.value, nowS, r?.t),
        app.mauri.sukrara.graph.CardSurface.STATUS_ICONS)

    /**
     * What the two slots show: drawable names (null = remove the slot) and what TalkBack says ([desc] on the number, or
     * on the single icon in the trend slot; [trendDesc] on the trend beside a number).
     */
    data class IconPlan(val trend: String?, val number: String?, val desc: String, val trendDesc: String = desc)

    /**
     * The system icons for a [look] ([GlucoseCard.face], the user 2026-10-21): the number and the trend while fresh or
     * late; from 5 min the number struck through at half strength (`sb_s…`, so it reads grey in SystemUI's tint) beside
     * the "no trend" ring (`sb_t_none_s`); from 20 min only the "no readings" icon (sensors_off) in the trend slot; an
     * ended sensor the checkered flag (round sensor-life). SystemUI draws them itself (tinted, no colour).
     */
    fun iconPlan(look: GlucoseLook, r: Reading?): IconPlan = when (look.state) {
        GlucoseLook.State.ENDED -> IconPlan("ic_stat_sensor_ended", null, OUR_DESCRIPTIONS[2])
        GlucoseLook.State.NO_READINGS -> IconPlan("ic_stat_no_readings", null,
            "${OUR_DESCRIPTIONS[1]}${r?.let { Freshness.timeText(it.t, System.currentTimeMillis() / 1000) } ?: "?"}")   // i18n-ok: SystemUI icon description, English like "Glucose …"
        GlucoseLook.State.NONE -> IconPlan(null, null, "")
        // a warm-up has no number to show in the number icons (one drawable per value): no slots until the first reading
        GlucoseLook.State.WARMING_UP -> IconPlan(null, null, "Sensor warming up")   // i18n-ok: SystemUI icon description
        GlucoseLook.State.VALUE -> {
            // One resource per value: the Compose status bar ignores the icon level (see tools/gen-slot-drawables.swift).
            val v = when (look.text) { "LO" -> "lo"; "HI" -> "hi"; else -> "n${look.text}" }   // card-ok: the look's text → its drawable name
            val number = if (look.strike) "sb_s${v.removePrefix("n")}" else "sb_$v"
            val trend = when {
                look.trend == TrendMark.ARROW && r?.rate != null -> Trend.drawableName(r.rate)
                look.strike -> "sb_t_none_s"
                else -> "sb_t_none"
            }
            val mark = if (look.strike) "(old)" else Trend.arrow(r?.rate)   // i18n-ok: SystemUI icon description
            IconPlan(trend, number, "${OUR_DESCRIPTIONS[0]}${look.text} mg/dL $mark", "$TREND_DESCRIPTION$mark")
        }
    }

    private fun show(ctx: Context, plan: IconPlan) {
        // External icons are placed first (leftmost), newest first: add the trend before the number.
        if (plan.trend == null) send(ctx, "remove $SLOT_TREND")
        else if (!send(ctx, "set $SLOT_TREND ${res(ctx, plan.trend)} 0 ${if (plan.number == null) plan.desc else plan.trendDesc}")) return
        if (plan.number == null) send(ctx, "remove $SLOT_NUMBER")
        else send(ctx, "set $SLOT_NUMBER ${res(ctx, plan.number)} 0 ${plan.desc}")
    }

    @SuppressLint("DiscouragedApi")
    private fun res(ctx: Context, name: String) = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)

    /**
     * The module came up in this System UI with its icon order hook (its hello): it shows number, then trend, from the
     * first frame, so the slot daemon must not re-add them after the restart (that remove / add was the visible jump,
     * the user 2026-10-22). The daemon keys this by System UI's pid, so a later restart without the module re-adds again.
     */
    private fun moduleHello() = io.execute {
        moduleOrders = true
        if (proc?.isAlive == true) runCatching { command(ORDER_MODULE) }.onFailure { Log.w(TAG, "order", it) }
    }

    /** The module said hello since this app process started: also told to a daemon started later. */
    @Volatile private var moduleOrders = false
    private const val ORDER_MODULE = "order module"

    private fun send(ctx: Context, cmd: String): Boolean {
        if (proc?.isAlive != true && !startDaemon(ctx)) return false
        return try {
            val reply = command(cmd)
            state = "on ($reply)"
            reply == "ok"
        } catch (e: Exception) {
            stopDaemon()
            state = "daemon died: ${e.message}"
            false
        }
    }

    /** One request → its reply, with the running daemon (on [io]). */
    private fun command(cmd: String): String {
        stdin!!.apply { write(cmd + "\n"); flush() }
        return stdout!!.readLine() ?: "eof"
    }

    private fun startDaemon(ctx: Context): Boolean = try {
        val cmd = "CLASSPATH=${ctx.applicationInfo.sourceDir} exec app_process /system/bin ${SlotDaemon::class.java.name} ${ctx.packageName}"
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        proc = p
        stdin = p.outputStream.writer()
        stdout = p.inputStream.bufferedReader()
        val hello = stdout!!.readLine()
        if (hello?.startsWith("ready") == true) {
            state = "on"
            if (moduleOrders) command(ORDER_MODULE)
            true
        } else { stopDaemon(); state = "daemon failed: $hello"; false }
    } catch (e: Exception) {
        state = "no root: ${e.message}" // no su, or root not granted to Sukrara in KernelSU
        false
    }

    private fun stopDaemon() {
        if (proc == null) return
        runCatching { stdin?.close() } // the daemon removes its icons on EOF
        proc?.destroy()
        proc = null; stdin = null; stdout = null
        state = "off"
    }

    /**
     * Root: restart SystemUI to (re)load the hook: a kill, not `am crash` (crashes count towards rescue mode); System UI
     * is persistent, so Android starts it again at once (the status bar blinks ~2 s). No reboot: LSPosed loads a
     * module when the process starts. The `su` call is also the root request: KernelSU / Magisk ask (or refuse) here,
     * so it waits up to [SU_WAIT_S]. Returns null when it worked, else why not (shown on the card).
     */
    fun restartSystemUi(): String? {
        val r = runCatching {
            val p = ProcessBuilder("su", "-c", "kill \$(pidof com.android.systemui)").redirectErrorStream(true).start()
            val done = p.waitFor(SU_WAIT_S, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) p.destroy()
            restartError(if (done) p.exitValue() else null, if (done) p.inputStream.bufferedReader().readText() else "")
        }.getOrElse { restartError(-1, it.message ?: "su not found") }
        root.value = r == null || root.value == true && r == TIMED_OUT
        return r
    }

    /**
     * The SystemUI module's own log lines (tagged Sukrara) from LSPosed's newest module log, read with Sukrara's root
     * (DebugReceiver `hookLog`; read-only: adb shell's su is refused on the phone). The last [lines] (1–500), then the
     * file's name; or why not.
     */
    fun hookLog(lines: Int): String = runCatching {
        val n = lines.coerceIn(1, 500)
        val script = "f=\$(ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -n 1); " +
            "[ -n \"\$f\" ] || { echo 'no LSPosed module log'; exit 0; }; grep Sukrara \"\$f\" | tail -n $n; echo \"== \$f\""
        val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        val out = java.util.concurrent.Executors.newSingleThreadExecutor().let { ex ->
            ex.submit<String> { p.inputStream.bufferedReader().readText() }.also { ex.shutdown() }
        }
        if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) { p.destroy(); return "no answer from su" }
        out.get(2, java.util.concurrent.TimeUnit.SECONDS).trimEnd()
    }.getOrElse { "couldn't read the module log: ${it.message}" }

    /** What a `su -c kill …` run means: null = System UI was killed; else a short reason ([exit] null = no answer in time). */
    internal fun restartError(exit: Int?, output: String): String? = when {
        exit == null -> TIMED_OUT
        exit == 0 -> null
        else -> output.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.take(120) ?: "su exit $exit"
    }

    internal const val TIMED_OUT = "no answer from su"
    private const val SU_WAIT_S = 30L
}
