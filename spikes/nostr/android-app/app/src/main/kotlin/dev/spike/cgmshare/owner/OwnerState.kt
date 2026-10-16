// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.cgmshare.owner

import dev.spike.cgmshare.core.Proto
import org.json.JSONArray
import org.json.JSONObject
import org.nostrdevkit.sdk.Keys

class Epoch(val n: Int, val g: Keys) {
    val pub: String = Proto.pub(g)
    val gk: String = Proto.gk(pub)
}

data class Follower(val f: String, val name: String, val since: Long, val revoked: Long? = null) {
    fun toJson(): JSONObject = JSONObject().put("f", f).put("name", name).put("since", since)
        .also { j -> revoked?.let { j.put("revoked", it) } }

    companion object {
        fun of(j: JSONObject) = Follower(j.getString("f"), j.optString("name", "?"), j.optLong("since"),
            if (j.has("revoked") && !j.isNull("revoked")) j.getLong("revoked") else null)
    }
}

data class InviteToken(val t: String, val x: Long)

data class PendingRequest(val f: String, val name: String, val token: String, val code: String, val at: Long)

/**
 * Everything the owner knows. Persisted locally in [dev.spike.cgmshare.core.SecureStore] (Keystore-encrypted,
 * includes I and D); the subset in [backupBody] goes into the owner backup event (encrypted to I, never contains D).
 */
class OwnerState(
    val i: Keys,
    var d: Keys,
    val sid: String,
    val epochs: MutableList<Epoch>,
    val followers: MutableList<Follower> = mutableListOf(),
    val invites: MutableList<InviteToken> = mutableListOf(),
    var relays: List<String> = Proto.RELAYS,
    var backupSavedAt: Long = 0,        // when the identity was handed to a password manager (Credential Manager)
    var backupPublishedAt: Long = 0,    // last owner-backup event
    var rosterPublishedAt: Long = 0,
    var synthetic: Boolean = false,
    val pending: MutableList<PendingRequest> = mutableListOf(),
) {
    val current: Epoch get() = epochs.last()
    val iPub: String get() = Proto.pub(i)
    val dPub: String get() = Proto.pub(d)

    fun active() = followers.filter { it.revoked == null }

    fun backupBody(): JSONObject = JSONObject()
        .put("v", 2)
        .put("sid", sid)
        .put("epochs", JSONArray(epochs.map { JSONObject().put("n", it.n).put("g", it.g.secretKey().toBech32()) }))
        .put("followers", JSONArray(followers.map { it.toJson() }))
        .put("invites", JSONArray(invites.map { JSONObject().put("t", it.t).put("x", it.x) }))
        .put("relays", JSONArray(relays))

    fun toJson(): JSONObject = backupBody()
        .put("I", i.secretKey().toHex())
        .put("D", d.secretKey().toHex())
        .put("backupSavedAt", backupSavedAt)
        .put("backupPublishedAt", backupPublishedAt)
        .put("rosterPublishedAt", rosterPublishedAt)
        .put("synthetic", synthetic)
        .put("pending", JSONArray(pending.map {
            JSONObject().put("f", it.f).put("name", it.name).put("token", it.token).put("code", it.code).put("at", it.at)
        }))

    companion object {
        fun create(synthetic: Boolean) =
            OwnerState(Keys.generate(), Keys.generate(), Proto.newSid(), mutableListOf(Epoch(1, Keys.generate())), synthetic = synthetic)

        private fun parseBody(i: Keys, d: Keys, j: JSONObject) = OwnerState(
            i = i,
            d = d,
            sid = j.getString("sid"),
            epochs = (0 until j.getJSONArray("epochs").length()).map {
                val e = j.getJSONArray("epochs").getJSONObject(it)
                Epoch(e.getInt("n"), Keys.parse(e.getString("g")))
            }.sortedBy { it.n }.toMutableList(),
            followers = j.optJSONArray("followers")?.let { a -> (0 until a.length()).map { Follower.of(a.getJSONObject(it)) } }
                ?.toMutableList() ?: mutableListOf(),
            invites = j.optJSONArray("invites")?.let { a ->
                (0 until a.length()).map { InviteToken(a.getJSONObject(it).getString("t"), a.getJSONObject(it).getLong("x")) }
            }?.toMutableList() ?: mutableListOf(),
            relays = Proto.strings(j.optJSONArray("relays")).ifEmpty { Proto.RELAYS },
        )

        fun fromJson(j: JSONObject): OwnerState = parseBody(Keys.parse(j.getString("I")), Keys.parse(j.getString("D")), j).apply {
            backupSavedAt = j.optLong("backupSavedAt")
            backupPublishedAt = j.optLong("backupPublishedAt")
            rosterPublishedAt = j.optLong("rosterPublishedAt")
            synthetic = j.optBoolean("synthetic")
            j.optJSONArray("pending")?.let { a ->
                for (k in 0 until a.length()) a.getJSONObject(k).let {
                    pending += PendingRequest(it.getString("f"), it.getString("name"), it.getString("token"), it.getString("code"), it.getLong("at"))
                }
            }
        }

        /** Phone change: everything from the owner backup + a brand-new device key D'. */
        fun fromBackup(i: Keys, body: JSONObject, synthetic: Boolean) =
            parseBody(i, Keys.generate(), body).apply { this.synthetic = synthetic; backupSavedAt = Proto.nowS() }
    }
}
