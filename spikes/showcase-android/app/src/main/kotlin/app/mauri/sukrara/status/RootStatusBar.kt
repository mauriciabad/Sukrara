// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.status

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.features.FeatureState
import app.mauri.sukrara.features.Features
import java.io.BufferedReader
import java.io.Writer
import java.util.concurrent.Executors
import kotlin.math.roundToInt

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

    /** One thread: the daemon's stdin/stdout protocol is strictly request → reply. */
    private val io = Executors.newSingleThreadExecutor()
    @Volatile var state: String = "off"; private set
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
                if (!f.systemIcons) stopDaemon() else if (r != null) showIcons(app, r)
            }.onFailure { Log.w(TAG, "system icons", it) }
        }
    }

    fun stop(ctx: Context) {
        sendToHook(ctx, Features.value.copy(hook = false), null)
        io.execute { stopDaemon() }
    }

    // --- 3b: the SystemUI hook --------------------------------------------------------------------------

    private fun sendToHook(ctx: Context, f: FeatureState, r: Reading?) {
        val i = Intent(ACTION).setPackage("com.android.systemui")
            .putExtra("enabled", f.hook)
            .putExtra("position", f.hookPosition)
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
            sendToHook(c, Features.value, last)
        }
    }

    // --- 3a: system icons through the root daemon ---------------------------------------------------------

    private fun showIcons(ctx: Context, r: Reading) {
        // One resource per value: the Compose status bar ignores the icon level (see tools/gen-slot-drawables.swift).
        val number = when {
            r.sgv < 40 -> "sb_lo"
            r.sgv > 400 -> "sb_hi"
            else -> "sb_n${r.sgv}"
        }
        val rate = r.rate
        val trend = when {
            rate == null -> "sb_t_none"
            Trend.isDouble(rate) -> if (rate > 0) "sb_t_up2" else "sb_t_down2"
            else -> "sb_t%02d".format(((Trend.angleDeg(rate) + 90f) / 5f).roundToInt())
        }
        val desc = "Glucose ${r.sgv} mg/dL ${Trend.arrow(rate)}"
        // External icons are placed first (leftmost), newest first: add the trend before the number.
        if (!send(ctx, "set $SLOT_TREND ${res(ctx, trend)} 0 $desc")) return
        send(ctx, "set $SLOT_NUMBER ${res(ctx, number)} 0 $desc")
    }

    @SuppressLint("DiscouragedApi")
    private fun res(ctx: Context, name: String) = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)

    private fun send(ctx: Context, cmd: String): Boolean {
        if (proc?.isAlive != true && !startDaemon(ctx)) return false
        return try {
            stdin!!.apply { write(cmd + "\n"); flush() }
            val reply = stdout!!.readLine() ?: "eof"
            state = "on ($reply)"
            reply == "ok"
        } catch (e: Exception) {
            stopDaemon()
            state = "daemon died: ${e.message}"
            false
        }
    }

    private fun startDaemon(ctx: Context): Boolean = try {
        val cmd = "CLASSPATH=${ctx.applicationInfo.sourceDir} exec app_process /system/bin ${SlotDaemon::class.java.name} ${ctx.packageName}"
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        proc = p
        stdin = p.outputStream.writer()
        stdout = p.inputStream.bufferedReader()
        val hello = stdout!!.readLine()
        if (hello?.startsWith("ready") == true) { state = "on"; true } else { stopDaemon(); state = "daemon failed: $hello"; false }
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

    /** Root: restart SystemUI to (re)load the hook. A kill, not `am crash` (crashes count towards rescue mode). */
    fun restartSystemUi(): String = runCatching {
        ProcessBuilder("su", "-c", "kill $(pidof com.android.systemui)").redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText().ifEmpty { "killed" }
    }.getOrElse { "no root: ${it.message}" }
}
