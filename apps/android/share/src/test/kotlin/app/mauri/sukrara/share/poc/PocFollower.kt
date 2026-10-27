// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.poc

import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.SensorInfo
import org.json.JSONObject
import org.nostrdevkit.sdk.Event
import org.nostrdevkit.sdk.Keys

/**
 * The showcase 0.11.53 Android follower's decisions (poc/spikes/showcase-android/…/follower/FollowerEngine.kt), without
 * its Android side effects (notifications, Room, widgets). The decision code is transcribed — FollowerEngine is bound
 * to Android — but every protocol step it takes (unwrap, NIP-44, tag derivation, parsing) is the 0.11.53 [Proto] itself,
 * compiled from poc/. Line numbers are FollowerEngine.kt's. [feed] answers a verdict like the sync fixtures:
 * `accepted:<what>`, `rejected:<why>` or `ignored:<why>`. [wouldReceive] = whether its relay filters deliver the event.
 */
class PocFollower(private val f: Keys, private val iPub: String) {
    class Epoch(val n: Int, val g: Keys) {
        val gk: String = Proto.gk(Proto.pub(g))
    }

    var sid: String? = null
    val epochs = mutableMapOf<String, Epoch>()
    var devices = listOf<String>()
    var writers = listOf<String>()
    var relays = listOf<String>()
    var rosterEpoch = 0
    var rosterCreatedAt = 0L
    var lastKeyAt = 0L
    var revoked = false
    var wearerName: String? = null
    val readings = sortedMapOf<Long, Pair<Int, Double?>>()
    var sensor: SensorInfo? = null
    var settingsU = 0L
    val alerts = mutableListOf<String>()
    val treatments = mutableMapOf<String, JSONObject>()

    /** gk of an epoch this follower doesn't hold, seen from the wearer: 0.11.53 sends a `rekey` (lines 536-539, 661-676). */
    val rekeyFor = mutableListOf<String>()

    fun feed(eventJson: String): String {
        val ev = JSONObject(eventJson)
        return when (ev.optInt("kind")) {
            Proto.WRAP -> onWrap(ev)
            Proto.LIVE, Proto.ALERT, Proto.CHUNK -> onData(ev)
            else -> "ignored:kind"
        }
    }

    // lines 443-516
    private fun onWrap(ev: JSONObject): String {
        val e = runCatching { Event.fromJson(ev.toString()) }.getOrNull() ?: return "ignored:malformed"
        if (!e.verify()) return "rejected:wrap-signature"
        val u = Proto.unwrap(f, e) ?: return "ignored:not-for-us"
        if (u.kind != Proto.RUMOR_TO_FOLLOWER || u.body.optInt("v") != 2) return "ignored:not-v2"
        if (u.sender != iPub) return "rejected:not-sealed-by-identity"
        return when (u.body.optString("type")) {
            "key" -> {
                val g = runCatching { Keys.parse(u.body.getString("g")) }.getOrNull() ?: return "rejected:bad-key"
                val n = u.body.optInt("epoch")
                val sid = u.body.optString("sid")
                if (this.sid != null && this.sid != sid) return "rejected:other-sid"
                epochs[Epoch(n, g).gk] = Epoch(n, g)
                this.sid = sid
                lastKeyAt = maxOf(lastKeyAt, u.createdAt)
                if (n > rosterEpoch) {
                    devices = Proto.strings(u.body.optJSONArray("devices"))
                    rosterEpoch = n
                    rosterCreatedAt = 0
                }
                Proto.strings(u.body.optJSONArray("relays")).takeIf { it.isNotEmpty() }?.let { relays = it }
                "accepted:key"
            }
            "revoked" -> {
                if (u.createdAt < lastKeyAt) return "ignored:revoked-before-key"
                revoked = true
                "accepted:revoked"
            }
            else -> "ignored:${u.body.optString("type")}"
        }
    }

    // lines 523-599
    private fun onData(ev: JSONObject): String {
        if (revoked) return "ignored:revoked"
        val kind = ev.optInt("kind")
        val author = ev.optString("pubkey")
        val sid = sid
        if (kind == Proto.CHUNK && sid != null && Proto.tagValue(ev, "c") == Proto.treatmentsC(sid)) return onTreatment(ev)
        val gkTag = Proto.tagValue(ev, "gk") ?: return "ignored:no-gk"
        val epoch = epochs[gkTag]
        if (epoch == null) {
            if ((author == iPub || author in devices) && ev.optLong("created_at") > lastKeyAt) rekeyFor += gkTag
            return "ignored:unknown-epoch"
        }
        val isRoster = kind == Proto.CHUNK && sid != null && Proto.tagValue(ev, "d") == Proto.rosterD(sid)
        val e =runCatching { Event.fromJson(ev.toString()) }.getOrNull() ?: return "rejected:malformed"
        if (!e.verify()) return "rejected:signature"
        if (isRoster) return onRoster(ev, e, epoch)
        if (author !in devices) return "rejected:author-not-in-roster"
        val body = runCatching { Proto.dec(epoch.g, author, ev.getString("content")) }.getOrNull() ?: return "rejected:decrypt"
        if (body.optInt("v") != 2) return "rejected:not-v2"
        if (kind == Proto.CHUNK && Proto.tagValue(ev, "d") == Proto.settingsD(sid!!)) {
            if (body.optString("type") != "settings") return "rejected:settings-type"
            if (body.optLong("u") <= settingsU) return "ignored:settings-older"
            settingsU = body.optLong("u")
            return "accepted:settings"
        }
        if (kind == Proto.ALERT) {
            if (body.optString("type") != "alert") return "rejected:alert-type"
            alerts += body.optString("k")
            return "accepted:alert"
        }
        if (kind == Proto.LIVE) {
            sensor = Proto.sensorOf(body)
            if (!body.has("sgv")) return "accepted:sensor"
            readings.putIfAbsent(body.getLong("t"), body.getInt("sgv") to if (body.isNull("r")) null else body.getDouble("r"))
            return "accepted:live"
        }
        val h = body.getLong("h")
        if (Proto.tagValue(ev, "d") != Proto.chunkD(sid!!, h)) return "rejected:chunk-d"
        val a = body.getJSONArray("r")
        for (i in 0 until a.length()) {
            val row = a.getJSONArray(i)
            readings.putIfAbsent(h + row.getLong(0), row.getInt(1) to if (row.isNull(2)) null else row.getInt(2) / 100.0)
        }
        return "accepted:chunk"
    }

    /**
     * Would 0.11.53's relay subscriptions deliver [eventJson] at all (subscribeData / subscribeTreatments, lines
     * 313-350; wraps by `#p`)? Authors, kinds and the `#d` / `#c` tags; the `since` windows are about timing, not format.
     */
    fun wouldReceive(eventJson: String): Boolean {
        val ev = JSONObject(eventJson)
        val kind = ev.optInt("kind")
        val author = ev.optString("pubkey")
        if (kind == Proto.WRAP) return Proto.tagValue(ev, "p") == Proto.pub(f)
        val sid = sid ?: return false
        return when {
            kind == Proto.LIVE || kind == Proto.ALERT -> author in devices
            kind != Proto.CHUNK -> false
            Proto.tagValue(ev, "d") == Proto.rosterD(sid) -> author == iPub
            Proto.tagValue(ev, "c") == Proto.chunkC(sid) -> author in devices
            Proto.tagValue(ev, "d") == Proto.settingsD(sid) -> author in devices
            Proto.tagValue(ev, "c") == Proto.treatmentsC(sid) -> author in devices || author in writers
            else -> false
        }
    }

    // lines 601-626
    private fun onRoster(ev: JSONObject, e: Event, epoch: Epoch): String {
        if (e.author().toHex() != iPub) return "rejected:roster-not-identity"
        val body = runCatching { Proto.dec(epoch.g, iPub, ev.getString("content")) }.getOrNull() ?: return "rejected:roster-decrypt"
        val n = body.optInt("epoch")
        val created = e.createdAt().asSecs().toLong()
        if (n < rosterEpoch || (n == rosterEpoch && created <= rosterCreatedAt)) return "ignored:roster-older"
        devices = Proto.strings(body.optJSONArray("devices"))
        writers = Proto.strings(body.optJSONArray("writers"))
        rosterEpoch = n
        rosterCreatedAt = created
        Proto.strings(body.optJSONArray("relays")).takeIf { it.isNotEmpty() }?.let { relays = it }
        body.optJSONObject("profile")?.let { wearerName = it.optString("name") }
        return "accepted:roster"
    }

    // lines 634-659 (the writer-scope merge is chunk 19's; here: who may write and that the body parses)
    private fun onTreatment(ev: JSONObject): String {
        val sid = sid ?: return "ignored:no-sid"
        val author = ev.optString("pubkey")
        val fromOwner = author in devices
        if (!fromOwner && author !in writers) return "rejected:treatment-author"
        val epoch = epochs[Proto.tagValue(ev, "gk") ?: return "ignored:no-gk"] ?: return "ignored:unknown-epoch"
        val e = runCatching { Event.fromJson(ev.toString()) }.getOrNull() ?: return "rejected:malformed"
        if (!e.verify()) return "rejected:signature"
        val body = runCatching { Proto.dec(epoch.g, author, ev.getString("content")) }.getOrNull() ?: return "rejected:decrypt"
        val id = body.optString("id")
        // Treatment.fromWire (treatments/Treatments.kt:52): k in KINDS, numeric a, u, the id the d tag names
        if (body.optString("k") !in setOf("rapid", "long", "carbs", "note") || !body.has("u")) return "rejected:treatment-body"
        if (Proto.tagValue(ev, "d") != Proto.treatmentD(sid, id)) return "rejected:treatment-d"
        treatments[id] = body
        return "accepted:treatment"
    }
}
