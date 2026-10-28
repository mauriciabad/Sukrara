// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.tidepool

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** A Tidepool session (SecureStore `tidepool`, never anywhere else): the session token and the account's user id. */
@Serializable
data class TpSession(
    val token: String,
    val userId: String,
) {
    override fun toString() = "TpSession(***)"
}

/** One sign-in's answer. */
sealed interface TpLogin {
    data class SignedIn(val session: TpSession) : TpLogin

    data object BadCredentials : TpLogin

    /** Network or server trouble; [why] never holds a secret. */
    data class Failed(val why: String) : TpLogin
}

/** The session stopped working and couldn't be refreshed: the person has to sign in again. */
class TpSignedOut : Exception("signed out")

/** A refused or failed request ([code] 0 = no answer); the message never holds a secret. */
class TpFailure(
    val code: Int,
    why: String,
) : Exception(why)

/**
 * Tidepool's public API. Blocking: call on IO.
 *
 * Auth is Tidepool's own session login, `POST /auth/login` with the account's email and password (Basic), used once.
 * It answers an `X-Tidepool-Session-Token` that `GET /auth/login` refreshes; nothing else is kept, never the password.
 * (The OAuth flow Tidepool's own apps use needs a client id registered with Tidepool; Sukrara has none and doesn't
 * borrow another app's.) Every URL is https (http only to loopback, for tests); redirects are never followed.
 */
class TidepoolClient(
    http: OkHttpClient,
    private val version: String,
    baseUrl: String = API,
) {
    companion object {
        const val API = "https://api.tidepool.org"
        private const val TOKEN_HEADER = "X-Tidepool-Session-Token"
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
    }

    private val client =
        http
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    private val base: HttpUrl? = safe(baseUrl)

    /** The session requests use; a refresh replaces it (the caller saves the new one). */
    @Volatile var session: TpSession? = null

    /** https, or http to loopback only; no user info. */
    private fun safe(url: String): HttpUrl? {
        val u = url.trim().toHttpUrlOrNull() ?: return null
        if (u.username.isNotEmpty() || u.password.isNotEmpty()) return null
        val loopback = u.host == "127.0.0.1" || u.host == "localhost"
        return u.takeIf { it.scheme == "https" || loopback }
    }

    /** Signs in; the password goes into this one request only. */
    fun login(
        email: String,
        password: String,
    ): TpLogin {
        val basic = Base64.getEncoder().encodeToString("${email.trim()}:$password".toByteArray())
        val r =
            try {
                http("POST", "/auth/login", null, auth = "Basic $basic")
            } catch (e: IOException) {
                return TpLogin.Failed(e.javaClass.simpleName)
            }
        if (r.code == 401 || r.code == 403) return TpLogin.BadCredentials
        val userId =
            runCatching {
                json
                    .parseToJsonElement(r.body)
                    .jsonObject["userid"]
                    ?.jsonPrimitive
                    ?.contentOrNull
            }.getOrNull()
        if (r.code != 200 || r.token == null || userId.isNullOrEmpty()) return TpLogin.Failed("HTTP ${r.code}")
        return TpLogin.SignedIn(TpSession(r.token, userId).also { session = it })
    }

    /** Swaps the session token for a fresh one; [TpSignedOut] when that has expired too. */
    fun refresh(): TpSession {
        val s = session ?: throw TpSignedOut()
        val r = http("GET", "/auth/login", null, token = s.token)
        if (r.code == 401 || r.code == 403) throw TpSignedOut()
        if (r.code != 200 || r.token == null) throw TpFailure(r.code, "refresh: HTTP ${r.code}")
        return s.copy(token = r.token).also { session = it }
    }

    /** Ends the session on Tidepool's side as well (best effort). */
    fun logout(s: TpSession) {
        runCatching { http("POST", "/auth/logout", null, token = s.token).code }
    }

    // MARK: data sets

    /** Sukrara's upload records on the account (`client.name` filter). */
    fun dataSets(): JsonArray =
        parse(authed("GET", "/v1/users/${uid()}/data_sets?client.name=${TidepoolData.CLIENT_NAME}&size=100", null)) as? JsonArray
            ?: JsonArray(emptyList())

    /** Creates the upload record; returns its id. */
    fun createDataSet(body: JsonObject): String {
        val o = parse(authed("POST", "/v1/users/${uid()}/data_sets", body.toString())) as? JsonObject
        val d = (o?.get("data") as? JsonObject) ?: o
        return (d?.get("uploadId") ?: d?.get("id"))?.jsonPrimitive?.contentOrNull ?: throw TpFailure(0, "no data set id")
    }

    fun addData(
        dataSetId: String,
        data: List<JsonElement>,
    ) {
        if (data.isEmpty()) return
        authed("POST", "/v1/data_sets/$dataSetId/data", JsonArray(data).toString())
    }

    fun deleteData(
        dataSetId: String,
        originIds: List<String>,
    ) {
        if (originIds.isEmpty()) return
        authed("DELETE", "/v1/data_sets/$dataSetId/data", TidepoolData.selectors(originIds).toString())
    }

    // MARK: notes

    fun sendNote(body: JsonObject): String =
        (parse(authed("POST", "/message/send/${uid()}", body.toString())) as? JsonObject)
            ?.get("id")
            ?.jsonPrimitive
            ?.contentOrNull ?: throw TpFailure(0, "no note id")

    fun editNote(
        id: String,
        body: JsonObject,
    ) {
        authed("PUT", "/message/edit/$id", body.toString())
    }

    /** Removes a note; one that is gone already counts as removed. */
    fun removeNote(id: String) {
        authed("DELETE", "/message/remove/$id", null, okMissing = true)
    }

    // MARK: http

    private fun uid() = session?.userId ?: throw TpSignedOut()

    private fun parse(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()

    /** A request with the session token; one refresh on 401. */
    private fun authed(
        method: String,
        path: String,
        body: String?,
        okMissing: Boolean = false,
    ): String {
        var s = session ?: throw TpSignedOut()
        var r = http(method, path, body, token = s.token)
        if (r.code == 401) {
            s = refresh()
            r = http(method, path, body, token = s.token)
        }
        if (r.code == 401 || r.code == 403) throw TpSignedOut()
        if (okMissing && r.code == 404) return ""
        if (r.code !in 200..299) {
            val where = path.substringBefore('?').replace(s.userId, "{user}")
            throw TpFailure(r.code, "$method $where: HTTP ${r.code}")
        }
        return r.body
    }

    private class Reply(
        val code: Int,
        val body: String,
        val token: String?,
    )

    private fun http(
        method: String,
        path: String,
        body: String?,
        token: String? = null,
        auth: String? = null,
    ): Reply {
        val url = base?.let { safe(it.toString().trimEnd('/') + path) } ?: throw IOException("address")
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Sukrara/$version")
                .header("X-Tidepool-Client-Name", TidepoolData.CLIENT_NAME)
                .header("X-Tidepool-Client-Version", version)
                .apply {
                    token?.let { header(TOKEN_HEADER, it) }
                    auth?.let { header("Authorization", it) }
                }.method(
                    method,
                    body?.toRequestBody(JSON) ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody() else null,
                ).build()
        return client.newCall(request).execute().use { r ->
            Reply(r.code, r.body?.string().orEmpty(), r.header(TOKEN_HEADER))
        }
    }
}
