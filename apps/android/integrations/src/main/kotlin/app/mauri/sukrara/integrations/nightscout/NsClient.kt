// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.nightscout

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

/** One call's result, already classified for the uploader. */
sealed interface Outcome {
    data class Ok(val body: String = "", val code: Int = 200) : Outcome

    /** Network, 5xx, 429, 408 or an unexpected redirect: try again later ([afterMs] from `Retry-After`). */
    data class Retry(val reason: String, val afterMs: Long? = null) : Outcome

    /** 401 / 403: stop until the person fixes the token / secret or its role. */
    data class AuthFailed(val reason: String) : Outcome

    /** Another 4xx: this document will never be accepted; drop it so it doesn't block the rest. */
    data class Rejected(val reason: String) : Outcome
}

/**
 * Thin Nightscout REST client (blocking: call on the IO dispatcher). API v1 (`/api/v1/…`, arrays accepted, every
 * server) and API v3 (`/api/v3/…`, one document per request, identifier dedupe, Nightscout ≥ 14).
 *
 * Auth: [NsAuth.ApiSecret] → `api-secret: <sha1 hex>`. [NsAuth.AccessToken] → a JWT from
 * `/api/v2/authorization/request/<token>` as `Authorization: Bearer`, refreshed before it expires and once after a
 * 401. Redirects are never followed (one could carry the secret to another host); reasons never contain the token.
 */
class NsClient(
    private val cfg: NsConfig,
    http: OkHttpClient,
    private val nowMs: () -> Long,
) {
    private val http =
        http
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    private var jwt: String? = null
    private var jwtExpMs = 0L

    private class Answer(val code: Int, val body: String, val retryAfter: String?, val error: String?)

    private fun send(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String?,
    ): Answer =
        try {
            val b =
                Request
                    .Builder()
                    .url(cfg.root + path)
                    .header("Accept", "application/json")
            headers.forEach { (k, v) -> b.header(k, v) }
            b.method(method, body?.toRequestBody(JSON))
            http.newCall(b.build()).execute().use { r -> Answer(r.code, r.body?.string().orEmpty(), r.header("Retry-After"), null) }
        } catch (e: IOException) {
            Answer(0, "", null, e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            Answer(0, "", null, "bad address")
        }

    private fun authHeaders(forceRefresh: Boolean = false): Pair<Map<String, String>, Outcome?> =
        when (val a = cfg.auth) {
            is NsAuth.ApiSecret -> {
                mapOf("api-secret" to NsDocs.sha1Hex(a.value)) to null
            }

            is NsAuth.AccessToken -> {
                if (forceRefresh || jwt == null || nowMs() > jwtExpMs - JWT_MARGIN_MS) {
                    val r = send("GET", "/api/v2/authorization/request/${enc(a.value)}", emptyMap(), null)
                    jwt = null
                    if (r.code == 200) {
                        runCatching {
                            val o = Json.parseToJsonElement(r.body).jsonObject
                            jwt = o["token"]?.jsonPrimitive?.contentOrNull
                            jwtExpMs = (o["exp"]?.jsonPrimitive?.longOrNull ?: 0) * 1000
                        }
                    }
                    if (jwt ==
                        null
                    ) {
                        return emptyMap<String, String>() to classify(r, "token exchange", treatOkAs = "unreadable token answer")
                    }
                }
                mapOf("Authorization" to "Bearer $jwt") to null
            }
        }

    /** One authenticated call; a 401 with a token retries once with a fresh JWT (it may just have expired). */
    private fun call(
        method: String,
        path: String,
        body: String? = null,
    ): Pair<Answer?, Outcome> {
        val (h, err) = authHeaders()
        if (err != null) return null to err
        var r = send(method, path, h, body)
        if (r.code == 401 && cfg.auth is NsAuth.AccessToken) {
            val (fresh, e) = authHeaders(forceRefresh = true)
            if (e != null) return r to e
            r = send(method, path, fresh, body)
        }
        return r to classify(r, "$method ${path.substringBefore('?')}")
    }

    // MARK: operations

    /** The site's version, or the failure (Test: read-only, nothing is written). */
    fun status(): Pair<String?, Outcome> {
        val (r, o) = call("GET", if (cfg.api == NsApi.V3) "/api/v3/status" else "/api/v1/status.json")
        if (o !is Outcome.Ok) return null to o
        val v =
            runCatching {
                val j = Json.parseToJsonElement(r!!.body).jsonObject
                ((j["result"] as? JsonObject)?.get("version") ?: j["version"])?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        return v to o
    }

    /** v1: one POST for the whole batch. v3: one POST per entry (dedupe on identifier). */
    fun postEntries(docs: List<JsonObject>): Outcome = postMany("entries", docs)

    fun postTreatments(docs: List<JsonObject>): Outcome = postMany("treatments", docs)

    fun postDeviceStatus(doc: JsonObject): Outcome = postMany("devicestatus", listOf(doc))

    /** v3 only: documents of the last [postEntries] / [postTreatments] call the server refused (dropped, not retried). */
    var lastRejected = 0
        private set

    private fun postMany(
        col: String,
        docs: List<JsonObject>,
    ): Outcome {
        lastRejected = 0
        if (docs.isEmpty()) return Outcome.Ok()
        if (cfg.api == NsApi.V1) return call("POST", "/api/v1/$col", JsonArray(docs).toString()).second
        for (d in docs) {
            val (r, o) = call("POST", "/api/v3/$col", d.toString())
            when {
                o is Outcome.Ok -> {}

                // the entry is already stored and v3 won't let a dedupe change its immutable fields: it's there
                o is Outcome.Rejected && col == "entries" && r?.body?.contains("cannot be modified") == true -> {}

                o is Outcome.Rejected -> {
                    lastRejected++
                }

                else -> {
                    return o
                }
            }
        }
        return Outcome.Ok()
    }

    /**
     * Create or replace one treatment by its identifier. v1: delete older copies with the same identifier but another
     * time, then POST (Nightscout 15 upserts on `identifier`, 14 on `created_at` + `eventType`). v3 refuses to change
     * `date`, so a moved treatment is deleted and created again.
     */
    fun upsertTreatment(doc: JsonObject): Outcome {
        val id = doc["identifier"]!!.jsonPrimitive.content
        if (cfg.api == NsApi.V1) {
            val (old, o) = treatmentsById(id)
            if (old == null) return o
            for (e in old) {
                // a moved entry, or one whose kind changed (14.x upserts on created_at + eventType: an old insulin
                // document would stay and count in IOB)
                val moved = e["created_at"]?.jsonPrimitive?.contentOrNull != doc["created_at"]?.jsonPrimitive?.contentOrNull
                val retyped = e["eventType"]?.jsonPrimitive?.contentOrNull != doc["eventType"]?.jsonPrimitive?.contentOrNull
                if (moved || retyped) {
                    val d = call("DELETE", "/api/v1/treatments/${enc(e["_id"]!!.jsonPrimitive.content)}").second
                    if (d !is Outcome.Ok) return d
                }
            }
            return postMany("treatments", listOf(doc))
        }
        val (r, o) = call("POST", "/api/v3/treatments", doc.toString())
        if (o is Outcome.Rejected && r?.code == 400 && r.body.contains("cannot be modified")) {
            val d = deleteTreatment(id)
            if (d !is Outcome.Ok) return d
            return call("POST", "/api/v3/treatments", doc.toString()).second
        }
        return o
    }

    /**
     * Delete by our identifier. v3: `DELETE …?permanent=true` (a soft delete only sets `isValid:false`, which v1 readers
     * don't filter). v1: look the `_id`s up by identifier, then delete each.
     */
    fun deleteTreatment(identifier: String): Outcome {
        if (cfg.api == NsApi.V3) {
            val (r, o) = call("DELETE", "/api/v3/treatments/${enc(identifier)}?permanent=true")
            return if (r?.code == 404 || r?.code == 410) Outcome.Ok(code = r.code) else o
        }
        val (old, o) = treatmentsById(identifier)
        if (old == null) return o
        for (e in old) {
            val d = call("DELETE", "/api/v1/treatments/${enc(e["_id"]!!.jsonPrimitive.content)}").second
            if (d !is Outcome.Ok) return d
        }
        return Outcome.Ok()
    }

    private fun treatmentsById(identifier: String): Pair<List<JsonObject>?, Outcome> {
        val (r, o) = call("GET", "/api/v1/treatments.json?find[identifier]=${enc(identifier)}&count=10")
        if (o !is Outcome.Ok) return null to o
        val list =
            runCatching {
                Json
                    .parseToJsonElement(r!!.body)
                    .jsonArray
                    .map { it.jsonObject }
                    .filter { it["_id"] != null }
            }.getOrNull()
                ?: return null to Outcome.Retry("unreadable treatments list")
        return list to o
    }

    /** `date` (ms) → `device` of the site's sgv entries in [fromMs, toMs]. */
    fun entriesBetween(
        fromMs: Long,
        toMs: Long,
        max: Int,
    ): Pair<Map<Long, String>?, Outcome> {
        val out = HashMap<Long, String>()
        if (cfg.api == NsApi.V1) {
            val (r, o) = call("GET", "/api/v1/entries/sgv.json?find[date][\$gte]=$fromMs&find[date][\$lte]=$toMs&count=$max")
            if (o !is Outcome.Ok) return null to o
            val arr =
                runCatching { Json.parseToJsonElement(r!!.body).jsonArray }.getOrNull()
                    ?: return null to Outcome.Retry("unreadable entries")
            for (e in arr) e.jsonObject.let { out[it.long("date")] = it.str("device") }
            return out to o
        }
        // v3 caps `limit` at 1000: page backwards by date, `fields` keeps it small
        var upper = toMs
        while (out.size < max) {
            val (r, o) =
                call("GET", "/api/v3/entries?type\$eq=sgv&date\$gte=$fromMs&date\$lte=$upper&fields=date,device&sort\$desc=date&limit=1000")
            if (o !is Outcome.Ok) return null to o
            val arr =
                runCatching { Json.parseToJsonElement(r!!.body).jsonObject["result"]!!.jsonArray }.getOrNull()
                    ?: return null to Outcome.Retry("unreadable entries")
            for (e in arr) e.jsonObject.let { out[it.long("date")] = it.str("device") }
            if (arr.size < 1000) break
            upper = arr.last().jsonObject.long("date") - 1
        }
        return out to Outcome.Ok()
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val JWT_MARGIN_MS = 5 * 60_000L

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        fun JsonObject.long(k: String) = this[k]?.jsonPrimitive?.longOrNull ?: 0L

        fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull.orEmpty()

        fun classify(
            r: Answer,
            what: String,
            treatOkAs: String? = null,
        ): Outcome =
            when {
                r.code == 0 -> {
                    Outcome.Retry("$what: ${r.error}")
                }

                r.code in 200..299 && treatOkAs != null -> {
                    Outcome.Retry("$what: $treatOkAs")
                }

                r.code in 200..299 -> {
                    Outcome.Ok(r.body, r.code)
                }

                r.code in 300..399 -> {
                    Outcome.Retry("$what: redirected (HTTP ${r.code}), check the address")
                }

                r.code == 401 || r.code == 403 -> {
                    Outcome.AuthFailed("$what: HTTP ${r.code}")
                }

                r.code == 408 || r.code == 429 || r.code >= 500 -> {
                    Outcome.Retry(
                        "$what: HTTP ${r.code}",
                        r.retryAfter
                            ?.trim()
                            ?.toLongOrNull()
                            ?.times(1000),
                    )
                }

                else -> {
                    Outcome.Rejected("$what: HTTP ${r.code}")
                }
            }
    }
}
