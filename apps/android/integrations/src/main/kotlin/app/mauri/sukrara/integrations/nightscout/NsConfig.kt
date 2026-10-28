// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.nightscout

import java.net.URI

/** How the uploader proves it may write. Never printed: [toString] hides the value. */
sealed interface NsAuth {
    val value: String

    /** The site's API_SECRET. Sent only as `api-secret: sha1(secret)`; API v1 only. Full admin rights. */
    data class ApiSecret(override val value: String) : NsAuth {
        override fun toString() = "ApiSecret(***)"
    }

    /**
     * An access token (Admin tools → Subjects, `name-0123456789abcdef`), exchanged for a short-lived JWT; v1 and v3,
     * limited to the role it was given. The recommended choice.
     */
    data class AccessToken(override val value: String) : NsAuth {
        override fun toString() = "AccessToken(***)"
    }
}

enum class NsApi { V1, V3 }

/** Why a typed site can't be used. */
enum class NsSiteProblem {
    /** Not a web address (or it carries a user name, a query or a fragment). */
    Url,

    /** Plain `http://` to anything but this phone: the app sends only encrypted traffic. */
    Https,
}

/** A site the person typed: its [root] without a trailing `/`, and [problem] when it can't be used. */
data class NsSite(
    val root: String,
    val problem: NsSiteProblem? = null,
) {
    companion object {
        /**
         * `https://` anywhere; plain `http://` only to this phone (`localhost`, `127.x`, `::1`), which is all the network
         * security config lets through. Decided on the literal host, never by resolving it.
         */
        fun parse(input: String): NsSite {
            val t = input.trim().trimEnd('/')
            val u = runCatching { URI(t) }.getOrNull() ?: return NsSite(t, NsSiteProblem.Url)
            val host =
                u.host
                    ?.lowercase()
                    ?.removePrefix("[")
                    ?.removeSuffix("]") ?: return NsSite(t, NsSiteProblem.Url)
            if (u.userInfo != null || u.query != null || u.fragment != null) return NsSite(t, NsSiteProblem.Url)
            return when (u.scheme?.lowercase()) {
                "https" -> NsSite(t)
                "http" -> if (isLoopback(host)) NsSite(t) else NsSite(t, NsSiteProblem.Https)
                else -> NsSite(t, NsSiteProblem.Url)
            }
        }

        fun isLoopback(h: String) = h == "localhost" || h == "::1" || ipv4(h)?.let { it[0] == 127 } == true

        private fun ipv4(h: String): List<Int>? =
            h
                .split('.')
                .takeIf { it.size == 4 }
                ?.map { p -> p.toIntOrNull()?.takeIf { it in 0..255 && p == it.toString() } ?: return null }
    }
}

/** One upload configuration. [device] names this install on the site (`Sukrara-<id>`), so other uploaders stand apart. */
data class NsConfig(
    val site: NsSite,
    val auth: NsAuth,
    val api: NsApi,
    val device: String,
) {
    init {
        require(site.problem == null) { "unusable Nightscout address" }
        require(api == NsApi.V1 || auth is NsAuth.AccessToken) { "API v3 needs an access token" }
    }

    val root: String get() = site.root
}
