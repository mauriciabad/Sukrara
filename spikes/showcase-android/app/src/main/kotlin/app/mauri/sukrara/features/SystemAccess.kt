// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.features

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import app.mauri.sukrara.BuildConfig
import org.json.JSONObject

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
        /** In scope, but System UI doesn't run the module (never loaded since, or it failed): restart System UI. */
        RESTART,
        /**
         * System UI runs an older build of the module whose System UI code differs from this one (or isn't known): it
         * works, a restart loads the new code. An older build with the same code ([BuildConfig.HOOK_CODE]) is [ACTIVE].
         */
        UPDATE,
        ACTIVE,
    }

    /**
     * [target]: System UI's state in LSPosed (UP_TO_DATE, STALE…), or null when it doesn't run the module; [loaded] the
     * versionCode System UI loaded the module from.
     */
    data class Status(val module: Module, val framework: String? = null, val target: String? = null, val loaded: Long = 0) {
        val active get() = module == Module.ACTIVE || module == Module.UPDATE
        val needsRestart get() = module == Module.RESTART || module == Module.UPDATE
    }

    /** What LSPosed reports for System UI: its [HookedTarget.State] name and the versionCode it loaded. */
    data class Target(val state: String, val loadedVersion: Long)

    /**
     * When System UI needs a restart, from what LSPosed reports. LSPosed marks System UI STALE after every install of
     * Sukrara (the module is the app, so any new APK is a new module), but System UI keeps running the old code fine:
     * a restart only changes something when the code System UI runs changed. [codeOf] = the System UI code fingerprint
     * this phone recorded for a versionCode (null = unknown, e.g. installed before the record existed or replaced by a
     * dev build with the same versionCode).
     *
     *  - not in scope → [Module.NO_SCOPE]; in scope but not running (or FAILED) → [Module.RESTART]
     *  - UP_TO_DATE, or RELOADING (LSPosed is hot-reloading it) → [Module.ACTIVE]
     *  - STALE from another versionCode with the same fingerprint → [Module.ACTIVE]; otherwise → [Module.UPDATE]
     */
    fun decide(inScope: Boolean, target: Target?, version: Long, code: String, codeOf: (Long) -> String?): Module = when {
        !inScope -> Module.NO_SCOPE
        target == null || target.state == "FAILED" -> Module.RESTART
        target.state == "UP_TO_DATE" || target.state == "RELOADING" -> Module.ACTIVE
        target.loadedVersion != version && codeOf(target.loadedVersion) == code -> Module.ACTIVE
        else -> Module.UPDATE
    }

    /** The module features the user has on: only these make a restart worth asking for outside their own pages. */
    fun usesModule(f: FeatureState) = f.lockWidget || (f.hook && (!f.systemIcons || f.hookColor || f.hideNotifIcon))

    /** The home screen's restart card: System UI needs a restart, a module feature is on, and it wasn't dismissed for this state. */
    fun homeBanner(s: Status, f: FeatureState, dismissed: String?) = s.needsRestart && usesModule(f) && dismissed != key(s)

    /** What a dismissal hides: this state of this build; a new install or a new state shows the card again. */
    fun key(s: Status) = "${s.module}:${s.loaded}:${BuildConfig.VERSION_CODE}"

    private val _status = MutableStateFlow(Status(Module.CHECKING))
    val status: StateFlow<Status> = _status
    @Volatile private var service: XposedService? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var prefs: android.content.SharedPreferences? = null
    private val _dismissed = MutableStateFlow<String?>(null)
    /** The [key] the user dismissed the home card for. */
    val dismissed: StateFlow<String?> = _dismissed

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
        // this build's System UI code, by versionCode (the newest few): what a later install compares with
        val codes = JSONObject(p.getString(CODES, null) ?: "{}")
        codes.put(BuildConfig.VERSION_CODE.toString(), BuildConfig.HOOK_CODE)
        val keep = codes.keys().asSequence().sortedByDescending { it.toLongOrNull() ?: 0 }.take(KEEP).toSet()
        codes.keys().asSequence().toList().filter { it !in keep }.forEach { codes.remove(it) }
        p.edit().putString(CODES, codes.toString()).apply()
        _dismissed.value = p.getString(DISMISSED, null)
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(s: XposedService) { service = s; refresh() }
            override fun onServiceDied(s: XposedService) { service = null; _status.value = Status(Module.MISSING) }
        })
        // LSPosed hands the service over at start; none after a few seconds = not there
        main.postDelayed({ if (_status.value.module == Module.CHECKING) _status.value = Status(Module.MISSING) }, 3_000)
    }

    private fun codeOf(version: Long): String? =
        prefs?.getString(CODES, null)?.let { JSONObject(it).optString(version.toString()).ifEmpty { null } }

    /** Hides the home card until System UI's state or Sukrara's build changes. */
    fun dismiss() {
        val k = key(_status.value)
        _dismissed.value = k
        prefs?.edit()?.putString(DISMISSED, k)?.apply()
    }

    /** Asks LSPosed again (after a scope change, a System UI restart, coming back to the screen). */
    fun refresh() {
        val s = service ?: return
        _status.value = runCatching {
            val framework = "${s.frameworkName} ${s.frameworkVersion}"
            val target = s.runningTargets.firstOrNull { it.processName == SYSTEM_UI }
            Status(decide(SYSTEM_UI in s.scope, target?.let { Target(it.state.name, it.loadedVersionCode) }, BuildConfig.VERSION_CODE.toLong(),
                BuildConfig.HOOK_CODE, ::codeOf), framework, target?.state?.name, target?.loadedVersionCode ?: 0)
        }.getOrElse { Status(Module.MISSING) }
    }

    /**
     * Restarts System UI (root) and follows it back up: it takes a few seconds to start and to load the module again,
     * and asking once too early saw no target yet (a false "restart needed"). Asks every second for up to 20 s.
     */
    suspend fun restartSystemUi() {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.mauri.sukrara.status.RootStatusBar.restartSystemUi() }
        repeat(20) {
            kotlinx.coroutines.delay(1_000)
            refresh()
            if (_status.value.module == Module.ACTIVE) return
        }
    }

    /** LSPosed shows its own prompt to add System UI to the module's scope. */
    fun requestSystemUi(onResult: (String?) -> Unit) {
        val s = service ?: return onResult("LSPosed isn't connected")
        s.requestScope(listOf(SYSTEM_UI), object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: List<String>) { refresh(); main.post { onResult(null) } }
            override fun onScopeRequestFailed(message: String) { main.post { onResult(message) } }
        })
    }

    private const val PREFS = "system_access"
    private const val CODES = "hook_codes"
    private const val DISMISSED = "restart_dismissed"
    private const val KEEP = 12
}
