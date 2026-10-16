// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.powerlab

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Runs an A/B measurement plan on the phone itself, unattended and unplugged (e.g. overnight):
 * for each window: apply the scenario → settle → snapshot → measure → snapshot → restore.
 * Snapshots and scenario switches use root (`su`): Perfetto power rails, /proc CPU times, `svc wifi`,
 * `am force-stop`… The phone is only touched over adb between runs, so adb adds no noise to the windows.
 *
 * Plan file (one window per line, `#` comments):  name | apply command | restore command
 */
object Lab {
    private const val TAG = "PowerLab"
    private const val PREFS = "lab"

    data class Window(val name: String, val apply: String, val restore: String)

    fun planFile(c: Context) = File(c.getExternalFilesDir(null), "plan.txt")

    fun loadPlan(c: Context): List<Window> {
        val f = planFile(c)
        if (!f.exists()) f.writeText(c.resources.openRawResource(R.raw.default_plan).bufferedReader().readText())
        return f.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.map {
            val p = it.split('|').map(String::trim)
            Window(p[0], p.getOrElse(1) { "" }, p.getOrElse(2) { "" })
        }
    }

    // --- control ---------------------------------------------------------------------------------------

    fun start(c: Context, settleMin: Int, measureMin: Int, delaySec: Int) {
        val root = su(c, "id")
        log(c, "root check: $root")
        val run = File(c.getExternalFilesDir(null), "run-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()))
        run.mkdirs()
        val plan = loadPlan(c) // writes the default plan if there is none
        planFile(c).copyTo(File(run, "plan.txt"), overwrite = true)
        prefs(c).edit().putString("run", run.path).putInt("idx", 0).putString("phase", "apply")
            .putInt("settle", settleMin).putInt("measure", measureMin).putBoolean("running", true).apply()
        log(c, "start ${run.name}: ${plan.size} windows, settle ${settleMin} + measure ${measureMin} min")
        schedule(c, delaySec * 1000L)
    }

    fun stop(c: Context) {
        val p = prefs(c)
        if (p.getBoolean("running", false)) loadPlan(c).getOrNull(p.getInt("idx", 0))?.let { su(c, it.restore) }
        p.edit().putBoolean("running", false).apply()
        alarms(c).cancel(stepIntent(c))
        su(c, "dumpsys deviceidle unforce")
        log(c, "stopped")
    }

    fun status(c: Context): String {
        val p = prefs(c)
        return "running=${p.getBoolean("running", false)} idx=${p.getInt("idx", 0)}/${loadPlan(c).size} " +
            "phase=${p.getString("phase", "")} next=${p.getLong("next", 0)} run=${p.getString("run", "")}"
    }

    /** Called by [StepReceiver] when the alarm fires (with a wakelock held). */
    fun step(c: Context) {
        val p = prefs(c)
        if (!p.getBoolean("running", false)) return
        val plan = loadPlan(c)
        val idx = p.getInt("idx", 0)
        val w = plan.getOrNull(idx) ?: return finish(c)
        val run = File(p.getString("run", "")!!)
        val label = "%02d-%s".format(idx + 1, w.name)
        when (p.getString("phase", "apply")) {
            "apply" -> {
                if (w.apply.isNotEmpty()) log(c, "$label apply: ${su(c, w.apply)}")
                // Same Doze state in every window: deep idle, as a phone lying still overnight reaches anyway.
                log(c, "$label idle: ${su(c, "dumpsys deviceidle force-idle deep")}")
                p.edit().putString("phase", "start").apply()
                schedule(c, TimeUnit.MINUTES.toMillis(p.getInt("settle", 3).toLong()))
            }
            "start" -> {
                snapshot(c, run, "$label-start")
                p.edit().putString("phase", "end").apply()
                schedule(c, TimeUnit.MINUTES.toMillis(p.getInt("measure", 30).toLong()))
            }
            "end" -> {
                snapshot(c, run, "$label-end")
                if (w.restore.isNotEmpty()) log(c, "$label restore: ${su(c, w.restore, timeoutSec = 90)}")
                p.edit().putInt("idx", idx + 1).putString("phase", "apply").apply()
                if (idx + 1 < plan.size) schedule(c, 5_000) else finish(c)
            }
        }
    }

    private fun finish(c: Context) {
        prefs(c).edit().putBoolean("running", false).apply()
        log(c, "finished: ${su(c, "dumpsys deviceidle unforce")}")
    }

    private fun snapshot(c: Context, run: File, label: String) {
        val script = File(c.filesDir, "snap.sh")
        script.writeText(c.resources.openRawResource(R.raw.snap).bufferedReader().readText())
        val r = su(c, "sh ${script.path} ${run.path} $label", timeoutSec = 60)
        log(c, "$label snapshot ${if (File(run, "$label.txt").exists()) "ok" else "FAILED: $r"}")
    }

    // --- plumbing ----------------------------------------------------------------------------------------

    private fun schedule(c: Context, delayMs: Long) {
        val at = System.currentTimeMillis() + delayMs
        prefs(c).edit().putLong("next", at).apply()
        // Exact and allowed while idle: the windows run in deep Doze.
        alarms(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, stepIntent(c))
    }

    private fun stepIntent(c: Context) = PendingIntent.getBroadcast(c, 0, Intent(c, StepReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun alarms(c: Context) = c.getSystemService(AlarmManager::class.java)
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun su(c: Context, cmd: String, timeoutSec: Long = 30): String = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.destroyForcibly()
        reader.join(2_000)
        out.toString().trim().replace('\n', ' ').take(300).ifEmpty { "exit ${runCatching { p.exitValue() }.getOrDefault(-1)}" }
    } catch (e: Exception) {
        "error: ${e.message}"
    }

    fun log(c: Context, s: String) {
        Log.i(TAG, s)
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + s + "\n"
        File(c.getExternalFilesDir(null), "log.txt").appendText(line)
    }

    fun wakeLock(c: Context): PowerManager.WakeLock =
        c.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PowerLab:step")
}
