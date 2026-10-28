// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.systemui

import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

/**
 * The root slot daemon: runs outside the app with `su` through `app_process` (started by [RootStatusBar]) and calls
 * the hidden API the keyboard uses for its icon, `IStatusBarService.setIcon(slot, package, resId, level, description)`.
 * System UI then draws the icon from this APK's resources ([SlotIcons]).
 *
 * Protocol ([DaemonProtocol]): one command per line on stdin, one reply line on stdout (`ok` / `err …`):
 * `set <slot> <resId> <level> <description…>`, `remove <slot>`, `order module|daemon`, `ping`.
 *
 * Never leaves a stale value on screen: it removes its slots when it starts, on EOF (the app let go or died), on
 * any crash, and when the app has said nothing for [WATCHDOG_MS] (the app pings every minute while it runs).
 *
 * After a System UI restart system_server hands our icons back in hash order (trend first), so the daemon watches
 * System UI's pid and re-adds them in order, unless the module orders them itself in that System UI (`order module`).
 */
object SlotDaemon {
    private const val TAG = "Sukrara.slots"
    private const val WATCHDOG_MS = 10 * 60_000L

    @Volatile private var crashCleanup: (() -> Unit)? = null

    @JvmStatic
    fun main(args: Array<String>) {
        val pkg = args.getOrNull(0) ?: return
        // any crash, also before the status bar service is reached: the app sees no "ready" and stops
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            Log.w(TAG, "slot daemon crashed", e)
            crashCleanup?.invoke()
            exitProcess(1)
        }
        val binder =
            Class
                .forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "statusbar") as IBinder
        val svc =
            checkNotNull(
                Class
                    .forName("com.android.internal.statusbar.IStatusBarService\$Stub")
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, binder),
            )
        val setIcon =
            svc.javaClass.getMethod(
                "setIcon",
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
        val removeIcon = svc.javaClass.getMethod("removeIcon", String::class.java)
        val slots = LinkedHashMap<String, Array<Any>>() // slot → setIcon args, in first-set order
        var modulePid: String? = null
        val lastCommand = AtomicLong(SystemClock.uptimeMillis())

        fun removeAll() =
            synchronized(slots) {
                SlotIcons.ORDER.forEach { runCatching { removeIcon.invoke(svc, it) } }
                slots.clear()
            }

        fun readd(pid: String?) =
            synchronized(slots) {
                if (pid != null && pid == modulePid) return@synchronized
                Log.i(TAG, "System UI restarted: re-adding ${slots.keys}")
                slots.keys.forEach { runCatching { removeIcon.invoke(svc, it) } }
                slots.values.forEach { runCatching { setIcon.invoke(svc, *it) } }
            }

        crashCleanup = { removeAll() } // no icon may outlive the daemon
        removeAll()
        Thread {
            var pid = systemUiPid()
            // started while System UI was (re)starting: the same hash-order problem without a pid change to notice
            pid?.let { ageSec(it) }?.takeIf { it < 20 }?.let {
                Thread.sleep(((13 - it).coerceAtLeast(0.0) * 1000).toLong())
                readd(pid)
            }
            while (true) {
                Thread.sleep(5_000)
                if (SystemClock.uptimeMillis() - lastCommand.get() > WATCHDOG_MS) {
                    Log.i(TAG, "no word from the app: removing the icons")
                    removeAll()
                    exitProcess(0)
                }
                val now = systemUiPid()
                if (now != null && pid != null && now != pid) {
                    Thread.sleep(8_000) // let it register first (and its module say hello)
                    readd(now)
                }
                if (now != null) pid = now
            }
        }.apply { isDaemon = true }.start()
        reply("ready")
        while (true) {
            val line = readlnOrNull() ?: break
            lastCommand.set(SystemClock.uptimeMillis())
            val cmd = DaemonProtocol.parse(line)
            if (cmd == null) {
                reply("err bad command")
                continue
            }
            try {
                when (cmd) {
                    is DaemonProtocol.Command.Set -> {
                        val a = arrayOf<Any>(cmd.slot, pkg, cmd.resId, 0, cmd.description)
                        setIcon.invoke(svc, *a)
                        synchronized(slots) { slots[cmd.slot] = a }
                    }

                    is DaemonProtocol.Command.Remove -> {
                        removeIcon.invoke(svc, cmd.slot)
                        synchronized(slots) { slots -= cmd.slot }
                    }

                    is DaemonProtocol.Command.Order -> {
                        synchronized(slots) { modulePid = if (cmd.module) systemUiPid() else null }
                    }

                    DaemonProtocol.Command.Ping -> {}
                }
                reply("ok")
            } catch (e: Exception) {
                reply("err ${(e.cause ?: e).javaClass.simpleName}")
            }
        }
        removeAll()
    }

    /** Seconds since the process started (/proc/<pid>/stat field 22, in clock ticks of 100 Hz). */
    private fun ageSec(pid: String): Double? =
        runCatching {
            val start =
                File("/proc/$pid/stat")
                    .readText()
                    .substringAfterLast(')')
                    .trim()
                    .split(' ')[19]
                    .toDouble() / 100
            File("/proc/uptime").readText().split(' ')[0].toDouble() - start
        }.getOrNull()

    private fun systemUiPid(): String? =
        File("/proc")
            .listFiles()
            ?.firstOrNull {
                it.name.all(Char::isDigit) &&
                    runCatching { File(it, "cmdline").readText().trimEnd('\u0000') }.getOrNull() == SystemUi.PACKAGE
            }?.name

    private fun reply(s: String) {
        println(s)
        System.out.flush()
    }
}

/** The slot daemon's line protocol, parsed in one place (also by the tests). */
object DaemonProtocol {
    const val ORDER_MODULE = "order module"
    const val ORDER_DAEMON = "order daemon"
    const val PING = "ping"

    sealed interface Command {
        data class Set(
            val slot: String,
            val resId: Int,
            val description: String,
        ) : Command

        data class Remove(
            val slot: String,
        ) : Command

        data class Order(
            val module: Boolean,
        ) : Command

        data object Ping : Command
    }

    /** A command, or null when it's malformed, names a slot that isn't ours or a resource id of 0. */
    fun parse(line: String): Command? {
        val parts = line.trim().split(" ", limit = 5)
        return when (parts[0]) {
            "set" -> {
                val slot = parts.getOrNull(1)?.takeIf { it in SlotIcons.ORDER } ?: return null
                val res = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it != 0 } ?: return null
                Command.Set(slot, res, parts.getOrElse(4) { "" })
            }

            "remove" -> {
                parts.getOrNull(1)?.takeIf { it in SlotIcons.ORDER }?.let { Command.Remove(it) }
            }

            "order" -> {
                Command.Order(parts.getOrNull(1) == "module")
            }

            "ping" -> {
                Command.Ping
            }

            else -> {
                null
            }
        }
    }
}
