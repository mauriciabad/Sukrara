// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucostatus

import android.os.IBinder

/**
 * Runs outside the app, via `app_process`, as root (`su`) — or as the adb shell user, which also holds
 * android.permission.STATUS_BAR. It calls the same hidden API that the IME uses for its keyboard icon:
 *
 *     IStatusBarService.setIcon(slot, iconPackage, iconResId, iconLevel, contentDescription)
 *
 * SystemUI then loads the icon *from our APK's resources*; the app ships one drawable per value.
 *
 * Protocol: one command per line on stdin, one reply line on stdout.
 *     set <slot> <resId> <level> <description…>
 *     remove <slot>
 * On EOF (the app died or let go) every slot we set is removed, so no stale value stays on screen.
 *
 * When SystemUI restarts, system_server hands it our icons back in hash order, which can swap number and arrow.
 * So we watch SystemUI's pid and, after a restart, re-add our icons in the order they were first set.
 */
object SlotDaemon {
    @JvmStatic
    fun main(args: Array<String>) {
        val pkg = args.getOrNull(0) ?: "dev.spike.glucostatus"
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "statusbar") as IBinder
        val svc = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        val setIcon = svc.javaClass.getMethod("setIcon", String::class.java, String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
        val removeIcon = svc.javaClass.getMethod("removeIcon", String::class.java)

        val slots = LinkedHashMap<String, Array<Any>>() // slot → setIcon args, in first-set order
        fun readd() = synchronized(slots) {
            android.util.Log.i(TAG, "SystemUI restarted: re-adding ${slots.keys}")
            slots.keys.forEach { runCatching { removeIcon.invoke(svc, it) } }
            slots.values.forEach { runCatching { setIcon.invoke(svc, *it) } }
        }
        Thread {
            var pid = systemUiPid()
            android.util.Log.i(TAG, "slot daemon: SystemUI pid $pid, age ${pid?.let { ageSec(it) }}")
            // Started while SystemUI was (re)starting: same problem, without a pid change to notice.
            pid?.let { ageSec(it) }?.takeIf { it < 20 }?.let { Thread.sleep(((13 - it).coerceAtLeast(0.0) * 1000).toLong()); readd() }
            while (true) {
                Thread.sleep(5_000)
                val now = systemUiPid()
                if (now != null && pid != null && now != pid) { Thread.sleep(8_000); readd() } // let it register first
                if (now != null) pid = now
            }
        }.apply { isDaemon = true }.start()
        reply("ready uid=${android.os.Process.myUid()}")
        while (true) {
            val line = readlnOrNull() ?: break
            val parts = line.trim().split(" ", limit = 5)
            try {
                when (parts[0]) {
                    "set" -> {
                        val a = arrayOf<Any>(parts[1], pkg, parts[2].toInt(), parts[3].toInt(), parts.getOrElse(4) { "" })
                        setIcon.invoke(svc, *a)
                        synchronized(slots) { slots[parts[1]] = a }
                    }
                    "remove" -> { removeIcon.invoke(svc, parts[1]); synchronized(slots) { slots -= parts[1] } }
                    else -> { reply("err unknown command"); continue }
                }
                reply("ok")
            } catch (e: Throwable) {
                reply("err ${(e.cause ?: e).toString().replace('\n', ' ')}")
            }
        }
        synchronized(slots) { slots.keys.forEach { runCatching { removeIcon.invoke(svc, it) } } }
    }

    /** Seconds since the process started (/proc/<pid>/stat field 22 is in clock ticks, 100 Hz on Android). */
    private fun ageSec(pid: String): Double? = runCatching {
        val start = java.io.File("/proc/$pid/stat").readText().substringAfterLast(')').trim().split(' ')[19].toDouble() / 100
        java.io.File("/proc/uptime").readText().split(' ')[0].toDouble() - start
    }.getOrNull()

    private fun systemUiPid(): String? = java.io.File("/proc").listFiles()?.firstOrNull {
        it.name.all(Char::isDigit) && runCatching { java.io.File(it, "cmdline").readText().trimEnd('\u0000') }
            .getOrNull() == "com.android.systemui"
    }?.name

    private fun reply(s: String) {
        println(s)
        System.out.flush()
    }
}
