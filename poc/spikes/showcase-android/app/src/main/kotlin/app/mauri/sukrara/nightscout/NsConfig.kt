// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.nightscout

import java.net.URI

/** How the uploader proves it may write (docs/25 §4). */
sealed interface NsAuth {
    /** The site's API_SECRET (≥ 12 chars). Sent only as `api-secret: sha1(secret)`; API v1 only. Full admin rights. */
    data class ApiSecret(val secret: String) : NsAuth { override fun toString() = "ApiSecret(***)" }

    /**
     * An access token (Admin tools → Subjects, `name-0123456789abcdef`), exchanged for a short-lived JWT; v1 and v3,
     * limited to the role it was given. The recommended choice.
     */
    data class AccessToken(val token: String) : NsAuth { override fun toString() = "AccessToken(***)" }
}

enum class NsApi { V1, V3 }

/** A site the user typed. [problem] is null when it may be used; [insecure] when it's plain HTTP on the home network. */
data class NsSite(val root: String, val problem: String? = null, val insecure: Boolean = false) {
    companion object {
        /**
         * HTTPS everywhere; plain HTTP only to this phone (`localhost`, `127.x`, `::1`) or the home network: RFC 1918
         * (`10/8`, `172.16/12`, `192.168/16`), link-local (`169.254/16`, `fe80::/10`), unique local (`fc00::/7`) and
         * `.local` names. Decided on the literal host, never by resolving it (a public name can't sneak in through DNS).
         */
        fun parse(input: String): NsSite {
            val t = input.trim().trimEnd('/')
            val u = runCatching { URI(t) }.getOrNull() ?: return NsSite(t, "url")
            val host = u.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return NsSite(t, "url")
            if (u.userInfo != null || u.query != null || u.fragment != null) return NsSite(t, "url")
            return when (u.scheme?.lowercase()) {
                "https" -> NsSite(t)
                "http" -> if (isLoopback(host)) NsSite(t) else if (isHomeNetwork(host)) NsSite(t, insecure = true) else NsSite(t, "https")
                else -> NsSite(t, "url")
            }
        }

        fun isLoopback(h: String) = h == "localhost" || h == "::1" || ipv4(h)?.let { it[0] == 127 } == true

        fun isHomeNetwork(h: String): Boolean {
            if (h.endsWith(".local") && h.length > 6) return true
            ipv4(h)?.let { (a, b) -> return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) }
            if (':' in h) {
                val first = h.substringBefore(':').toIntOrNull(16) ?: return false
                return (first and 0xffc0) == 0xfe80 || (first and 0xfe00) == 0xfc00
            }
            return false
        }

        private fun ipv4(h: String): List<Int>? = h.split('.').takeIf { it.size == 4 }
            ?.map { p -> p.toIntOrNull()?.takeIf { it in 0..255 && p == it.toString() } ?: return null }
    }
}

/**
 * One upload configuration (docs/25 §4). Wearer only: there is no follower relay ([Nightscout] is started by the
 * wearer's service only).
 */
data class NsConfig(
    val site: NsSite,
    val auth: NsAuth,
    val api: NsApi = NsApi.V1,
    val device: String = "Sukrara",
) {
    init {
        require(site.problem == null) { "unusable Nightscout address" }
        require(api == NsApi.V1 || auth is NsAuth.AccessToken) { "API v3 needs an access token" }
    }

    val root: String get() = site.root
}
