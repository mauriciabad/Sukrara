// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.tidepool

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tidepool's public API (docs/27), blocking (call off the main thread), plain JVM so the end-to-end harness uses it too.
 *
 * Auth is Tidepool's own session login, `POST /auth/login` with the account's email and password (Basic). It answers
 * an `X-Tidepool-Session-Token` (a Keycloak token pair packed by Tidepool) that `GET /auth/login` refreshes; nothing
 * else is stored, never the password. The OAuth flow Tidepool's own apps, Loop, xDrip+ and AAPS use needs a client id
 * registered with Tidepool (a partner agreement); Sukrara has none and doesn't borrow another app's.
 */
class TidepoolClient(private val version: String, private val api: String = "https://api.tidepool.org") {
    /** The token stopped working and couldn't be refreshed: the user has to sign in again. */
    class SignedOut(message: String) : Exception(message)
    class Failure(message: String, val code: Int) : Exception(message)

    data class Session(val token: String, val userId: String)

    private class Reply(val code: Int, val body: String, val token: String?)

    @Volatile var session: Session? = null
        private set
    /** Called when a refresh replaced the token, so the caller can store the new one. */
    var onToken: ((Session) -> Unit)? = null

    fun restore(s: Session?) { session = s }

    fun login(email: String, password: String): Session {
        val basic = Base64.getEncoder().encodeToString("${email.trim()}:$password".toByteArray())
        val r = http("POST", "/auth/login", null, auth = "Basic $basic")
        if (r.code == 401 || r.code == 403) throw SignedOut(reason(r))
        if (r.code != 200 || r.token == null) throw Failure("login: HTTP ${r.code} ${reason(r)}", r.code)
        val userId = Json.parseToJsonElement(r.body).jsonObject["userid"]!!.jsonPrimitive.content
        return Session(r.token, userId).also { session = it }
    }

    /** Swaps the session token for a fresh one (the packed refresh token); [SignedOut] when that has expired too. */
    fun refresh(): Session {
        val s = session ?: throw SignedOut("not signed in")
        val r = http("GET", "/auth/login", null, token = s.token)
        if (r.code == 401 || r.code == 403) throw SignedOut("session expired")
        if (r.code != 200 || r.token == null) throw Failure("refresh: HTTP ${r.code}", r.code)
        return s.copy(token = r.token).also { session = it; onToken?.invoke(it) }
    }

    /** Drops the session on Tidepool's side as well (best effort). */
    fun logout() {
        session?.let { runCatching { http("POST", "/auth/logout", null, token = it.token) } }
        session = null
    }

    // ============================================================================================ data sets

    /** Sukrara's open upload records on the account (`client.name` filter), newest first. */
    fun dataSets(): JsonArray = authed("GET", "/v1/users/${uid()}/data_sets?client.name=${enc(TidepoolData.CLIENT_NAME)}&size=100", null)
        .let { Json.parseToJsonElement(it).jsonArray }

    /** Creates the upload record; returns its id. */
    fun createDataSet(body: JsonObject): String {
        val o = Json.parseToJsonElement(authed("POST", "/v1/users/${uid()}/data_sets", body.toString())).jsonObject
        val d = (o["data"] as? JsonObject) ?: o
        return (d["uploadId"] ?: d["id"])!!.jsonPrimitive.content
    }

    fun addData(dataSetId: String, data: List<JsonElement>) {
        if (data.isEmpty()) return
        authed("POST", "/v1/data_sets/$dataSetId/data", JsonArray(data).toString())
    }

    fun deleteData(dataSetId: String, originIds: List<String>) {
        if (originIds.isEmpty()) return
        authed("DELETE", "/v1/data_sets/$dataSetId/data", TidepoolData.selectors(originIds).toString())
    }

    /** Deletes a whole upload record and its data. */
    fun deleteDataSet(dataSetId: String) { authed("DELETE", "/v1/datasets/$dataSetId", null) }

    /** Data of the account (`GET /data/{userId}`), e.g. one upload record's: `uploadId=…`. */
    fun data(query: String): JsonArray = Json.parseToJsonElement(authed("GET", "/data/${uid()}?$query", null)).jsonArray

    // ============================================================================================ notes

    fun sendNote(body: JsonObject): String =
        Json.parseToJsonElement(authed("POST", "/message/send/${uid()}", body.toString())).jsonObject["id"]!!.jsonPrimitive.content

    fun editNote(id: String, body: JsonObject) { authed("PUT", "/message/edit/$id", body.toString()) }

    fun removeNote(id: String) { authed("DELETE", "/message/remove/$id", null, okMissing = true) }

    /** The notes in [startIso, endIso]. */
    fun notes(startIso: String, endIso: String): JsonArray {
        val text = authed("GET", "/message/notes/${uid()}?starttime=${enc(startIso)}&endtime=${enc(endIso)}", null, okMissing = true)
        if (text.isBlank()) return JsonArray(emptyList())
        val e = Json.parseToJsonElement(text)
        return (e as? JsonObject)?.get("messages")?.jsonArray ?: (e as? JsonArray) ?: JsonArray(emptyList())
    }

    // ============================================================================================ http

    private fun uid() = session?.userId ?: throw SignedOut("not signed in")

    /** A request with the session token; one refresh on 401. */
    private fun authed(method: String, path: String, body: String?, okMissing: Boolean = false): String {
        var s = session ?: throw SignedOut("not signed in")
        var r = http(method, path, body, token = s.token)
        if (r.code == 401) { s = refresh(); r = http(method, path, body, token = s.token) }
        if (r.code == 401) throw SignedOut("not authorized")
        if (okMissing && r.code == 404) return ""
        if (r.code !in 200..299) throw Failure("$method ${path.substringBefore('?').replace(s.userId, "{user}")} → HTTP ${r.code} ${reason(r)}", r.code)
        return r.body
    }

    private fun reason(r: Reply): String = runCatching {
        val o = Json.parseToJsonElement(r.body).jsonObject
        (o["reason"] ?: o["message"] ?: o["title"])?.jsonPrimitive?.content
    }.getOrNull() ?: r.body.take(200)

    private fun http(method: String, path: String, body: String?, token: String? = null, auth: String? = null): Reply {
        val c = (URL(api + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Sukrara/$version")
            setRequestProperty("X-Tidepool-Client-Name", TidepoolData.CLIENT_NAME)
            setRequestProperty("X-Tidepool-Client-Version", version)
            token?.let { setRequestProperty("X-Tidepool-Session-Token", it) }
            auth?.let { setRequestProperty("Authorization", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Reply(code, text, c.getHeaderField("X-Tidepool-Session-Token"))
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
