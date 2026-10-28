// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.libreview

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * What a LibreView app sign-in leaves behind (SecureStore `libreview`, never anywhere else): the token, where to send
 * it and the person's fields the sensor registration repeats. Never the password.
 */
@Serializable
data class LvSession(
    val token: String,
    val baseUrl: String,
    val apiKey: String,
    val dateOfBirth: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val guardianFirstName: String? = null,
    val guardianLastName: String? = null,
) {
    /** The sensor registration repeats the profile: only send it when sign-in returned it (never blank it). */
    val hasProfile: Boolean get() = dateOfBirth != null && firstName != null && lastName != null

    override fun toString() = "LvSession(***)"
}

/** One sign-in's answer. */
sealed interface LvAuth {
    data class SignedIn(val session: LvSession) : LvAuth

    /** Another device (LibreLink, Juggluco, another phone) uploads for this account: only a take-over gets past it. */
    data object OtherDevice : LvAuth

    data object BadCredentials : LvAuth

    /** Network or server trouble; [why] never holds a secret. */
    data class Failed(val why: String) : LvAuth
}

/** One upload's answer. */
sealed interface LvPost {
    data object Ok : LvPost

    /** The token no longer works (another device took over, or it expired): sign in again. */
    data object SignInAgain : LvPost

    /** Try again later (network, 5xx, 408, 429). */
    data class Retry(val why: String) : LvPost

    /** LibreView refused it ([why] = its status and reason). */
    data class Rejected(val why: String) : LvPost
}

/**
 * LibreView's app API, as the FreeStyle Libre 3 app uses it (contracts/treatments/SPEC.md §3). Blocking: call on IO.
 * Every URL is https (the config's too); redirects are never followed, so the token can't be carried to another host.
 */
class LibreViewClient internal constructor(
    http: OkHttpClient,
    private val assetsUrl: String,
    /** Tests only: MockWebServer on loopback speaks http. */
    private val allowLoopbackHttp: Boolean,
) {
    constructor(http: OkHttpClient) : this(http, ASSETS, false)

    companion object {
        const val ASSETS = "https://fsll3.freestyleserver.com/Payloads/Mobile/FSLibre3/Android/Assets/3.3.0/DE.json"
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
    }

    private val client =
        http
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    /** The URL if it's one we may send to (https, no user info), else null. */
    internal fun safe(url: String): HttpUrl? {
        val u = url.trim().toHttpUrlOrNull() ?: return null
        if (u.username.isNotEmpty() || u.password.isNotEmpty()) return null
        val loopback = allowLoopbackHttp && (u.host == "127.0.0.1" || u.host == "localhost")
        return u.takeIf { it.scheme == "https" || loopback }
    }

    /**
     * Signs in with the LibreView app login. [takeOver] true makes this phone the account's uploading device (the
     * other device stops uploading): only after the person chose it. The password goes into this one request only.
     */
    fun signIn(
        email: String,
        password: String,
        deviceId: String,
        takeOver: Boolean,
    ): LvAuth {
        val (base, apiKey) =
            try {
                config()
            } catch (e: IOException) {
                return LvAuth.Failed(e.message ?: e.javaClass.simpleName)
            }
        val body =
            buildJsonObject {
                put("Culture", LibreViewPayload.CULTURE)
                put("DeviceId", deviceId)
                put("Password", password)
                put("SetDevice", takeOver)
                put("UserName", email.trim())
                put("Domain", "Libreview")
                put("GatewayType", LibreViewPayload.GATEWAY)
            }.toString()
        val (code, o) =
            try {
                call("POST", base, "/api/nisperson/getauthentication", body, apiKey, token = "")
            } catch (e: IOException) {
                return LvAuth.Failed(e.javaClass.simpleName)
            }
        if (code == 401 || code == 403) return LvAuth.BadCredentials
        if (code != 200 || o == null) return LvAuth.Failed("HTTP $code")
        val status = o["status"]?.jsonPrimitive?.intOrNull
        val reason = o["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (status == 20 && "wrongDeviceForUser" in reason) return LvAuth.OtherDevice
        if (status == 2) return LvAuth.BadCredentials
        val r = (o["result"] as? JsonObject)
        val token = r?.get("UserToken")?.jsonPrimitive?.contentOrNull
        if (status != 0 || token.isNullOrEmpty()) return LvAuth.Failed("status $status")

        fun field(k: String) = r[k]?.jsonPrimitive?.contentOrNull
        return LvAuth.SignedIn(
            LvSession(
                token,
                base.toString().trimEnd('/'),
                apiKey,
                field("DateOfBirth"),
                field("FirstName"),
                field("LastName"),
                field("GuardianFirstName"),
                field("GuardianLastName"),
            ),
        )
    }

    /** `POST /api/measurements` with [body] (a [LibreViewPayload.payload]). */
    fun postMeasurements(
        s: LvSession,
        body: String,
    ): LvPost = post("POST", "/api/measurements", s, body)

    /** Tells the account which sensor is active (before that sensor's first glucose). */
    fun putSensor(
        s: LvSession,
        serial: String,
    ): LvPost {
        if (!s.hasProfile) return LvPost.Rejected("no profile")
        val body =
            buildJsonObject {
                put("DateOfBirth", s.dateOfBirth)
                put("DomainData", "{\"activeSensor\":\"${serial.takeLast(9)}\"}")
                put("FirstName", s.firstName)
                put("GuardianFirstName", s.guardianFirstName.orEmpty())
                put("GuardianLastName", s.guardianLastName.orEmpty())
                put("LastName", s.lastName)
                put("UserToken", s.token)
                put("Domain", "Libreview")
                put("GatewayType", LibreViewPayload.GATEWAY)
            }.toString()
        return post("PUT", "/api/nisperson", s, body)
    }

    private fun post(
        method: String,
        path: String,
        s: LvSession,
        body: String,
    ): LvPost {
        val base = safe(s.baseUrl) ?: return LvPost.SignInAgain
        val (code, o) =
            try {
                call(method, base, path, body, s.apiKey, s.token)
            } catch (e: IOException) {
                return LvPost.Retry(e.javaClass.simpleName)
            }
        return when {
            code == 401 || code == 403 -> {
                LvPost.SignInAgain
            }

            code == 408 || code == 425 || code == 429 || code >= 500 -> {
                LvPost.Retry("HTTP $code")
            }

            code != 200 || o == null -> {
                LvPost.Rejected("HTTP $code")
            }

            else -> {
                val status = o["status"]?.jsonPrimitive?.intOrNull ?: -1
                val reason = o["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
                when {
                    status == 0 -> LvPost.Ok
                    status == 20 && "wrongDevice" in reason -> LvPost.SignInAgain
                    else -> LvPost.Rejected("$status ${reason.take(80)}".trim())
                }
            }
        }
    }

    /** Abbott's app config: the API's address and key (https only). */
    private fun config(): Pair<HttpUrl, String> {
        val assets = safe(assetsUrl) ?: throw IOException("config address")
        val cfgUrl =
            jsonOf(get(assets))["Configuration"]?.jsonPrimitive?.contentOrNull?.let(::safe)
                ?: throw IOException("config address")
        val cfg = jsonOf(get(cfgUrl))
        val base = cfg["newYuUrl"]?.jsonPrimitive?.contentOrNull?.let(::safe) ?: throw IOException("config address")
        val key = cfg["newYuApiKey"]?.jsonPrimitive?.contentOrNull ?: throw IOException("config key")
        return base to key
    }

    private fun jsonOf(text: String): JsonObject =
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw IOException("config is not JSON")

    private fun get(url: HttpUrl): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.code != 200) throw IOException("config HTTP ${r.code}")
            r.body?.string().orEmpty()
        }

    private fun call(
        method: String,
        base: HttpUrl,
        path: String,
        body: String,
        apiKey: String,
        token: String,
    ): Pair<Int, JsonObject?> {
        val url = safe(base.toString().trimEnd('/') + path) ?: throw IOException("address")
        val request =
            Request
                .Builder()
                .url(url)
                .header("Platform", "Android")
                .header("Version", "3.3.0")
                .header("Abbott-ADC-App-Platform", "Android/16/FSL3/3.3.0.9092")
                .header("Accept-Language", LibreViewPayload.CULTURE)
                .header("x-api-key", apiKey)
                .header("x-newyu-token", token)
                .method(method, body.toRequestBody(JSON))
                .build()
        return client.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            r.code to runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        }
    }
}
