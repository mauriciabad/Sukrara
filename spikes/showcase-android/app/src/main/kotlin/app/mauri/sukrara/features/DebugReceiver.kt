// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.features

import kotlinx.coroutines.launch
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.mauri.sukrara.App
import app.mauri.sukrara.owner.OwnerService
import app.mauri.sukrara.wearer.Live
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * adb-only switches (the receiver requires android.permission.DUMP, which the shell/root has and apps can't get).
 * Every extra is optional; the reply (`resultData`) is the full state as JSON. Nothing restarts the process.
 *
 *   am broadcast -n app.mauri.sukrara.poc/app.mauri.sukrara.features.DebugReceiver \
 *       [--ez nostr B] [--ei nostrInterval MIN] [--ez libreview B] [--ei libreviewInterval MIN] \
 *       [--ez bleFollowers B] [--ez chip B] [--ez icon B] [--ez hook B] \
 *       [--es hookPosition after_clock|before_clock|icons:0|slot:|slot:<mobile|slot:wifi|…] [--ez restartSystemUi true] \
 *       [--ez hookColor B] [--ez hideNotifIcon B] \
 *       [--ez screenOffSkip B] [--ez garmin B] [--ez nightscout B] [--ez nightscoutLan B] \
 *       [--es connPriority default|low|balanced|high] \
 *       [--es source juggluco|direct --ez confirmDirect true] \
 *       [--es cmd owner|invite|pair|approve|deny|writer [--es f HEX] [--ez on B]] \
 *       [--es cmd follow --es link URL [--es name NAME]]   (follower role, e.g. an emulator following the phone)
 *       [--es cmd untreat --es id ID] [--es cmd profile [--es name N] [--ez shuffle true]]
 *       [--es cmd sensorEnd [--ei countdownHours H] [--ez countdown B] [--es reminders 'D@MIN,…'] [--ez unlock B] [--ez evening B] [--es night 'MIN-MIN']] [--es cmd sensorEndState]
 *           (no extras = the state only, nothing changes)
 *       [--es cmd calendar [--ez on B]]   (sensor end in calendar: state + events as JSON) \
 *       [--es cmd newSensor]   (opens the New sensor screen, the live flow) \
 *       [--es graph JSON] [--es widgetGraph ID:JSON] [--es cmd pinWidget --es kind value|graph|combined] [--es cmd widgetprobe]
 *       [--es alertSettings JSON]
 *       [--es cmd hookLog [--ei lines N]]   (read-only: the last N "Sukrara" lines of LSPosed's newest module log, via root)
 *     debug builds only (DebugFakes: they replace the sensor, or show / write made-up data: a glucose value, an alert, a
 *     treatment, a sensor state; a release build changes nothing and the reply's `debugOnly` says so):
 *       [--es cmd sensorEnd --ei inMin N] [--es cmd sensorUnlock] [--es cmd sensorStart [--ei agoMin N | --ez firstReading true]]
 *       [--es cmd fakeSensor [--es serial S] [--el start UNIX] [--el end UNIX] [--ez clear true]] [--es cmd sensor …] [--es cmd backfill …]
 *       [--es alertTest KIND] [--es alertFeed 'SGV,RATE'] [--ei previewSgv N [--ef previewRate R]]   (root status bar, until the next reading)
 *       [--es cmd locktest [--ez on B]]   (lock screen widget: a 100 dp magenta square + circle, to measure distortion)
 *       [--es cmd newSensor --es stage waiting|reading|signin|pairing|slow|warmup|ready|ended|libre2|failed|bluetooth|nfcoff|
 *                                      choose|untested|pairfailed|setup-<profile id>]
 *       [--es cmd sensorProfile --es profile ID]   (the active sensor's family: the untested-sensor banner; none = clear)
 *       [--es cmd treat --es k rapid|long|carbs|note [--ef a AMOUNT] [--es n NOTE]]
 *
 * docs/10-showcase.md has the table. `libreview true` only arms the uploader; it still needs the setup screen.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        resultData = reply(c, i).toString()
        Log.i("Sukrara.debug", resultData)
    }

    /** Applies the extras and returns the state (DebugFakesTest calls it directly: a test has no pending broadcast result). */
    internal fun reply(c: Context, i: Intent): JSONObject {
        Features.init(c)
        val notes = mutableListOf<String>()
        fun b(k: String, cur: Boolean) = if (i.hasExtra(k)) i.getBooleanExtra(k, cur) else cur
        fun n(k: String, cur: Int) = if (i.hasExtra(k)) i.getIntExtra(k, cur) else cur
        val wantSource = i.getStringExtra("source")
        val f = Features.update { s ->
            s.copy(
                nostr = b("nostr", s.nostr), nostrIntervalMin = n("nostrInterval", s.nostrIntervalMin),
                libreview = b("libreview", s.libreview), libreviewIntervalMin = n("libreviewInterval", s.libreviewIntervalMin),
                bleFollowers = b("bleFollowers", s.bleFollowers),
                chip = b("chip", s.chip), icon = b("icon", s.icon), hook = b("hook", s.hook),
                hookPosition = i.getStringExtra("hookPosition") ?: s.hookPosition, hookColor = b("hookColor", s.hookColor),
                hideNotifIcon = b("hideNotifIcon", s.hideNotifIcon), lockWidget = b("lockWidget", s.lockWidget),
                lockBg = i.getStringExtra("lockBg") ?: s.lockBg, lockLook = i.getStringExtra("lockLook") ?: s.lockLook,
                screenOffSkip = b("screenOffSkip", s.screenOffSkip), garmin = b("garmin", s.garmin),
                nightscout = b("nightscout", s.nightscout), nightscoutLan = b("nightscoutLan", s.nightscoutLan),
                connPriority = i.getStringExtra("connPriority") ?: s.connPriority,
                source = when {
                    wantSource == null -> s.source
                    wantSource == "direct" && !i.getBooleanExtra("confirmDirect", false) ->
                        s.source.also { notes += "source=direct needs --ez confirmDirect true (takes the sensor from Juggluco)" }
                    else -> wantSource
                },
            )
        }
        // Lock screen widget style: the module and the widget redraw now, not at the next reading.
        if (listOf("lockWidget", "lockBg", "lockLook").any(i::hasExtra)) {
            app.mauri.sukrara.status.RootStatusBar.pushState(c)
            app.mauri.sukrara.widgets.SukraraHooks.refresh(c, "lock screen style", force = true)
        }
        if (i.hasExtra("systemIcons")) notes += "systemIcons is derived: --ez hook true --es hookPosition slot: (or slot:wifi …)"
        val out = JSONObject().put("features", f.toJson()).put("runtime", Live.status.value.toJson())
        out.put("systemIcons", app.mauri.sukrara.status.RootStatusBar.state)
        // Screenshots: a made-up value in the root status bar until the next real reading. Debug builds only (DebugFakes).
        if (i.hasExtra("previewSgv")) DebugFakes.run(c, "previewSgv", i, out)
        // Root: reload the LSPosed module (kill, not `am crash`: crashes count towards Android's rescue mode).
        if (i.getBooleanExtra("restartSystemUi", false)) out.put("restartSystemUi", app.mauri.sukrara.status.RootStatusBar.restartSystemUi() ?: "killed")
        runCatching { command(c, i, out) }.onFailure { notes += "cmd failed: ${it.message}" }
        // Investigation 15: --es alertSettings '<json>' (SPEC §3). Debug builds only (DebugFakes): --es alertTest high
        // (fires once, shared with followers) · --es alertFeed '204,-0.5' (a made-up reading through the rules)
        val alerts = app.mauri.sukrara.alerts.Alerts
        i.getStringExtra("alertSettings")?.let { j ->
            alerts.update(c) { app.mauri.sukrara.alerts.AlertSettings.fromJson(JSONObject(j)) }
        }
        if (i.hasExtra("alertTest")) DebugFakes.run(c, "alertTest", i, out)
        if (i.hasExtra("alertFeed")) DebugFakes.run(c, "alertFeed", i, out)
        alerts.init(c)
        out.put("alerts", alerts.toJson())
        // root (su for Sukrara) and the LSPosed module as Settings sees them
        SystemAccess.refresh()
        out.put("systemAccess", JSONObject().put("root", app.mauri.sukrara.status.RootStatusBar.root.value ?: JSONObject.NULL)
            .put("module", SystemAccess.status.value.module.name).put("framework", SystemAccess.status.value.framework ?: JSONObject.NULL)
            .put("systemUi", SystemAccess.status.value.target ?: JSONObject.NULL))
        // Investigation 16: --es graph '{"profile":"KDE_LOG","defaultHours":6,"ringCount":3,…}' (GraphConfig.toJson
        // fields, merged into the current ones) · --es widgetGraph '<appWidgetId>:{"hours":6,"rings":1,"side":false}'
        val graph = app.mauri.sukrara.graph.GraphSettings
        graph.init(c)
        i.getStringExtra("graph")?.let { j ->
            val patch = JSONObject(j)
            graph.update { cur -> app.mauri.sukrara.graph.GraphConfig.fromJson(cur.toJson().also { o -> patch.keys().forEach { k -> o.put(k, patch.get(k)) } }) }
            app.mauri.sukrara.widgets.SukraraHooks.refresh(c, "debug graph", force = true)
        }
        i.getStringExtra("widgetGraph")?.let { j ->
            graph.setWidget(c, j.substringBefore(":").toInt(), app.mauri.sukrara.graph.WidgetGraphConfig.fromJson(JSONObject(j.substringAfter(":"))))
            app.mauri.sukrara.widgets.SukraraHooks.refresh(c, "debug widget graph", force = true)
        }
        out.put("graph", graph.value.toJson())
        if (App.role(c) == "owner" && App.owner(c).hasIdentity()) {
            val u = App.owner(c).ui.value
            out.put("owner", JSONObject().put("epoch", u.epoch).put("followers", u.followers.count { it.revoked == null })
                .put("pending", u.pending.size).put("liveSent", u.liveSent).put("chunksSent", u.chunksSent)
                .put("bleOn", u.bleOn).put("bleCentrals", u.bleCentrals).put("source", u.source)
                .put("profile", u.profile?.toJson() ?: JSONObject.NULL)
                .put("pendingRequests", org.json.JSONArray(u.pending.map {
                    JSONObject().put("name", it.name).put("label", it.label).put("nearby", it.nearby).put("others", it.others)
                }))
                .put("followerNames", org.json.JSONArray(u.followers.filter { it.revoked == null }.map { "${it.name} ${it.avatar ?: "-"}" })))
        }
        if (App.role(c) == "follower") {
            val u = App.follower(c).ui.value
            out.put("follower", JSONObject().put("status", u.status).put("code", u.code ?: JSONObject.NULL)
                .put("epoch", u.epoch).put("readings", u.count).put("rejects", u.rejects)
                .put("last", u.last?.let { JSONObject().put("t", it.t).put("sgv", it.sgv).put("via", it.via) } ?: JSONObject.NULL)
                .put("ble", u.ble).put("canWrite", u.canWrite)
                .put("me", u.me?.toJson() ?: JSONObject.NULL).put("wearer", u.wearer?.toJson() ?: JSONObject.NULL)
                .put("treatments", org.json.JSONArray(u.treatments.map { e ->
                    JSONObject().put("id", e.tr.id).put("k", e.tr.k).put("a", e.tr.a).put("n", e.tr.n).put("del", e.tr.del)
                        .put("o", e.tr.o).put("status", e.status ?: JSONObject.NULL)
                })))
        }
        app.mauri.sukrara.core.SensorLife.current.value?.let { si ->
            out.put("sensor", JSONObject().put("serial", si.serial).put("start", si.startEpoch).put("end", si.endEpoch)
                .put("label", app.mauri.sukrara.core.SensorLife.label(si.endEpoch, app.mauri.sukrara.core.Proto.nowS())))
        }
        out.put("widgets", app.mauri.sukrara.widgets.WidgetUpdater.placements(c))
        out.put("role", App.role(c) ?: JSONObject.NULL)
        if (notes.isNotEmpty()) out.put("notes", notes.joinToString("; "))
        return out
    }

    /** Headless setup / pairing for tests on a locked phone. */
    private fun command(c: Context, i: Intent, out: JSONObject) {
        val cmd = i.getStringExtra("cmd") ?: return
        when (cmd) {
            "owner" -> {
                if (App.role(c) == null) App.setRole(c, "owner")
                val o = App.owner(c)
                if (!o.hasIdentity()) runBlocking { o.createIdentity() }
                c.startForegroundService(Intent(c, OwnerService::class.java))
                out.put("cmd", "owner ready")
            }
            // made-up sensors and data (DebugFakes): debug builds only, a release build changes nothing and says so
            "fakeSensor", "sensor", "backfill", "sensorStart", "sensorUnlock", "locktest", "treat", "sensorProfile" -> DebugFakes.run(c, cmd, i, out)
            // sensor end in calendar: the calendars and Sukrara's events as the provider has them ([--ez on false] turns it off)
            "calendar" -> {
                SensorCalendar.init(c)
                if (i.hasExtra("on") && !i.getBooleanExtra("on", true)) SensorCalendar.disable(c)
                else if (i.getBooleanExtra("on", false)) { if (SensorCalendar.granted(c)) SensorCalendar.enable(c) else out.put("calendar", "grant READ/WRITE_CALENDAR first (pm grant)") }
                out.put("calendar", SensorCalendar.dump(c))
            }
            // the New sensor screen, live; a forced step (--es stage) shows a made-up state: debug builds only
            "newSensor" -> if (i.hasExtra("stage")) DebugFakes.run(c, "newSensorStage", i, out) else {
                app.mauri.sukrara.ui.NewSensorFlow.preview.value = null
                c.startActivity(app.mauri.sukrara.ui.MainActivity.sensorIntent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                out.put("cmd", "newSensor live")
            }
            "invite" -> out.put("invite", App.owner(c).newInvite(i.getStringExtra("label") ?: ""))   // --es label <who it's for>
            // investigation 16: ask the launcher to place a widget (it shows its own "Add to home screen" dialog)
            "pinWidget" -> {
                val r = when (i.getStringExtra("kind")) {
                    "value" -> app.mauri.sukrara.widgets.ValueWidgetReceiver::class.java
                    "graph" -> app.mauri.sukrara.widgets.GraphWidgetReceiver::class.java
                    else -> app.mauri.sukrara.widgets.CombinedWidgetReceiver::class.java
                }
                // with the preview the home screen's banner sends (WidgetPresence.pinPreview)
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    val extras = app.mauri.sukrara.widgets.WidgetPresence.pinPreview(c, r)
                    android.util.Log.i("Sukrara.debug", "pin requested: " + android.appwidget.AppWidgetManager.getInstance(c)
                        .requestPinAppWidget(android.content.ComponentName(c, r), extras, null) + " preview=" + (extras != null))
                }
                out.put("cmd", "pin requested (with its preview, see the log)")
            }
            // the home screen's "add the widget" banner: what it sees (read-only); --ez reset true shows it again
            "widgetBanner" -> {
                if (i.getBooleanExtra("reset", false)) app.mauri.sukrara.widgets.WidgetPresence.undismiss(c)
                else app.mauri.sukrara.widgets.WidgetPresence.refresh(c)
                out.put("widgetBanner", app.mauri.sukrara.widgets.WidgetPresence.state.value.toString())
            }
            // read-only (any build): the SystemUI module's own log lines, from LSPosed's newest module log via Sukrara's
            // root (adb shell's su is refused on the phone)
            "hookLog" -> out.put("cmd", app.mauri.sukrara.status.RootStatusBar.hookLog(i.getIntExtra("lines", 80)))
            "widgetprobe" -> { app.mauri.sukrara.widgets.SukraraHooks.remeasureAll(c); out.put("cmd", "measuring graph widgets (logcat Sukrara.widgets)") }
            "pair" -> { App.owner(c).nearbyPairing(); out.put("cmd", "nearby pairing open 10 min") }
            // sensor end: without extras only the state (nothing changes). Settings: --ei countdownHours H; --ez countdown B;
            // --es reminders '1@540,0@540' (daysBefore@minuteOfDay); --ez unlock B (first-unlock reminders); --ez evening B;
            // --es night '1380-420' (night window, minutes of the day). Debug builds only (DebugFakes): a made-up sensor
            // ending in --ei inMin N (only when given); an unlock (as ACTION_USER_PRESENT): --es cmd sensorUnlock
            "sensorEnd" -> {
                val se = app.mauri.sukrara.sensorend.SensorEnd
                if (i.hasExtra("countdownHours")) se.setCountdownHours(c, i.getIntExtra("countdownHours", 8))
                if (listOf("countdown", "reminders", "unlock", "evening", "night").any { i.hasExtra(it) }) se.update(c) { cfg ->
                    val night = i.getStringExtra("night")?.split('-')?.map { it.trim().toInt() }
                    cfg.copy(countdown = i.getBooleanExtra("countdown", cfg.countdown),
                        reminders = i.getStringExtra("reminders")?.split(',')?.filter { it.isNotBlank() }?.map {
                            app.mauri.sukrara.sensorend.Reminder(it.substringBefore('@').trim().toInt(), it.substringAfter('@').trim().toInt())
                        } ?: cfg.reminders,
                        unlock = i.getBooleanExtra("unlock", cfg.unlock), evening = i.getBooleanExtra("evening", cfg.evening),
                        nightStart = night?.get(0) ?: cfg.nightStart, nightEnd = night?.get(1) ?: cfg.nightEnd)
                }
                if (i.hasExtra("inMin")) DebugFakes.run(c, "sensorEnd", i, out)
                out.put("sensorEnd", se.state(c))
            }
            "sensorEndState" -> out.put("sensorEnd", app.mauri.sukrara.sensorend.SensorEnd.state(c))
            "approve" -> App.owner(c).ui.value.pending.firstOrNull()?.let {
                App.owner(c).approve(it.f); out.put("cmd", "approved ${it.name} code ${it.code}")
            } ?: out.put("cmd", "nothing pending")
            "deny" -> App.owner(c).ui.value.pending.firstOrNull()?.let {
                App.owner(c).deny(it.f); out.put("cmd", "denied ${it.name}")
            } ?: out.put("cmd", "nothing pending")
            "follow" -> {
                if (App.role(c) == null) App.setRole(c, "follower")
                val link = i.getStringExtra("link") ?: return out.put("cmd", "follow needs --es link URL").let { }
                val me = app.mauri.sukrara.core.Profile(i.getStringExtra("name") ?: "Follower (adb)", app.mauri.sukrara.core.Avatar.random())
                val err = runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(25_000) {
                        App.follower(c).resolveInvite(link).fold({ inv -> listOf(App.follower(c).join(inv, me)) }, { listOf("invite: ${it.message}") })
                    }?.first() ?: if (App.follower(c).hasJoined()) null else "timeout"
                }
                if (err == null) c.startForegroundService(Intent(c, app.mauri.sukrara.follower.FollowerService::class.java))
                out.put("cmd", err ?: "follow request sent (check the code on the wearer)")
            }
            // name + picture (both roles): --es name <n> [--ez shuffle true]; the wearer's goes out with the roster
            "profile" -> {
                val shuffle = i.getBooleanExtra("shuffle", false)
                if (App.role(c) == "follower") {
                    val cur = App.follower(c).ui.value.me ?: return out.put("cmd", "not following").let { }
                    App.follower(c).setProfile(cur.copy(name = i.getStringExtra("name") ?: cur.name,
                        avatar = if (shuffle) app.mauri.sukrara.core.Avatar.random() else cur.avatar))
                } else {
                    val cur = App.owner(c).ui.value.profile ?: return out.put("cmd", "no identity").let { }
                    App.owner(c).setProfile(cur.copy(name = i.getStringExtra("name") ?: cur.name,
                        avatar = if (shuffle) app.mauri.sukrara.core.Avatar.random() else cur.avatar))
                }
                out.put("cmd", "profile set")
            }
            // treatments: adding one (test data) is debug-only (DebugFakes, above); deleting your own by id is allowed
            "untreat" -> {
                app.mauri.sukrara.treatments.Treat.init(c)
                out.put("cmd", if (app.mauri.sukrara.treatments.Treat.store.delete(i.getStringExtra("id") ?: "") != null) "deleted" else "no such entry")
            }
            "writer" -> {
                App.owner(c).setWriter(i.getStringExtra("f") ?: return, i.getBooleanExtra("on", true))
                out.put("cmd", "writer set")
            }
        }
    }
}
