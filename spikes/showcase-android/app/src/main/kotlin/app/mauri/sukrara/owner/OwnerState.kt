// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.owner

import app.mauri.sukrara.core.Avatar
import app.mauri.sukrara.core.Profile
import app.mauri.sukrara.core.Proto
import org.json.JSONArray
import org.json.JSONObject
import org.nostrdevkit.sdk.Keys

class Epoch(val n: Int, val g: Keys) {
    val pub: String = Proto.pub(g)
    val gk: String = Proto.gk(pub)
}

/** [avatar]: the follower's own generated picture (PROTOCOL-v2 "Profiles"), null if it never sent one. */
data class Follower(val f: String, val name: String, val since: Long, val revoked: Long? = null, val avatar: Avatar? = null) {
    fun toJson(): JSONObject = JSONObject().put("f", f).put("name", name).put("since", since)
        .put("avatar", avatar?.json ?: JSONObject.NULL)
        .also { j -> revoked?.let { j.put("revoked", it) } }

    companion object {
        fun of(j: JSONObject) = Follower(j.getString("f"), j.optString("name", "?"), j.optLong("since"),
            if (j.has("revoked") && !j.isNull("revoked")) j.getLong("revoked") else null, Avatar.of(j.optJSONObject("avatar")))
    }
}

data class InviteToken(val t: String, val x: Long)

data class PendingRequest(val f: String, val name: String, val token: String, val code: String, val at: Long,
                          val avatar: Avatar? = null)

/**
 * Everything the owner knows. Persisted locally in [app.mauri.sukrara.core.SecureStore] (Keystore-encrypted,
 * includes I and D); the subset in [backupBody] goes into the owner backup event (encrypted to I, never contains D).
 */
class OwnerState(
    val i: Keys,
    var d: Keys,
    val sid: String,
    val epochs: MutableList<Epoch>,
    val followers: MutableList<Follower> = mutableListOf(),
    val invites: MutableList<InviteToken> = mutableListOf(),
    val relays: List<String> = Proto.RELAYS,           // not stored: always the current default set
    var backupSavedAt: Long = 0,        // when the identity was handed to a password manager (Credential Manager)
    var backupPublishedAt: Long = 0,    // last owner-backup event
    var rosterPublishedAt: Long = 0,
    var treatmentsPublishedAt: Long = 0, // last full treatment republish (SPEC §5: weekly, rotation, new follower)
    var synthetic: Boolean = false,
    val pending: MutableList<PendingRequest> = mutableListOf(),
    val writers: MutableSet<String> = mutableSetOf(),     // investigation 07: followers allowed to add treatments
    var profile: Profile = Profile("", Avatar.random()),  // the wearer's name + picture, sent only to followers
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
        .put("writers", JSONArray(writers.toList()))
        .put("profile", profile.toJson())

    fun toJson(): JSONObject = backupBody()
        .put("I", i.secretKey().toHex())
        .put("D", d.secretKey().toHex())
        .put("backupSavedAt", backupSavedAt)
        .put("backupPublishedAt", backupPublishedAt)
        .put("rosterPublishedAt", rosterPublishedAt)
        .put("treatmentsPublishedAt", treatmentsPublishedAt)
        .put("synthetic", synthetic)
        .put("pending", JSONArray(pending.map {
            JSONObject().put("f", it.f).put("name", it.name).put("token", it.token).put("code", it.code).put("at", it.at)
                .put("avatar", it.avatar?.json ?: JSONObject.NULL)
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
            writers = Proto.strings(j.optJSONArray("writers")).toMutableSet(),
            profile = Profile.of(j.optJSONObject("profile"))?.let { if (it.avatar == null) it.copy(avatar = Avatar.random()) else it }
                ?: Profile("", Avatar.random()),
        )

        fun fromJson(j: JSONObject): OwnerState = parseBody(Keys.parse(j.getString("I")), Keys.parse(j.getString("D")), j).apply {
            backupSavedAt = j.optLong("backupSavedAt")
            backupPublishedAt = j.optLong("backupPublishedAt")
            rosterPublishedAt = j.optLong("rosterPublishedAt")
            treatmentsPublishedAt = j.optLong("treatmentsPublishedAt")
            synthetic = j.optBoolean("synthetic")
            j.optJSONArray("pending")?.let { a ->
                for (k in 0 until a.length()) a.getJSONObject(k).let {
                    pending += PendingRequest(it.getString("f"), it.getString("name"), it.getString("token"), it.getString("code"),
                        it.getLong("at"), Avatar.of(it.optJSONObject("avatar")))
                }
            }
        }

        /** Phone change: everything from the owner backup + a brand-new device key D'. */
        fun fromBackup(i: Keys, body: JSONObject, synthetic: Boolean) =
            parseBody(i, Keys.generate(), body).apply { this.synthetic = synthetic; backupSavedAt = Proto.nowS() }
    }
}
