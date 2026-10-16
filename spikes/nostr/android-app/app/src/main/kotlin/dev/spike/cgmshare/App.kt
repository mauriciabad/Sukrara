// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.cgmshare

import android.app.Application
import android.content.Context
import dev.spike.cgmshare.core.Notifications
import dev.spike.cgmshare.follower.FollowerEngine
import dev.spike.cgmshare.owner.OwnerEngine

/**
 * One install = one role, chosen at onboarding and stored in plain prefs ("owner" / "follower"). The two engines
 * never share storage: different SQLite files, different Keystore aliases, different state files. Only the engine
 * of the chosen role is ever created.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.channels(this)
        if (BuildConfig.DEBUG) {
            // `adb -s emulator-… shell am broadcast -a dev.spike.cgmshare.DUMP` → app thread stacks in logcat
            registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, i: android.content.Intent) {
                    Thread.getAllStackTraces().forEach { (t, st) ->
                        if (st.any { it.className.startsWith("dev.spike") || it.className.contains("nostrdevkit") }) {
                            android.util.Log.i("CgmShare.dump", "thread ${t.name} ${t.state}\n" + st.take(25).joinToString("\n") { "  at $it" })
                        }
                    }
                }
            }, android.content.IntentFilter("dev.spike.cgmshare.DUMP"), RECEIVER_EXPORTED)
        }
    }

    companion object {
        @Volatile private var owner: OwnerEngine? = null
        @Volatile private var follower: FollowerEngine? = null

        fun role(ctx: Context): String? = ctx.getSharedPreferences("role", Context.MODE_PRIVATE).getString("role", null)

        fun setRole(ctx: Context, role: String?) =
            ctx.getSharedPreferences("role", Context.MODE_PRIVATE).edit().putString("role", role).apply()

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
