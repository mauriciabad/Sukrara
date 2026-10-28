// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.systemui

import android.os.Handler
import android.os.Looper
import android.util.Log
import app.mauri.sukrara.core.model.ModuleState
import app.mauri.sukrara.core.model.SystemAccessPort
import app.mauri.sukrara.core.model.SystemAccessState
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Root for Sukrara and the Sukrara LSPosed module in System UI, as the status-bar and lock-screen pages and the home
 * card need them. Root: `su -c id -u` (KernelSU / Magisk ask the person the first time). The module: LSPosed's own
 * report (libxposed service: Sukrara enabled, System UI in its scope) plus the module's hello from inside System UI,
 * which carries the code it runs ([HookCode.VERSION]). LSPosed's report is advisory (it only decides which hint shows).
 */
class SystemAccess(
    private val io: CoroutineDispatcher,
) : SystemAccessPort {
    private val _state = MutableStateFlow(SystemAccessState())
    override val state: StateFlow<SystemAccessState> = _state

    @Volatile private var service: XposedService? = null

    /** The module's hello since this app process started: the code System UI runs (null = no hello yet). */
    @Volatile private var helloCode: String? = null

    private var listening = false

    /** Starts listening for LSPosed (it binds within a moment of the app starting); none after 3 s = not there. */
    fun start() {
        if (listening) return
        listening = true
        runCatching {
            XposedServiceHelper.registerListener(
                object : XposedServiceHelper.OnServiceListener {
                    override fun onServiceBind(s: XposedService) {
                        service = s
                        refreshModule()
                    }

                    override fun onServiceDied(s: XposedService) {
                        service = null
                        refreshModule()
                    }
                },
            )
        }.onFailure { Log.w(TAG, "LSPosed listener: ${it.javaClass.simpleName}") }
        Handler(Looper.getMainLooper()).postDelayed({ refreshModule(checked = true) }, 3_000)
    }

    /** System UI's module said hello with the code it runs. */
    fun onHello(code: String?) {
        helloCode = code ?: ""
        refreshModule()
    }

    override suspend fun checkRoot() {
        val ok = withContext(io) { su("id -u", 5).let { it.exit == 0 && it.output.trim() == "0" } }
        _state.update { it.copy(root = ok) }
    }

    override suspend fun restartSystemUi(): String? {
        // a kill, not `am crash` (crashes count towards rescue mode); System UI is persistent, Android starts it at once
        val r = withContext(io) { su("kill \$(pidof ${SystemUi.PACKAGE})", SU_WAIT_S) }
        val problem = restartError(r.exit, r.output)
        if (r.exit == 0) _state.update { it.copy(root = true) }
        if (problem != null) return problem
        helloCode = null
        // it takes a few seconds to start and load the module again: follow it up for 20 s
        repeat(20) {
            delay(1_000)
            refreshModule()
            if (_state.value.module == ModuleState.Active) return null
        }
        return null
    }

    override suspend fun requestScope(): String? {
        val s = service ?: return "LSPosed isn't connected"
        return suspendCancellableCoroutine { cont ->
            runCatching {
                s.requestScope(
                    listOf(SystemUi.PACKAGE),
                    object : XposedService.OnScopeEventListener {
                        override fun onScopeRequestApproved(approved: List<String>) {
                            refreshModule()
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onScopeRequestFailed(message: String) {
                            if (cont.isActive) cont.resume(message)
                        }
                    },
                )
            }.onFailure { if (cont.isActive) cont.resume(it.javaClass.simpleName) }
        }
    }

    override fun refresh() = refreshModule()

    private fun refreshModule(checked: Boolean = false) {
        val s = service
        val lsposed =
            s?.let {
                runCatching {
                    Lsposed(
                        framework = "${it.frameworkName} ${it.frameworkVersion}",
                        inScope = SystemUi.PACKAGE in it.scope,
                    )
                }.getOrNull()
            }
        _state.update { old ->
            val module = decide(lsposed, helloCode, HookCode.VERSION, waiting = !checked && old.module == ModuleState.Checking)
            old.copy(module = module, framework = lsposed?.framework)
        }
    }

    /** What LSPosed reports: its name and version, and whether System UI is in the module's scope. */
    data class Lsposed(
        val framework: String,
        val inScope: Boolean,
    )

    private class SuResult(
        val exit: Int?,
        val output: String,
    )

    /** Runs [cmd] with `su`, waiting up to [waitS] (the root prompt may be showing); exit null = no answer in time. */
    private fun su(
        cmd: String,
        waitS: Long,
    ): SuResult =
        runCatching {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val done = p.waitFor(waitS, TimeUnit.SECONDS)
            if (!done) {
                p.destroyForcibly()
                SuResult(null, "")
            } else {
                SuResult(p.exitValue(), p.inputStream.bufferedReader().readText())
            }
        }.getOrElse { SuResult(-1, it.message ?: "su not found") }

    companion object {
        private const val TAG = "Sukrara.systemui"
        private const val SU_WAIT_S = 30L
        internal const val TIMED_OUT = "no answer from su"

        /**
         * The module's state: the hello is the truth (System UI runs the module, with this code or another); without
         * one, LSPosed tells why: not there ([ModuleState.Missing]), System UI not in scope, or in scope but not loaded
         * yet ([ModuleState.Restart]). [waiting] = still within LSPosed's first seconds.
         */
        fun decide(
            lsposed: Lsposed?,
            hello: String?,
            mine: String,
            waiting: Boolean = false,
        ): ModuleState =
            when {
                hello != null && hello == mine -> ModuleState.Active
                hello != null -> ModuleState.Update
                lsposed == null -> if (waiting) ModuleState.Checking else ModuleState.Missing
                !lsposed.inScope -> ModuleState.NoScope
                else -> ModuleState.Restart
            }

        /** What a `su -c kill …` run means: null = System UI was killed; else a short reason (exit null = no answer). */
        fun restartError(
            exit: Int?,
            output: String,
        ): String? =
            when (exit) {
                null -> {
                    TIMED_OUT
                }

                0 -> {
                    null
                }

                else -> {
                    output
                        .lines()
                        .map { it.trim() }
                        .lastOrNull { it.isNotEmpty() }
                        ?.take(120) ?: "su exit $exit"
                }
            }
    }
}
