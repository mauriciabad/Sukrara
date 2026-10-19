// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.features

import android.content.Context
import android.content.Intent
import app.mauri.sukrara.BuildConfig
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.sensorend.SensorEnd
import app.mauri.sukrara.wearer.Live
import org.json.JSONObject

/**
 * The DebugReceiver commands that replace the sensor (SensorLife) or show or write made-up data (readings, unlocks, test alerts, status-bar previews, treatments, forced screens): **debug builds
 * only**. On a release build each one changes nothing and says so in the reply.
 *
 * Why (2026-10-18): `--es cmd sensorEnd`, sent to the user's release build only to read the state, faked a sensor
 * ending in 10 minutes over the real one: a live countdown started and the calendar events were deleted and
 * re-created. Guarded twice: [run] is the only way in and starts with the gate (`tools/check` rule `debug`: no
 * fake/SensorLife.update calls in DebugReceiver.kt, one public entry here gated on `debug`), and `DebugFakesTest`
 * runs every command with `debug = false` and checks that nothing changed.
 */
object DebugFakes {
    /**
     * The commands handled here: DebugReceiver's `cmd` values (`sensorEnd` only with `--ei inMin`, `newSensorStage` =
     * `newSensor --es stage`), and the extras `alertFeed`, `alertTest`, `previewSgv`. **The release app never shows a
     * glucose value, an alert, a treatment or a sensor state that isn't real**, because the user acts on what Sukrara
     * shows (2026-10-19): everything that shows or writes made-up data is here.
     */
    val COMMANDS = setOf("fakeSensor", "sensor", "backfill", "sensorEnd", "sensorStart", "sensorUnlock", "alertFeed",
        "alertTest", "previewSgv", "locktest", "newSensorStage", "treat", "sensorProfile")

    fun run(c: Context, cmd: String, i: Intent, out: JSONObject, debug: Boolean = BuildConfig.DEBUG) {
        if (!debug) { out.accumulate("debugOnly", "$cmd: debug builds only, nothing changed"); return }
        when (cmd) {
            // a test alert (fires once, shared with followers)
            "alertTest" -> { app.mauri.sukrara.alerts.Alerts.test(c, i.getStringExtra("alertTest") ?: "high", share = true); out.put("alertTest", "fired") }
            // a made-up value in the root status bar only, until the next real reading (screenshots)
            "previewSgv" -> app.mauri.sukrara.status.RootStatusBar.update(c, Features.value, Reading(System.currentTimeMillis() / 1000,
                i.getIntExtra("previewSgv", 100), if (i.hasExtra("previewRate")) i.getFloatExtra("previewRate", 0f).toDouble() else null))
            // the lock screen widget's test pattern (a 100 dp magenta square + circle) instead of the value
            "locktest" -> {
                app.mauri.sukrara.widgets.LockscreenWidget.testPattern = i.getBooleanExtra("on", true)
                app.mauri.sukrara.widgets.SukraraHooks.refresh(c, "locktest", force = true)
                out.put("cmd", "lock screen widget test pattern ${app.mauri.sukrara.widgets.LockscreenWidget.testPattern}")
            }
            // the New sensor screen forced to one step (screenshots), whatever the sensor really does
            "newSensorStage" -> {
                val stage = app.mauri.sukrara.ui.NewSensorFlow.previewStage(i.getStringExtra("stage") ?: "", System.currentTimeMillis() / 1000)
                app.mauri.sukrara.ui.NewSensorFlow.preview.value = stage
                c.startActivity(app.mauri.sukrara.ui.MainActivity.sensorIntent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                out.put("cmd", "newSensor $stage")
            }
            "treat" -> treat(c, i, out)
            // the active sensor's family forced (the untested-sensor banner on home; screenshots), until the source restarts
            "sensorProfile" -> {
                val p = app.mauri.sukrara.core.SensorProfiles.byId(i.getStringExtra("profile") ?: "")
                app.mauri.sukrara.core.ActiveSensor.profile.value = p
                out.put("cmd", "active sensor profile ${p?.id}")
            }
            "fakeSensor" -> fakeSensor(c, i, out)
            "sensor" -> sensor(c, i, out)
            "backfill" -> backfill(i, out)
            "sensorEnd" -> { SensorEnd.fake(c, i.getIntExtra("inMin", 10)); out.put("cmd", "made-up sensor ending in ${i.getIntExtra("inMin", 10)} min") }
            "sensorStart" -> {
                if (i.getBooleanExtra("firstReading", false)) SensorEnd.fakeFirstReading(c) else SensorEnd.fakeStart(c, i.getIntExtra("agoMin", 50))
                out.put("cmd", "sensorStart (made-up data)")
            }
            "sensorUnlock" -> { SensorEnd.unlocked(c); out.put("cmd", "made-up unlock") }
            "alertFeed" -> alertFeed(c, i, out)
        }
    }

    /**
     * A treatment typed over adb (test data): the wearer's own log (charts, followers, LibreView when on) or a
     * follower's writer entry. `untreat` (deleting by id) stays allowed on release: it removes, never makes up.
     */
    private fun treat(c: Context, i: Intent, out: JSONObject) {
        val k = i.getStringExtra("k") ?: "note"
        val a = i.getFloatExtra("a", 0f).toDouble()
        val n = i.getStringExtra("n") ?: ""
        if (app.mauri.sukrara.App.role(c) == "follower") { app.mauri.sukrara.App.follower(c).addTreatment(k, a, n); out.put("cmd", "follower entry sent") }
        else {
            app.mauri.sukrara.treatments.Treat.init(c)
            out.put("cmd", "added").put("id", app.mauri.sukrara.treatments.Treat.store.add(k, a, Proto.nowS(), n).id)
        }
    }

    /** A made-up sensor (synthetic serial; --el start/end unix s, end default start + 15 d) or --ez clear true. */
    private fun fakeSensor(c: Context, i: Intent, out: JSONObject) {
        val start = i.getLongExtra("start", System.currentTimeMillis() / 1000)
        val info = if (i.getBooleanExtra("clear", false)) null else SensorInfo(
            i.getStringExtra("serial") ?: "TEST0000001", start, i.getLongExtra("end", start + 60L * SensorLife.DEFAULT_WEAR_MIN))
        SensorLife.update(c, info)
        out.put("cmd", "sensor ${info ?: "cleared"}")
    }

    /** A synthetic sensor `--el left <s>` before its end (`--el len <s>`, default 15 d), `--es serial`, or `--ez clear true`. */
    private fun sensor(c: Context, i: Intent, out: JSONObject) {
        val now = Proto.nowS()
        if (i.hasExtra("countdown")) SensorLife.setCountdownHours(c, i.getIntExtra("countdown", 8))
        if (i.getBooleanExtra("clear", false)) SensorLife.update(c, null)
        else if (i.hasExtra("left")) {
            val end = now + i.getLongExtra("left", 0)
            val len = i.getLongExtra("len", 60L * SensorLife.DEFAULT_WEAR_MIN)
            SensorLife.update(c, SensorInfo(i.getStringExtra("serial") ?: "DEBUG", end - len, end))
        }
        app.mauri.sukrara.widgets.SukraraHooks.refresh(c, "sensor", force = true)
        out.put("cmd", "sensor ${SensorLife.current.value} countdown ${SensorLife.countdown.value} h")
    }

    /** A direct-source reconnect's burst into Live: the reading, then `--ei packets <n>` (16) history packets 50 ms apart. */
    private fun backfill(i: Intent, out: JSONObject) {
        val n = i.getIntExtra("packets", 16)
        val now = Proto.nowS()
        val last = Live.status.value.last
        Thread {
            Live.reading(Reading(now, (last?.sgv ?: 120) + 1, 0.5, "debug"))
            repeat(n) { p ->
                Thread.sleep(50)
                Live.history((0 until 9).map { k -> Reading(now - 3600 * 12 + (p * 9 + k) * 300L, 110 + k, null, "debug") })
            }
        }.start()
        out.put("cmd", "backfill burst: 1 reading + $n history packets")
    }

    /** `--es alertFeed '204,-0.5'`: a made-up reading through the alert rules (until the next real one). */
    private fun alertFeed(c: Context, i: Intent, out: JSONObject) {
        val p = i.getStringExtra("alertFeed")?.split(",") ?: return
        app.mauri.sukrara.alerts.Alerts.onReading(c, Reading(System.currentTimeMillis() / 1000, p[0].trim().toInt(),
            p.getOrNull(1)?.trim()?.toDoubleOrNull()))
        out.put("alertFeed", "made-up reading ${p[0].trim()}")
    }
}
