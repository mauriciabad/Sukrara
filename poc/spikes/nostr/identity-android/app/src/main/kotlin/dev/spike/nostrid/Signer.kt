// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.nostrid

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.nostrdevkit.sdk.Event
import org.nostrdevkit.sdk.EventBuilder
import org.nostrdevkit.sdk.Kind
import org.nostrdevkit.sdk.Nip44Version
import org.nostrdevkit.sdk.PublicKey
import org.nostrdevkit.sdk.Timestamp
import org.nostrdevkit.sdk.nip44Encrypt

/**
 * NIP-55 Android signer (Amber): the app never sees the main key. Content Resolver first (silent, works once the
 * user ticked "remember my choice"), Intent otherwise. All pubkeys hex.
 */
class Signer(private val ctx: Context, val pkg: String, val userPub: String) {

    companion object {
        /** One launcher per activity; the pending call completes from MainActivity's result callback. */
        var launcher: ActivityResultLauncher<Intent>? = null
        private var pending: CompletableDeferred<ActivityResult>? = null
        fun onResult(r: ActivityResult) { pending?.complete(r); pending = null }

        fun installed(ctx: Context) = ctx.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")), 0).isNotEmpty()

        private suspend fun launch(i: Intent): ActivityResult {
            val d = CompletableDeferred<ActivityResult>()
            pending = d
            withContext(Dispatchers.Main) { (launcher ?: error("no activity")).launch(i) }
            return d.await()
        }

        /** get_public_key with pre-authorised permissions, so later calls can go through the Content Resolver. */
        suspend fun connect(ctx: Context): Signer {
            val perms = JSONArray()
                .put(JSONObject().put("type", "sign_event").put("kind", 13))      // NIP-59 seal of the sealed root
                .put(JSONObject().put("type", "nip44_encrypt"))
                .put(JSONObject().put("type", "nip44_decrypt"))
            val r = launch(Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
                .putExtra("type", "get_public_key").putExtra("permissions", perms.toString()))
            val d = r.data
            check(r.resultCode == Activity.RESULT_OK && d != null) { "signer failed (${r.resultCode})" }
            check(!d.getBooleanExtra("rejected", false)) { "rejected in the signer" }
            val res = d.getStringExtra("result") ?: error("no pubkey")
            val hex = if (res.startsWith("npub")) PublicKey.parse(res).toHex() else res
            return Signer(ctx, d.getStringExtra("package") ?: error("no package"), hex)
        }
    }

    /** → (result, event-or-null); throws on rejection. */
    private suspend fun call(type: String, payload: String, otherPub: String = ""): Pair<String, String?> {
        val viaResolver = withContext(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.query(Uri.parse("content://$pkg.${type.uppercase()}"),
                    arrayOf(payload, otherPub, userPub), null, null, null)?.use { c ->
                    if (c.getColumnIndex("rejected") > -1) error("rejected in the signer (remembered)")
                    if (!c.moveToFirst()) null
                    else c.getString(c.getColumnIndexOrThrow("result")) to
                        c.getColumnIndex("event").takeIf { it > -1 }?.let { c.getString(it) }
                }
            }.getOrElse { if (it.message?.startsWith("rejected") == true) throw it else null }
        }
        if (viaResolver != null) { calls += "$type:resolver"; return viaResolver }
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:$payload")).setPackage(pkg)
            .putExtra("type", type).putExtra("current_user", userPub)
        if (otherPub.isNotEmpty()) i.putExtra("pubkey", otherPub)
        val r = launch(i)
        val d = r.data
        check(r.resultCode == Activity.RESULT_OK && d != null) { "signer failed (${r.resultCode})" }
        check(!d.getBooleanExtra("rejected", false)) { "rejected in the signer" }
        calls += "$type:intent"
        return (d.getStringExtra("result") ?: error("no result")) to d.getStringExtra("event")
    }

    /** Which transport each call used (shown in the app as evidence). */
    val calls = mutableListOf<String>()

    suspend fun nip44Encrypt(plain: String, to: String) = call("nip44_encrypt", plain, to).first
    suspend fun nip44Decrypt(ct: String, from: String) = call("nip44_decrypt", ct, from).first
    suspend fun signEvent(unsigned: JSONObject): JSONObject {
        val (sig, ev) = call("sign_event", unsigned.toString())
        return if (ev != null) JSONObject(ev) else JSONObject(unsigned.toString()).put("sig", sig)
    }
}

/**
 * "Sealed root" for signer users: the Sukrara root can't be derived through a signer (no NIP-46/55 method returns
 * anything deterministic and secret), so a random root is stored as a NIP-59 gift wrap **to yourself** — on the
 * relays it's one more kind 1059 addressed to the main key, the same shape as a NIP-17 DM copy. Only the main key
 * (inside the signer) can open it, so the main key stays the one thing to keep.
 */
object SealedRoot {
    const val RUMOR = 30503

    suspend fun create(s: Signer, root: ByteArray): Event {
        val m = s.userPub
        val rumor = JSONObject().put("kind", RUMOR).put("pubkey", m).put("created_at", Nostr.now())
            .put("tags", JSONArray()).put("content", JSONObject().put("v", 1).put("type", "sukrara-root")
                .put("app", Derive.APP_ID).put("root", Derive.hex(root)).toString())
        val sealContent = s.nip44Encrypt(rumor.toString(), m)                          // seal: NIP-44 to self
        val seal = s.signEvent(JSONObject().put("kind", 13).put("pubkey", m)
            .put("created_at", Nostr.now() - Nostr.rng.nextInt(2 * 86400)).put("tags", JSONArray()).put("content", sealContent))
        check(Event.fromJson(seal.toString()).verify()) { "signer returned an invalid seal" }
        val eph = Nostr.keys(Nostr.random(32).let { Derive.subkey(it, "ephemeral") })   // wrap: one-time key
        val wrapContent = nip44Encrypt(eph.secretKey(), PublicKey.parse(m), seal.toString(), Nip44Version.V2)
        return EventBuilder(Kind(1059u), wrapContent).tags(listOf(Nostr.tag("p", m)))
            .customCreatedAt(Timestamp.fromSecs((Nostr.now() - Nostr.rng.nextInt(2 * 86400)).toULong())).finalize(eph)
    }

    /** Try every wrap addressed to the main key; the first that opens to our rumor wins. */
    suspend fun find(s: Signer, wraps: List<JSONObject>, log: (String) -> Unit): ByteArray? {
        for (w in wraps.sortedByDescending { it.getLong("created_at") }) {
            val root = runCatching {
                val seal = JSONObject(s.nip44Decrypt(w.getString("content"), w.getString("pubkey")))
                check(seal.getInt("kind") == 13 && seal.getString("pubkey") == s.userPub) { "seal not by the main key" }
                check(Event.fromJson(seal.toString()).verify()) { "bad seal signature" }
                val rumor = JSONObject(s.nip44Decrypt(seal.getString("content"), s.userPub))
                check(rumor.getString("pubkey") == s.userPub && rumor.getInt("kind") == RUMOR) { "not a sealed root" }
                val c = JSONObject(rumor.getString("content"))
                check(c.getString("app") == Derive.APP_ID) { "other app" }
                Derive.unhex(c.getString("root"))
            }.onFailure { log("wrap ${w.getString("id").take(8)}: ${it.message}") }.getOrNull()
            if (root != null) return root
        }
        return null
    }
}
