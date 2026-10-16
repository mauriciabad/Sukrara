// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.cgmshare.follower

import dev.spike.cgmshare.core.Proto
import org.json.JSONArray
import org.json.JSONObject
import org.nostrdevkit.sdk.Keys

class FollowerEpoch(val n: Int, val g: Keys) {
    val gk: String = Proto.gk(Proto.pub(g))
}

/** The follower's secrets and trust anchors, Keystore-encrypted in `follower-state.bin` (separate key alias). */
class FollowerState(
    val f: Keys,
    var name: String,
    val iPub: String,                       // the stream identity from the invite: the only key trusted for deliveries/roster
    val token: String,
    var relays: List<String>,
    val code: String,
    val requestedAt: Long,
    var status: String = REQUESTED,
    var sid: String? = null,
    val epochs: MutableMap<String, FollowerEpoch> = mutableMapOf(),   // gk → epoch
    var devices: List<String> = emptyList(),
    var rosterEpoch: Int = 0,
    var rosterCreatedAt: Long = 0,
    var lastKeyAt: Long = 0,                // rumor created_at of the newest key delivery
    var revokedAt: Long = 0,                // rumor created_at of the revoked notice
    var low: Int = 65,
    var high: Int = 185,
    var noDataMin: Int = 20,
    var alarmsOn: Boolean = true,
    var testUnfiltered: Boolean = false,    // debug: drop relay-side `authors` filters to exercise the app's own checks
    var writers: List<String> = emptyList(),  // investigation 07: roster `writers`
    var bleAddr: String? = null,              // investigation 07: owner's BLE address (nearby pairing)
) {
    val fPub: String get() = Proto.pub(f)
    val maxEpoch: Int get() = epochs.values.maxOfOrNull { it.n } ?: 0

    fun toJson(): JSONObject = JSONObject()
        .put("F", f.secretKey().toHex()).put("name", name).put("i", iPub).put("token", token)
        .put("relays", JSONArray(relays)).put("code", code).put("requestedAt", requestedAt).put("status", status)
        .put("sid", sid ?: JSONObject.NULL)
        .put("epochs", JSONArray(epochs.values.map { JSONObject().put("n", it.n).put("g", it.g.secretKey().toHex()) }))
        .put("devices", JSONArray(devices)).put("rosterEpoch", rosterEpoch).put("rosterCreatedAt", rosterCreatedAt)
        .put("lastKeyAt", lastKeyAt).put("revokedAt", revokedAt)
        .put("low", low).put("high", high).put("noDataMin", noDataMin).put("alarmsOn", alarmsOn)
        .put("testUnfiltered", testUnfiltered)
        .put("writers", JSONArray(writers)).put("bleAddr", bleAddr ?: JSONObject.NULL)

    companion object {
        const val REQUESTED = "requested"
        const val ACTIVE = "active"
        const val REVOKED = "revoked"

        fun fromJson(j: JSONObject) = FollowerState(
            f = Keys.parse(j.getString("F")),
            name = j.getString("name"),
            iPub = j.getString("i"),
            token = j.getString("token"),
            relays = Proto.strings(j.getJSONArray("relays")),
            code = j.getString("code"),
            requestedAt = j.getLong("requestedAt"),
            status = j.getString("status"),
            sid = if (j.isNull("sid")) null else j.getString("sid"),
            epochs = j.getJSONArray("epochs").let { a ->
                (0 until a.length()).map { FollowerEpoch(a.getJSONObject(it).getInt("n"), Keys.parse(a.getJSONObject(it).getString("g"))) }
                    .associateBy { it.gk }.toMutableMap()
            },
            devices = Proto.strings(j.getJSONArray("devices")),
            rosterEpoch = j.optInt("rosterEpoch"),
            rosterCreatedAt = j.optLong("rosterCreatedAt"),
            lastKeyAt = j.optLong("lastKeyAt"),
            revokedAt = j.optLong("revokedAt"),
            low = j.optInt("low", 65),
            high = j.optInt("high", 185),
            noDataMin = j.optInt("noDataMin", 20),
            alarmsOn = j.optBoolean("alarmsOn", true),
            testUnfiltered = j.optBoolean("testUnfiltered"),
            writers = Proto.strings(j.optJSONArray("writers")),
            bleAddr = if (j.has("bleAddr") && !j.isNull("bleAddr")) j.getString("bleAddr") else null,
        )
    }
}
