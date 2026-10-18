// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import android.content.Context
import app.mauri.sukrara.R
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The 32-bit "receiver ID" a Libre 3 is bound to when it is activated over NFC. Taking over a running sensor (NFC
 * again, from another phone or app) only works with that same ID; activating a new sensor accepts any.
 *
 * - [own]: random, made once per install. Used to start new sensors when no LibreView account is known.
 * - [libreView]: derived from the LibreView account ID, the way LibreLink-era apps and Juggluco derive it, so a sensor
 *   started by one of them can be taken over (and later handed back). Learned by [fetchLibreView].
 */
object ReceiverId {
    private const val PREFS = "direct"
    private const val OWN = "ownReceiverId"
    private const val LIBREVIEW = "libreViewReceiverId"

    fun own(ctx: Context): Long {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.contains(OWN)) return p.getLong(OWN, 0)
        // keep it clear of small IDs other apps use as defaults (Juggluco's manual fallback is 1001)
        val id = (SecureRandom().nextInt().toLong() and 0xffffffffL) or 0x10000000L
        p.edit().putLong(OWN, id).apply()
        return id
    }

    fun libreView(ctx: Context): Long? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { if (it.contains(LIBREVIEW)) it.getLong(LIBREVIEW, 0) else null }

    fun forgetLibreView(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(LIBREVIEW).apply()

    /** IDs to try for a takeover, most likely first. */
    fun takeoverCandidates(ctx: Context): List<Long> = listOfNotNull(libreView(ctx), own(ctx)).distinct()

    /** The ID a new sensor gets: the LibreView one when known, so LibreLink-compatible apps can take it over later. */
    fun forActivation(ctx: Context): Long = libreView(ctx) ?: own(ctx)

    /** LibreView account ID (a 36-character GUID) → receiver ID (Juggluco's getlibreaccountidnumber). */
    fun fromAccountId(accountId: String): Long {
        var h = 0L
        for (c in accountId.toByteArray(Charsets.UTF_8)) h = ((h * 0x811C9DC5L) xor (c.toLong() and 0xff)) and 0xffffffffL
        return h
    }

    class LoginFailure(message: String) : Exception(message)

    /**
     * Read-only sign-in to learn the LibreView account ID: LibreLinkUp's login answers with the same account ID that
     * LibreView's app login returns, without making this phone the account's upload device (that would cut off
     * Juggluco's or LibreLink's LibreView uploads). Nothing else is called; neither password nor token is kept.
     * Blocking: call from Dispatchers.IO. Stores and returns the receiver ID.
     */
    fun fetchLibreView(ctx: Context, email: String, password: String): Long {
        val body = buildJsonObject { put("email", email.trim()); put("password", password) }.toString()
        var host = "api.libreview.io"
        repeat(2) {
            val o = post(ctx, "https://$host/llu/auth/login", body)
            val status = o["status"]?.jsonPrimitive?.intOrNull
            val data = o["data"]?.jsonObject
            val region = data?.get("region")?.jsonPrimitive?.content
            if (status == 0 && data?.get("redirect")?.jsonPrimitive?.content == "true" && region != null) {
                host = "api-$region.libreview.io"; return@repeat
            }
            val id = data?.get("user")?.jsonObject?.get("id")?.jsonPrimitive?.content
            if (status == 0 && id != null && id.length == 36) {
                val rid = fromAccountId(id)
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(LIBREVIEW, rid).apply()
                return rid
            }
            throw LoginFailure(when (status) {
                2 -> ctx.getString(R.string.libreview_error_credentials)
                4 -> ctx.getString(R.string.libreview_error_terms)
                else -> o["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: ctx.getString(R.string.libreview_error_status, "$status")
            })
        }
        throw LoginFailure("LibreView kept redirecting")
    }

    private fun post(ctx: Context, url: String, body: String) = (URL(url).openConnection() as HttpURLConnection).run {
        requestMethod = "POST"
        connectTimeout = 10_000
        readTimeout = 20_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "application/json")
        setRequestProperty("product", "llu.android")
        setRequestProperty("version", "4.16.0")
        outputStream.use { it.write(body.toByteArray()) }
        val code = responseCode
        val text = (if (code < 400) inputStream else errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code == 401) throw LoginFailure(ctx.getString(R.string.libreview_error_credentials))
        if (code != 200) throw LoginFailure("LibreView sign-in: HTTP $code")
        Json.parseToJsonElement(text).jsonObject
    }
}
