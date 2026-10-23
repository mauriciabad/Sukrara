// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import android.app.Application
import android.content.Context
import app.mauri.sukrara.core.Notifications
import app.mauri.sukrara.follower.FollowerEngine
import app.mauri.sukrara.owner.OwnerEngine

/**
 * One install = one role, chosen at onboarding and stored in plain prefs ("owner" / "follower"). The two engines
 * never share storage: different SQLite files, different Keystore aliases, different state files. Only the engine
 * of the chosen role is ever created.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // the drawing code's words follow the app's language (per-app language, res/values-es)
        app.mauri.sukrara.core.Words.loader = { app.mauri.sukrara.core.Words.of(resources) }
        // every clock time follows Settings → 24-hour time, read live (graph/Clock)
        app.mauri.sukrara.graph.Clock.hour24Source = { android.text.format.DateFormat.is24HourFormat(this) }
        app.mauri.sukrara.graph.RoundedFont.init(this)   // the card's rounded numbers (Google Sans Flex, ROND 100)
        Notifications.channels(this)
        // a follower's Settings from (Default / Wearer / Custom) and the wearer's settings: the stores below read it
        app.mauri.sukrara.settings.WearerSettings.init(this)
        app.mauri.sukrara.alerts.Alerts.init(this)   // alert channels + settings (investigation 15)
        app.mauri.sukrara.features.Features.init(this)
        app.mauri.sukrara.features.SystemAccess.init(this)   // is the LSPosed module running in System UI?
        app.mauri.sukrara.graph.GraphSettings.init(this)
        app.mauri.sukrara.features.SensorCalendar.init(this)   // sensor end in calendar (owner only; follows SensorLife)
        app.mauri.sukrara.core.SensorLife.init(this)   // sensor end + countdown window (round sensor-life)
        // sensor end reminders + live countdowns (wearer); a failure there must never stop the app from starting
        runCatching { app.mauri.sukrara.sensorend.SensorEnd.init(this) }.onFailure { android.util.Log.e("Sukrara.SensorEnd", "init failed", it) }
        // insulin pens (inv. 29): the ledger, the background reader's enabled state, a timed-out pending dose
        // off the main thread: file read, a PackageManager call and WorkManager must not delay the service's start
        Thread {
            runCatching { app.mauri.sukrara.pen.Pens.init(this); app.mauri.sukrara.pen.Pens.syncComponent(this); app.mauri.sukrara.pen.PenWork.schedule(this) }
                .onFailure { android.util.Log.e("SukraraPen", "init failed", it) }
        }.start()
        app.mauri.sukrara.widgets.SukraraHooks.publishPreviewsOnce(this)
        app.mauri.sukrara.widgets.SukraraHooks.watchTreatments(this)   // treatments → widgets (both roles)
        app.mauri.sukrara.widgets.SukraraHooks.watchConfiguration(this)   // dark theme or language → widgets, channel names
        // The debug-exported CMD/DUMP receivers of CGM Share are gone: headless commands now go through the
        // DUMP-protected features.DebugReceiver (adb shell only), in debug and release alike.
    }

    companion object {
        @Volatile private var owner: OwnerEngine? = null
        @Volatile private var follower: FollowerEngine? = null

        fun role(ctx: Context): String? = ctx.getSharedPreferences("role", Context.MODE_PRIVATE).getString("role", null)

        fun setRole(ctx: Context, role: String?) {
            ctx.getSharedPreferences("role", Context.MODE_PRIVATE).edit().putString("role", role).apply()
            app.mauri.sukrara.settings.WearerSettings.setFollower(role == "follower")   // effective settings per role
            app.mauri.sukrara.widgets.RecentHistory.reload()   // the widgets read the new role's store
            app.mauri.sukrara.graph.GraphData.reload()
        }

        fun owner(ctx: Context): OwnerEngine {
            check(role(ctx) == "owner") { "owner engine requested but role is ${role(ctx)}" }
            return owner ?: synchronized(this) { owner ?: OwnerEngine(ctx.applicationContext).also { owner = it } }
        }

        fun follower(ctx: Context): FollowerEngine {
            check(role(ctx) == "follower") { "follower engine requested but role is ${role(ctx)}" }
            return follower ?: synchronized(this) { follower ?: FollowerEngine(ctx.applicationContext).also { follower = it } }
        }
    }
}
