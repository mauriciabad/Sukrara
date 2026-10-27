// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data.net

import okhttp3.OkHttpClient
import java.time.Duration

/**
 * The app's one HTTP client (connection pool and dispatcher shared by every integration). No logging interceptor:
 * bodies carry glucose values, tokens and passwords. Modules derive variants with `client.newBuilder()`.
 */
object Http {
    private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(15)
    private val READ_TIMEOUT: Duration = Duration.ofSeconds(30)
    private val CALL_TIMEOUT: Duration = Duration.ofSeconds(60)

    fun userAgent(appVersion: String) = "Sukrara/$appVersion"

    fun client(appVersion: String): OkHttpClient {
        val agent = userAgent(appVersion)
        return OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT)
            .readTimeout(READ_TIMEOUT)
            .callTimeout(CALL_TIMEOUT)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", agent).build()) }
            .build()
    }
}
