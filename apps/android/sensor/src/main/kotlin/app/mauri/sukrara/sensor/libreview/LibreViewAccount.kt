// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.libreview

import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.sensor.pairing.ReceiverIdState
import app.mauri.sukrara.sensor.pairing.ReceiverIds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What one LibreLinkUp login answer means. */
sealed interface LoginAnswer {
    data class Account(val accountId: String) : LoginAnswer

    /** Sign in again at the account's regional host (`api-<region>.libreview.io`). */
    data class Redirect(val region: String) : LoginAnswer

    data object BadCredentials : LoginAnswer

    /** The account must accept new terms in the LibreLinkUp / LibreView app first. */
    data object AcceptTerms : LoginAnswer

    data class Failed(val why: String) : LoginAnswer
}

/** The result of [LibreViewAccount.signIn]. */
sealed interface SignInResult {
    data class SignedIn(val receiverId: Long) : SignInResult

    data object BadCredentials : SignInResult

    data object AcceptTerms : SignInResult

    /** Network or server trouble; [why] is for diagnostics. Try again later. */
    data class Failed(val why: String) : SignInResult
}

/**
 * The LibreView account's receiver ID (CHANGES P1): what lets V1 start sensors that the showcase, Juggluco or Abbott's
 * apps on the same account can take over, and take over sensors they started.
 *
 * Read-only sign-in through LibreLinkUp's login (as the PoC): it answers with the same account ID as LibreView's app
 * login without making this phone the account's upload device (which would cut off another app's LibreView uploads).
 * Stores ONLY the receiver ID and the email (for "signed in as"); the password and the token are never stored — the
 * receiver ID never changes for an account, and uploads (chunk 26) need a different login anyway.
 */
class LibreViewAccount internal constructor(
    private val state: StateStore<ReceiverIdState>,
    private val http: OkHttpClient,
    private val io: CoroutineDispatcher,
    private val baseHost: String = HOST,
    private val scheme: String = "https",
) {
    constructor(state: StateStore<ReceiverIdState>, http: OkHttpClient, io: CoroutineDispatcher) :
        this(state, http, io, HOST, "https")

    companion object {
        const val HOST = "api.libreview.io"
        private val JSON = "application/json".toMediaType()

        /** Pure: one login answer (status 0 + 36-char user id, redirect, 2 = credentials, 4 = terms). */
        fun parse(body: String): LoginAnswer {
            val o = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return LoginAnswer.Failed("answer is not JSON")
            val status = o["status"]?.jsonPrimitive?.intOrNull
            val data = o["data"] as? JsonObject
            val region = data?.get("region")?.jsonPrimitive?.contentOrNull
            if (status == 0 && data?.get("redirect")?.jsonPrimitive?.contentOrNull == "true" && region != null) {
                return if (region.matches(Regex("[a-z]{2,3}"))) LoginAnswer.Redirect(region) else LoginAnswer.Failed("bad region")
            }
            val id = (data?.get("user") as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
            return when {
                status == 0 && id != null && id.length == 36 -> LoginAnswer.Account(id)
                status == 2 -> LoginAnswer.BadCredentials
                status == 4 -> LoginAnswer.AcceptTerms
                else -> LoginAnswer.Failed("status $status")
            }
        }
    }

    /** The signed-in email, or null. */
    val email: Flow<String?> = state.data.map { it.accountEmail }

    /** The signed-in account's receiver ID (shown in Settings › Sensor to compare with other apps), or null. */
    val receiverId: Flow<Long?> = state.data.map { it.account }

    suspend fun signIn(email: String, password: String): SignInResult = withContext(io) {
        val body = buildJsonObject {
            put("email", email.trim())
            put("password", password)
        }.toString()
        var host = baseHost
        repeat(2) {
            val answer = try {
                post("$scheme://$host/llu/auth/login", body)
            } catch (e: IOException) {
                return@withContext SignInResult.Failed("${e.javaClass.simpleName}")
            }
            when (answer) {
                is LoginAnswer.Redirect -> host = "api-${answer.region}.libreview.io"
                is LoginAnswer.Account -> {
                    val rid = ReceiverIds.fromAccountId(answer.accountId)
                    state.update { it.copy(account = rid, accountEmail = email.trim()) }
                    return@withContext SignInResult.SignedIn(rid)
                }
                LoginAnswer.BadCredentials -> return@withContext SignInResult.BadCredentials
                LoginAnswer.AcceptTerms -> return@withContext SignInResult.AcceptTerms
                is LoginAnswer.Failed -> return@withContext SignInResult.Failed(answer.why)
            }
        }
        SignInResult.Failed("LibreView kept redirecting")
    }

    /** Forgets the account's receiver ID and email. V1's own ID and a running sensor's pairing stay. */
    suspend fun signOut() = state.update { it.copy(account = null, accountEmail = null) }

    private fun post(url: String, body: String): LoginAnswer {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("product", "llu.android")
            .header("version", "4.16.0")
            .post(body.toRequestBody(JSON))
            .build()
        http.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
            .newCall(request).execute().use { resp ->
                if (resp.code == 401) return LoginAnswer.BadCredentials
                if (resp.code != 200) return LoginAnswer.Failed("HTTP ${resp.code}")
                return parse(resp.body?.string().orEmpty())
            }
    }
}
