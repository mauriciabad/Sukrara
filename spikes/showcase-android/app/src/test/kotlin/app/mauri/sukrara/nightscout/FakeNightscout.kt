// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.nightscout

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * In-memory Nightscout with the dedupe rules of cgm-remote-monitor 15.0.8: v1 entries upsert on `sysTime`+`type`
 * (`lib/server/entries.js:246`), treatments on `identifier` (`lib/server/treatments.js:358`), v3 on `identifier`.
 * [legacyV1] = Nightscout 14.x: v1 treatments upsert on `created_at`+`eventType` instead.
 */
class FakeNightscout(
    private val secret: String = "spike-secret-0123",
    private val token: String = "sukrarapho-0123456789abcdef",
) : Http {
    val entries = LinkedHashMap<String, JSONObject>()      // sysTime|type → doc
    val treatments = LinkedHashMap<String, JSONObject>()   // _id → doc
    var legacyV1 = false
    fun treatment(identifier: String) = treatments.values.firstOrNull { it.optString("identifier") == identifier }
    val deviceStatus = ArrayList<JSONObject>()
    val requests = ArrayList<HttpRequest>()
    /** Next answers to force, e.g. 500, 0 (no network), 401; the request is still applied when [applyThenFail]. */
    val failures = ArrayDeque<Int>()
    var applyThenFail = false
    var retryAfterSec: Int? = null
    var jwtIssued = 0
    var jwtExpSec = 4_000_000_000L
    private var nextId = 1

    override fun send(req: HttpRequest): HttpResponse {
        requests += req
        val path = req.url.substringAfter("://").substringAfter('/').let { "/$it" }
        if (path.startsWith("/api/v2/authorization/request/")) {
            return if (URLDecoder.decode(path.substringAfterLast('/'), "UTF-8") == token) {
                jwtIssued++
                HttpResponse(200, JSONObject().put("token", "jwt$jwtIssued").put("exp", jwtExpSec).toString())
            } else HttpResponse(401, "{}")
        }
        val forced = failures.removeFirstOrNull()
        if (forced != null && !applyThenFail) return HttpResponse(forced, error = if (forced == 0) "ConnectException" else null,
            headers = retryAfterSec?.let { mapOf("Retry-After" to "$it") } ?: emptyMap())
        val authed = req.headers["api-secret"] == NsDocs.sha1Hex(secret) || req.headers["Authorization"]?.startsWith("Bearer jwt") == true
        if (!authed) return HttpResponse(401, "{\"status\":401}")
        val out = handle(req.method, path, req.body)
        if (forced != null) return HttpResponse(forced)
        return out
    }

    private fun handle(method: String, path: String, body: String?): HttpResponse {
        val p = path.substringBefore('?')
        val q = path.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        return when {
            method == "POST" && p == "/api/v1/entries" -> { arr(body).forEach { putEntry(it) }; HttpResponse(200, "[]") }
            method == "POST" && p == "/api/v3/entries" -> { val d = JSONObject(body); if (!d.has("app")) HttpResponse(400) else { putEntry(d); HttpResponse(201, "{}") } }
            method == "POST" && (p == "/api/v1/treatments" || p == "/api/v3/treatments") -> {
                val docs = if (p.contains("v1")) arr(body) else listOf(JSONObject(body))
                if (p.contains("v3")) treatment(docs[0].getString("identifier"))?.let { old ->
                    if (old.getLong("date") != docs[0].getLong("date")) return HttpResponse(400, "{\"status\":400,\"message\":\"Field date cannot be modified by the client\"}")
                }
                docs.forEach { d ->
                    val old = if (legacyV1 && p.contains("v1")) treatments.values.firstOrNull { it.getString("created_at") == d.getString("created_at") && it.getString("eventType") == d.getString("eventType") }
                        else treatment(d.getString("identifier"))
                    val id = old?.getString("_id") ?: "%024x".format(nextId++)
                    treatments[id] = d.put("_id", id)
                }
                HttpResponse(if (p.contains("v1")) 200 else 201, "{}")
            }
            method == "POST" && (p == "/api/v1/devicestatus" || p == "/api/v3/devicestatus") -> { deviceStatus += arr(body).ifEmpty { listOf(JSONObject(body)) }; HttpResponse(200, "{}") }
            method == "GET" && p == "/api/v1/treatments.json" ->
                HttpResponse(200, JSONArray(treatments.values.filter { it.optString("identifier") == q["find[identifier]"] }).toString())
            method == "DELETE" && p.startsWith("/api/v1/treatments/") -> {
                val id = p.substringAfterLast('/'); treatments.values.removeIf { it.getString("_id") == id }; HttpResponse(200, "{}")
            }
            method == "DELETE" && p.startsWith("/api/v3/treatments/") -> {
                val ident = URLDecoder.decode(p.substringAfterLast('/'), "UTF-8")
                if (q["permanent"] != "true") error("soft delete leaves the doc visible to v1 readers")
                val old = treatment(ident)
                if (old == null) HttpResponse(404) else { treatments.remove(old.getString("_id")); HttpResponse(200, "{}") }
            }
            method == "GET" && p == "/api/v1/entries/sgv.json" -> {
                val from = q["find[date][\$gte]"]!!.toLong(); val to = q["find[date][\$lte]"]!!.toLong()
                HttpResponse(200, JSONArray(entries.values.filter { it.getLong("date") in from..to }).toString())
            }
            method == "GET" && p == "/api/v3/entries" -> {
                val from = q["date\$gte"]!!.toLong(); val to = q["date\$lte"]!!.toLong(); val limit = q["limit"]!!.toInt()
                if (limit > 1000) return HttpResponse(400, "{\"message\":\"Parameter limit out of tolerance\"}")   // API3_MAX_LIMIT
                val page = entries.values.filter { it.getLong("date") in from..to }.sortedByDescending { it.getLong("date") }.take(limit)
                HttpResponse(200, JSONObject().put("status", 200).put("result", JSONArray(page)).toString())
            }
            else -> HttpResponse(404, "not in fake: $method $p")
        }
    }

    private fun putEntry(d: JSONObject) { entries[NsDocs.isoUtc(d.getLong("date")) + "|" + d.getString("type")] = d.put("sysTime", NsDocs.isoUtc(d.getLong("date"))) }

    private fun arr(body: String?): List<JSONObject> = runCatching { JSONArray(body) }.getOrNull()
        ?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

    fun posts(path: String) = requests.count { it.method == "POST" && it.url.endsWith(path) }
}
