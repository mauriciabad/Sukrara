// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.model

import kotlinx.coroutines.flow.StateFlow

/** The Sukrara LSPosed module in System UI (the status-bar text, colour and order, the lock screen widget). */
enum class ModuleState {
    /** Waiting for LSPosed (it answers within a moment of the app starting). */
    Checking,

    /** No LSPosed, or the Sukrara module isn't enabled in it. */
    Missing,

    /** Enabled, but System UI isn't in its scope. */
    NoScope,

    /** In scope, but System UI doesn't run it (enabled since System UI started, or it failed): restart System UI. */
    Restart,

    /** System UI runs the module from an older build whose System UI code differs: a restart loads the new code. */
    Update,

    Active,
    ;

    /** System UI runs the module (maybe an older build of it). */
    val active: Boolean get() = this == Active || this == Update

    val needsRestart: Boolean get() = this == Restart || this == Update
}

/** Root for Sukrara ([root] null = not checked yet) and the module's state; [framework] = LSPosed's name and version. */
data class SystemAccessState(
    val root: Boolean? = null,
    val module: ModuleState = ModuleState.Checking,
    val framework: String? = null,
)

/** Root and the System UI module, for the status-bar and lock-screen pages and the home card (`:monitor` implements it). */
interface SystemAccessPort {
    val state: StateFlow<SystemAccessState>

    /** Asks `su` again (the root prompt shows the first time). */
    suspend fun checkRoot()

    /** Asks LSPosed again (back on a page, after a scope change). */
    fun refresh()

    /** Restarts System UI with root so it loads the module; null when it worked, else why not. */
    suspend fun restartSystemUi(): String?

    /** LSPosed shows its own prompt to add System UI to the module's scope; null when approved, else why not. */
    suspend fun requestScope(): String?
}
