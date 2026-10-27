// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.nsupload

import java.net.URI

/** How the uploader proves it may write. */
sealed interface NsAuth {
    /** The site's API_SECRET (≥ 12 chars). Sent only as `api-secret: sha1(secret)`; API v1 only. Full admin rights. */
    data class ApiSecret(val secret: String) : NsAuth { override fun toString() = "ApiSecret(***)" }

    /**
     * An access token from Admin tools → Subjects (`name-0123456789abcdef`). Exchanged for a short-lived JWT at
     * `/api/v2/authorization/request/<token>`; the JWT goes in `Authorization: Bearer`. Works on v1 and v3 and can be
     * limited to the permissions the uploader needs (docs/25 §5).
     */
    data class AccessToken(val token: String) : NsAuth { override fun toString() = "AccessToken(***)" }
}

enum class NsApi { V1, V3 }

/**
 * @param baseUrl the site root, e.g. `https://my-ns.example`; a sub-path is kept (`https://host/ns`)
 * @param device the `device` field of every document (Nightscout shows it; dedupe on v3 hashes it)
 * @param relayPerson if non-null, readings of this followed person may be uploaded (explicit user choice); otherwise
 *   followed data never leaves the phone through this uploader
 */
data class NsConfig(
    val baseUrl: String,
    val auth: NsAuth,
    val api: NsApi = NsApi.V1,
    val device: String = "Sukrara",
    val relayPerson: String? = null,
    val uploadEntries: Boolean = true,
    val uploadTreatments: Boolean = true,
    val uploadDeviceStatus: Boolean = true,
    /** How far back a fresh setup (or a long outage) is filled. Juggluco: 30 days; Nightscout keeps 90 by default. */
    val maxBackfillMs: Long = 7 * DAY,
    /** How far back the periodic server diff looks for holes. */
    val reconcileWindowMs: Long = DAY,
    val reconcileEveryMs: Long = 6 * HOUR,
    /** Entries per v1 POST (Juggluco: up to 5000; xDrip+: 300 queue items). */
    val batchSize: Int = 500,
    /** v3 has no bulk insert: one request per document, capped per run so a backfill doesn't hog the radio. */
    val v3MaxPerRun: Int = 240,
) {
    init {
        val u = URI(baseUrl)
        val local = u.host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        require(u.scheme == "https" || (u.scheme == "http" && local)) {
            "Nightscout URL must be https:// (http:// only for localhost)"
        }
        require(api == NsApi.V1 || auth is NsAuth.AccessToken) { "API v3 needs an access token (it does not accept the API secret)" }
        if (auth is NsAuth.ApiSecret) require(auth.secret.length >= 12) { "Nightscout API secrets are at least 12 characters" }
    }

    val root: String get() = baseUrl.trimEnd('/')

    companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
