// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.features

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
 *       [--es hookPosition after_clock|before_clock|icons:0|slot:|slot:wifi|…] [--ez restartSystemUi true] \
 *       [--ez hookColor B] [--ez hideNotifIcon B] [--ei previewSgv N [--ef previewRate R]]   (root status bar only, until the next reading) \
 *       [--ez screenOffSkip B] [--ez garmin B] [--ez nightscout B] [--ez nightscoutLan B] \
 *       [--es connPriority default|low|balanced|high] \
 *       [--es source juggluco|direct --ez confirmDirect true] \
 *       [--es cmd owner|invite|pair|approve|writer [--es f HEX] [--ez on B]] \
 *       [--es cmd follow --es link URL [--es name NAME]]   (follower role, e.g. an emulator following the phone)
 *       [--es cmd treat --es k rapid|long|carbs|note [--ef a AMOUNT] [--es n NOTE]] [--es cmd untreat --es id ID]
 *
 * docs/10-showcase.md has the table. `libreview true` only arms the uploader; it still needs the setup screen.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
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
                hideNotifIcon = b("hideNotifIcon", s.hideNotifIcon),
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
        if (i.hasExtra("systemIcons")) notes += "systemIcons is derived: --ez hook true --es hookPosition slot: (or slot:wifi …)"
        val out = JSONObject().put("features", f.toJson()).put("runtime", Live.status.value.toJson())
        out.put("systemIcons", app.mauri.sukrara.status.RootStatusBar.state)
        // Screenshots/tests: show a made-up value in the root status bar only, until the next real reading.
        if (i.hasExtra("previewSgv")) app.mauri.sukrara.status.RootStatusBar.update(c, f, app.mauri.sukrara.core.Reading(
            System.currentTimeMillis() / 1000, i.getIntExtra("previewSgv", 100),
            if (i.hasExtra("previewRate")) i.getFloatExtra("previewRate", 0f).toDouble() else null))
        // Root: reload the LSPosed module (kill, not `am crash`: crashes count towards Android's rescue mode).
        if (i.getBooleanExtra("restartSystemUi", false)) out.put("restartSystemUi", app.mauri.sukrara.status.RootStatusBar.restartSystemUi())
        runCatching { command(c, i, out) }.onFailure { notes += "cmd failed: ${it.message}" }
        if (App.role(c) == "owner" && App.owner(c).hasIdentity()) {
            val u = App.owner(c).ui.value
            out.put("owner", JSONObject().put("epoch", u.epoch).put("followers", u.followers.count { it.revoked == null })
                .put("pending", u.pending.size).put("liveSent", u.liveSent).put("chunksSent", u.chunksSent)
                .put("bleOn", u.bleOn).put("bleCentrals", u.bleCentrals).put("source", u.source)
                .put("profile", u.profile?.toJson() ?: JSONObject.NULL)
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
        out.put("widgets", app.mauri.sukrara.widgets.WidgetUpdater.placements(c))
        out.put("role", App.role(c) ?: JSONObject.NULL)
        if (notes.isNotEmpty()) out.put("notes", notes.joinToString("; "))
        resultData = out.toString()
        Log.i("Sukrara.debug", resultData)
    }

    /** Headless setup / pairing for tests on a locked phone. */
    private fun command(c: Context, i: Intent, out: JSONObject) {
        when (i.getStringExtra("cmd") ?: return) {
            "owner" -> {
                if (App.role(c) == null) App.setRole(c, "owner")
                val o = App.owner(c)
                if (!o.hasIdentity()) runBlocking { o.createIdentity() }
                c.startForegroundService(Intent(c, OwnerService::class.java))
                out.put("cmd", "owner ready")
            }
            "invite" -> out.put("invite", App.owner(c).newInvite())   // public link, no secret
            "widgetprobe" -> { app.mauri.sukrara.widgets.SukraraHooks.remeasureAll(c); out.put("cmd", "measuring graph widgets (logcat Sukrara.widgets)") }
            "pair" -> { App.owner(c).nearbyPairing(); out.put("cmd", "nearby pairing open 10 min") }
            "approve" -> App.owner(c).ui.value.pending.firstOrNull()?.let {
                App.owner(c).approve(it.f); out.put("cmd", "approved ${it.name} code ${it.code}")
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
            // treatments: the wearer's own log (Treat.store → followers), or a follower's writer entry
            "treat" -> {
                val k = i.getStringExtra("k") ?: "note"
                val a = i.getFloatExtra("a", 0f).toDouble()
                val n = i.getStringExtra("n") ?: ""
                if (App.role(c) == "follower") { App.follower(c).addTreatment(k, a, n); out.put("cmd", "follower entry sent") }
                else {
                    app.mauri.sukrara.treatments.Treat.init(c)
                    out.put("cmd", "added").put("id", app.mauri.sukrara.treatments.Treat.store.add(k, a, app.mauri.sukrara.core.Proto.nowS(), n).id)
                }
            }
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
