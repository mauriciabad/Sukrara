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
 *       [--ez macBle B] [--ez bleFollowers B] [--ez chip B] [--ez icon B] [--ez systemIcons B] [--ez hook B] \
 *       [--ez screenOffSkip B] [--ez garmin B] [--es connPriority default|low|balanced|high] \
 *       [--es source juggluco|direct --ez confirmDirect true] \
 *       [--es cmd owner|invite|pair|approve|writer [--es f HEX] [--ez on B]]
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
                macBle = b("macBle", s.macBle), bleFollowers = b("bleFollowers", s.bleFollowers),
                chip = b("chip", s.chip), icon = b("icon", s.icon), systemIcons = b("systemIcons", s.systemIcons), hook = b("hook", s.hook),
                screenOffSkip = b("screenOffSkip", s.screenOffSkip), garmin = b("garmin", s.garmin),
                connPriority = i.getStringExtra("connPriority") ?: s.connPriority,
                source = when {
                    wantSource == null -> s.source
                    wantSource == "direct" && !i.getBooleanExtra("confirmDirect", false) ->
                        s.source.also { notes += "source=direct needs --ez confirmDirect true (takes the sensor from Juggluco)" }
                    else -> wantSource
                },
            )
        }
        if (i.getBooleanExtra("systemIcons", false) || i.getBooleanExtra("hook", false)) {
            notes += "systemIcons/hook are not bundled in this build (always off)"
        }
        val out = JSONObject().put("features", f.toJson()).put("runtime", Live.status.value.toJson())
        runCatching { command(c, i, out) }.onFailure { notes += "cmd failed: ${it.message}" }
        if (App.role(c) == "owner" && App.owner(c).hasIdentity()) {
            val u = App.owner(c).ui.value
            out.put("owner", JSONObject().put("epoch", u.epoch).put("followers", u.followers.count { it.revoked == null })
                .put("pending", u.pending.size).put("liveSent", u.liveSent).put("chunksSent", u.chunksSent)
                .put("bleOn", u.bleOn).put("bleCentrals", u.bleCentrals).put("source", u.source))
        }
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
            "pair" -> { App.owner(c).nearbyPairing(); out.put("cmd", "nearby pairing open 10 min") }
            "approve" -> App.owner(c).ui.value.pending.firstOrNull()?.let {
                App.owner(c).approve(it.f); out.put("cmd", "approved ${it.name} code ${it.code}")
            } ?: out.put("cmd", "nothing pending")
            "writer" -> {
                App.owner(c).setWriter(i.getStringExtra("f") ?: return, i.getBooleanExtra("on", true))
                out.put("cmd", "writer set")
            }
        }
    }
}
