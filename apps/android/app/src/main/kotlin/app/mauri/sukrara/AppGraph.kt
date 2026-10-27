// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import android.app.Application
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.DefaultDispatchers
import app.mauri.sukrara.core.model.DispatcherProvider
import app.mauri.sukrara.core.model.SystemClock
import app.mauri.sukrara.feature.home.HomeDeps
import app.mauri.sukrara.feature.sharing.SharingDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/**
 * The app's object graph (manual DI, docs/ARCHITECTURE.md §Android DI).
 *
 * - Each area owns ONE module class in its own Gradle module (e.g. `DataModule` in `:core:data`, `MonitorModule` in
 *   `:monitor`) that builds its objects from constructor parameters. `AppGraph` only composes them: one
 *   `val x by lazy { XModule(…) }` line per module, then the feature `XxxDeps` it implements delegate to them.
 * - Built in [App.onCreate] on the main thread, so the constructor does no I/O: everything is `lazy`, and work
 *   that reads disk happens in [ready] on the IO dispatcher.
 * - [scope] lives as long as the process; a failing child doesn't cancel the others.
 */
class AppGraph(
    val app: Application,
    val dispatchers: DispatcherProvider = DefaultDispatchers,
    val clock: Clock = SystemClock,
) : HomeDeps, SharingDeps {
    val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    override val appVersion: String = BuildConfig.VERSION_NAME

    /** Loads what the modules need from disk, off the main thread. Call before reading state. */
    suspend fun ready() = withContext(dispatchers.io) {
        // modules' ready() calls go here, in dependency order
    }
}
