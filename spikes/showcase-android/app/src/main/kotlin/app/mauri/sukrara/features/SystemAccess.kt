// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.features

import android.os.Handler
import android.os.Looper
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the Sukrara LSPosed module runs in System UI, from LSPosed itself (libxposed service API 102: the framework
 * hands an active module app its XposedService). Root is [app.mauri.sukrara.status.RootStatusBar.root].
 *
 * What depends on what: the value in the status bar needs root; its colour, hiding the notification icon, every
 * placement but "With the system icons", and the lock screen widget need the module.
 */
object SystemAccess {
    const val SYSTEM_UI = "com.android.systemui"

    enum class Module {
        /** Waiting for LSPosed (it binds within a moment of the app starting). */
        CHECKING,
        /** No LSPosed, or the Sukrara module isn't enabled in it. */
        MISSING,
        /** Enabled, but System UI isn't in its scope. */
        NO_SCOPE,
        /** In scope, but System UI doesn't run the module: restart System UI. */
        RESTART,
        /** System UI runs an older build of the module (Sukrara was updated): works, a restart loads the new one. */
        UPDATE,
        ACTIVE,
    }

    /** [target]: System UI's state in LSPosed (UP_TO_DATE, STALE…), or null when it doesn't run the module. */
    data class Status(val module: Module, val framework: String? = null, val target: String? = null) {
        val active get() = module == Module.ACTIVE || module == Module.UPDATE
    }

    private val _status = MutableStateFlow(Status(Module.CHECKING))
    val status: StateFlow<Status> = _status
    @Volatile private var service: XposedService? = null
    private val main = Handler(Looper.getMainLooper())

    fun init() {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(s: XposedService) { service = s; refresh() }
            override fun onServiceDied(s: XposedService) { service = null; _status.value = Status(Module.MISSING) }
        })
        // LSPosed hands the service over at start; none after a few seconds = not there
        main.postDelayed({ if (_status.value.module == Module.CHECKING) _status.value = Status(Module.MISSING) }, 3_000)
    }

    /** Asks LSPosed again (after a scope change, a System UI restart, coming back to the screen). */
    fun refresh() {
        val s = service ?: return
        _status.value = runCatching {
            val framework = "${s.frameworkName} ${s.frameworkVersion}"
            val target = s.runningTargets.firstOrNull { it.processName == SYSTEM_UI }
            Status(when {
                SYSTEM_UI !in s.scope -> Module.NO_SCOPE
                target == null || target.state == HookedTarget.State.FAILED -> Module.RESTART
                target.state != HookedTarget.State.UP_TO_DATE -> Module.UPDATE
                else -> Module.ACTIVE
            }, framework, target?.state?.name)
        }.getOrElse { Status(Module.MISSING) }
    }

    /** LSPosed shows its own prompt to add System UI to the module's scope. */
    fun requestSystemUi(onResult: (String?) -> Unit) {
        val s = service ?: return onResult("LSPosed isn't connected")
        s.requestScope(listOf(SYSTEM_UI), object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: List<String>) { refresh(); main.post { onResult(null) } }
            override fun onScopeRequestFailed(message: String) { main.post { onResult(message) } }
        })
    }
}
