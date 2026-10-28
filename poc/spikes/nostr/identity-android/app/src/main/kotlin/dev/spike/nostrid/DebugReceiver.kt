// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.nostrid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Headless driving for tests (shell-only: the receiver is protected by android.permission.DUMP in the manifest).
 *   adb shell am broadcast -n dev.spike.nostrid/.DebugReceiver --es cmd create --es name Alex [--es shape cube]
 *   … --es cmd derive --es key <nsec of a THROWAWAY key> [--es mode own|nostr] [--es name …]
 *   … --es cmd invite | poll | allow | deny | reset
 *   … --es cmd join --es link <link> [--es name …]
 * Work runs in the background; read the redacted result with `adb shell run-as dev.spike.nostrid cat files/state.json`.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val e = Engine.get(ctx)
        val x = { k: String -> intent.getStringExtra(k) }
        val cmd = x("cmd") ?: return
        e.log("adb: $cmd")
        when (cmd) {
            "create" -> e.launch(cmd) {
                val a = Avatar.random(initial = x("name") ?: "").let { av -> x("shape")?.let { av.copy(shape = it) } ?: av }
                e.createGenerated(x("name") ?: "Test", a, null)
            }
            "derive" -> e.launch(cmd) { e.loginWithSecret(x("key")!!, x("password"), x("mode") ?: "own", x("name") ?: "Test", Avatar.random()) }
            "invite" -> e.launch(cmd) { e.createInvite() }
            "join" -> e.launch(cmd) { e.join(x("link")!!, x("name") ?: "Follower") }
            "poll" -> e.launch(cmd) { e.poll() }
            "allow", "deny" -> e.launch(cmd) {
                val r = e.state.value.optJSONArray("requests") ?: return@launch
                for (i in 0 until r.length()) if (r.getJSONObject(i).optString("status") == "pending") {
                    e.decide(r.getJSONObject(i).getString("f"), cmd == "allow"); break
                }
            }
            "reset" -> e.reset()
            "delete" -> e.launch(cmd) { e.deleteEverywhere() }
        }
        resultData = "ok: $cmd (state → files/state.json)"
        File(ctx.filesDir, "last-cmd.txt").writeText(cmd)
    }
}
