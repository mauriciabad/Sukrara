// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One file of a release ("Sukrara on other devices": the Mac dmg, the APK, …). */
data class ReleaseAsset(val name: String, val url: String)

/** One GitHub release of mauriciabad/sukrara. */
data class Release(
    val tag: String,
    val url: String,
    val prerelease: Boolean,
    val draft: Boolean,
    val assets: List<ReleaseAsset>,
)

/** What the About page shows about updates. */
sealed interface UpdateState {
    data object Checking : UpdateState

    /** No newer release; [latest] = the newest one offered (for its other-device files), if any. */
    data class UpToDate(val latest: Release?) : UpdateState

    data class Available(val release: Release) : UpdateState

    /** Offline or refused: the page links to the releases page instead. */
    data object Offline : UpdateState
}

object UpdateCheck {
    const val REPO_URL = "https://github.com/mauriciabad/sukrara"
    const val RELEASES_URL = "$REPO_URL/releases"
    const val ISSUES_URL = "$REPO_URL/issues"

    /** All releases (not `/latest`, which never returns a prerelease). */
    const val API_URL = "https://api.github.com/repos/mauriciabad/sukrara/releases"

    /**
     * The release to offer: the newest non-draft one newer than [installed]; prereleases only when the installed
     * version is a prerelease itself (major 0). Null = up to date.
     */
    fun newer(
        releases: List<Release>,
        installed: String,
        prerelease: Boolean,
    ): Release? = offered(releases, prerelease).filter { compare(it.tag, installed) > 0 }.maxWithOrNull { a, b -> compare(a.tag, b.tag) }

    /** The newest release this install may be offered (its files are listed as "Sukrara on other devices"). */
    fun latest(
        releases: List<Release>,
        prerelease: Boolean,
    ): Release? = offered(releases, prerelease).maxWithOrNull { a, b -> compare(a.tag, b.tag) }

    fun state(
        releases: Result<List<Release>>,
        installed: String,
        prerelease: Boolean,
    ): UpdateState {
        val list = releases.getOrNull() ?: return UpdateState.Offline
        return newer(list, installed, prerelease)?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate(latest(list, prerelease))
    }

    private fun offered(
        releases: List<Release>,
        prerelease: Boolean,
    ) = releases.filter { !it.draft && (prerelease || !it.prerelease) }

    /**
     * Compares two versions ("v0.12.1", "0.12.0-rc2") number by number; a version with a pre-release suffix
     * ("-rc1") is older than the same numbers without one.
     */
    fun compare(
        a: String,
        b: String,
    ): Int {
        fun numbers(v: String) =
            v
                .removePrefix("v")
                .substringBefore('-')
                .split('.')
                .map { it.toIntOrNull() ?: 0 }
        val x = numbers(a)
        val y = numbers(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        val sa = a.substringAfter('-', "")
        val sb = b.substringAfter('-', "")
        return when {
            sa.isEmpty() && sb.isEmpty() -> 0

            sa.isEmpty() -> 1

            sb.isEmpty() -> -1

            // "rc2" > "rc1": the suffix's number decides
            else -> (sa.filter(Char::isDigit).toIntOrNull() ?: 0).compareTo(sb.filter(Char::isDigit).toIntOrNull() ?: 0)
        }
    }

    /** Parses the List-releases JSON (unknown fields ignored; a release without a tag is skipped). */
    fun parse(json: String): List<Release> =
        Json.parseToJsonElement(json).jsonArray.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val tag = o.string("tag_name") ?: return@mapNotNull null
            Release(
                tag = tag,
                url = o.string("html_url") ?: RELEASES_URL,
                prerelease = o["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false,
                draft = o["draft"]?.jsonPrimitive?.booleanOrNull ?: false,
                assets =
                    (o["assets"] as? JsonArray).orEmpty().mapNotNull { a ->
                        val ao = a.jsonObject
                        val name = ao.string("name") ?: return@mapNotNull null
                        val url = ao.string("browser_download_url") ?: return@mapNotNull null
                        ReleaseAsset(name, url)
                    },
            )
        }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}

/** [ReleasesSource] over the app's shared OkHttp client (tools/check allow.json `network_ok`). */
class GitHubReleases(
    http: OkHttpClient,
    private val io: CoroutineDispatcher,
) : ReleasesSource {
    private val client = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()

    override suspend fun fetch(): Result<List<Release>> =
        withContext(io) {
            runCatching {
                val request =
                    Request
                        .Builder()
                        .url(UpdateCheck.API_URL)
                        .header("Accept", "application/vnd.github+json")
                        .build()
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    UpdateCheck.parse(response.body?.string().orEmpty())
                }
            }
        }
}
