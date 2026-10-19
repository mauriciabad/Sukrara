// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.nightscout

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Result of one call, already classified for the queue. */
sealed interface Outcome {
    data class Ok(val body: String = "", val code: Int = 200) : Outcome
    /** Network, 5xx, 429, 408: try again later ([afterMs] from `Retry-After` when the server sent one). */
    data class Retry(val reason: String, val afterMs: Long? = null) : Outcome
    /** 401/403: stop everything until the user fixes the secret/token or its role. */
    data class AuthFailed(val reason: String) : Outcome
    /** Another 4xx: this document will never be accepted; drop it so it doesn't block the queue. */
    data class Rejected(val reason: String) : Outcome
}

/**
 * Thin Nightscout REST client. API v1 (`/api/v1/…`, arrays accepted, every server since 0.x) and API v3
 * (`/api/v3/…`, one document per request, identifier-based dedupe, Nightscout ≥ 14).
 *
 * Auth: [NsAuth.ApiSecret] → `api-secret: <sha1 hex>` (what xDrip+, Juggluco v1 and Trio send).
 * [NsAuth.AccessToken] → JWT from `/api/v2/authorization/request/<token>` as `Authorization: Bearer` (AAPS
 * `NSAuthInterceptor`, Juggluco v3), refreshed before `exp` and once after a 401.
 */
class NsClient(private val cfg: NsConfig, private val http: Http, private val now: () -> Long = System::currentTimeMillis) {
    private var jwt: String? = null
    private var jwtExpMs = 0L

    private fun authHeaders(forceRefresh: Boolean = false): Pair<Map<String, String>, Outcome?> = when (val a = cfg.auth) {
        is NsAuth.ApiSecret -> mapOf("api-secret" to NsDocs.sha1Hex(a.secret)) to null
        is NsAuth.AccessToken -> {
            if (forceRefresh || jwt == null || now() > jwtExpMs - 5 * 60_000) {
                val r = http.send(HttpRequest("GET", "${cfg.root}/api/v2/authorization/request/${enc(a.token)}"))
                when {
                    r.code == 200 -> runCatching {
                        val o = JSONObject(r.body)
                        jwt = o.getString("token")
                        jwtExpMs = o.optLong("exp", 0) * 1000
                    }
                    else -> jwt = null
                }
                if (jwt == null) return emptyMap<String, String>() to classify(r, "token exchange")
            }
            mapOf("Authorization" to "Bearer $jwt") to null
        }
    }

    /** One authenticated call; a 401 with a token retries once with a fresh JWT (it may just have expired). */
    fun call(method: String, path: String, body: String? = null): Pair<HttpResponse?, Outcome> {
        var (h, err) = authHeaders()
        if (err != null) return null to err
        var r = http.send(HttpRequest(method, cfg.root + path, h, body))
        if (r.code == 401 && cfg.auth is NsAuth.AccessToken) {
            val fresh = authHeaders(forceRefresh = true)
            if (fresh.second != null) return r to fresh.second!!
            h = fresh.first
            r = http.send(HttpRequest(method, cfg.root + path, h, body))
        }
        return r to classify(r, "$method $path")
    }

    // MARK: operations

    fun status(): Outcome = call("GET", if (cfg.api == NsApi.V3) "/api/v3/status" else "/api/v1/status.json").second

    /** v1: one POST for the whole batch (upsert on `sysTime`+`type`). v3: one POST per entry (dedupe on identifier). */
    fun postEntries(docs: List<JSONObject>): Outcome = postMany("entries", docs)

    fun postTreatments(docs: List<JSONObject>): Outcome = postMany("treatments", docs)

    /**
     * Create or replace one treatment by its identifier. v1 (Nightscout ≥ 15) upserts on `identifier`. v3's POST
     * dedupes on `identifier` too, but refuses to change `date` on the stored document (HTTP 400 "Field date cannot
     * be modified by the client", verified on 15.0.8), so a treatment moved in time is deleted and created again.
     */
    fun upsertTreatment(doc: JSONObject): Outcome {
        if (cfg.api == NsApi.V1) {
            // Nightscout 15 upserts v1 treatments on `identifier`; 14.x upserted on `created_at`+`eventType`, so a
            // moved treatment would be duplicated there. One lookup makes moves version-independent.
            val (r, o) = call("GET", "/api/v1/treatments.json?find[identifier]=${enc(doc.getString("identifier"))}&count=10")
            if (o !is Outcome.Ok) return o
            val old = runCatching { JSONArray(r!!.body) }.getOrNull() ?: return Outcome.Retry("unreadable treatments list")
            for (i in 0 until old.length()) {
                val e = old.getJSONObject(i)
                if (e.optString("created_at") != doc.getString("created_at")) {
                    val d = call("DELETE", "/api/v1/treatments/${enc(e.getString("_id"))}").second
                    if (d !is Outcome.Ok) return d
                }
            }
            return postMany("treatments", listOf(doc))
        }
        val (r, o) = call("POST", "/api/v3/treatments", doc.toString())
        if (o is Outcome.Rejected && r?.code == 400 && r.body.contains("cannot be modified")) {
            val d = deleteTreatment(doc.getString("identifier"))
            if (d !is Outcome.Ok) return d
            return call("POST", "/api/v3/treatments", doc.toString()).second
        }
        return o
    }

    fun postDeviceStatus(doc: JSONObject): Outcome = postMany("devicestatus", listOf(doc))

    private fun postMany(col: String, docs: List<JSONObject>): Outcome {
        if (docs.isEmpty()) return Outcome.Ok()
        if (cfg.api == NsApi.V1) return call("POST", "/api/v1/$col", JSONArray(docs).toString()).second
        lastRejected = 0
        for (d in docs) {
            val (r, o) = call("POST", "/api/v3/$col", d.toString())
            when {
                o is Outcome.Ok -> {}
                // the document is already stored and v3 won't let a dedupe change its immutable fields: it's there
                o is Outcome.Rejected && r?.body?.contains("cannot be modified") == true && col == "entries" -> {}
                o is Outcome.Rejected -> lastRejected++
                else -> return o
            }
        }
        return Outcome.Ok()
    }

    /** v3 only: documents of the last [postEntries]/[postTreatments] call the server refused (dropped, not retried). */
    var lastRejected = 0
        private set

    /**
     * Delete by our identifier. v3: `DELETE /api/v3/treatments/<id>?permanent=true` — a soft delete only sets
     * `isValid:false`, which v1 readers (LoopFollow, nightguard, the web UI) don't filter, so the carbs would stay
     * on their screens (verified, docs/25 §4). v1: look the `_id` up by identifier, then `DELETE /api/v1/treatments/<_id>`.
     */
    fun deleteTreatment(identifier: String): Outcome {
        if (cfg.api == NsApi.V3) {
            val (r, o) = call("DELETE", "/api/v3/treatments/${enc(identifier)}?permanent=true")
            return if (r?.code == 404 || r?.code == 410) Outcome.Ok(code = r.code) else o
        }
        val (r, o) = call("GET", "/api/v1/treatments.json?find[identifier]=${enc(identifier)}&count=10")
        if (o !is Outcome.Ok) return o
        val ids = runCatching { JSONArray(r!!.body) }.getOrNull()?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("_id") } }
            ?: return Outcome.Retry("unreadable treatments list")
        for (id in ids) {
            val d = call("DELETE", "/api/v1/treatments/${enc(id)}").second
            if (d !is Outcome.Ok) return d
        }
        return Outcome.Ok()
    }

    /** `date` (ms) → `device` of the sgv entries the server has in [fromMs, toMs]. */
    fun entriesBetween(fromMs: Long, toMs: Long, max: Int = 3000): Pair<Map<Long, String>?, Outcome> {
        val out = HashMap<Long, String>()
        if (cfg.api == NsApi.V1) {
            val (r, o) = call("GET", "/api/v1/entries/sgv.json?find[date][\$gte]=$fromMs&find[date][\$lte]=$toMs&count=$max")
            if (o !is Outcome.Ok) return null to o
            val arr = runCatching { JSONArray(r!!.body) }.getOrNull() ?: return null to Outcome.Retry("unreadable entries list")
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { out[it.optLong("date")] = it.optString("device", "") }
            return out to o
        }
        // v3 caps `limit` at API3_MAX_LIMIT = 1000 (lib/api3/const.json:6): page backwards by date, `fields` keeps it small
        var upper = toMs
        while (out.size < max) {
            val (r, o) = call("GET", "/api/v3/entries?type\$eq=sgv&date\$gte=$fromMs&date\$lte=$upper&fields=date,device&sort\$desc=date&limit=1000")
            if (o !is Outcome.Ok) return null to o
            val arr = runCatching { JSONObject(r!!.body).getJSONArray("result") }.getOrNull() ?: return null to Outcome.Retry("unreadable entries list")
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { out[it.optLong("date")] = it.optString("device", "") }
            if (arr.length() < 1000) break
            upper = arr.getJSONObject(arr.length() - 1).optLong("date") - 1
        }
        return out to Outcome.Ok()
    }

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        fun classify(r: HttpResponse, what: String): Outcome = when {
            r.code == 0 -> Outcome.Retry("$what: ${r.error}")
            r.code in 200..299 -> Outcome.Ok(r.body, r.code)
            r.code == 401 || r.code == 403 -> Outcome.AuthFailed("$what: HTTP ${r.code}")
            r.code == 408 || r.code == 429 || r.code >= 500 ->
                Outcome.Retry("$what: HTTP ${r.code}", r.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.trim()?.toLongOrNull()?.times(1000))
            else -> Outcome.Rejected("$what: HTTP ${r.code} ${r.body.take(200)}")
        }
    }
}
