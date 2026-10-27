// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucostatus

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Which status-bar surfaces are on. Every approach can run at the same time for side-by-side comparison. */
data class Config(
    val demo: Boolean = false,
    /** Approach 1: Live Update (promoted ongoing notification) → status bar chip. */
    val chip: Boolean = true,
    /** Android 17 MetricStyle instead of BigTextStyle for the notification body. */
    val metricStyle: Boolean = true,
    /** Hide the chip while another app has a Live Update (needs notification listener access). */
    val yieldChip: Boolean = true,
    /** Approach 2: the notification's small icon is the number, rendered to a bitmap. */
    val dynamicIcon: Boolean = true,
    /** Approach 3a: system icon slot (right side) via IStatusBarService.setIcon, through the slot daemon. */
    val slot: Boolean = false,
    /** Approach 3b: LSPosed hook in SystemUI draws a TextView. Position is one of [HookPosition]. */
    val hook: Boolean = false,
    val hookPosition: String = HookPosition.AFTER_CLOCK,
) {
    companion object {
        private const val PREFS = "config"

        fun load(c: Context): Config {
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = Config()
            return Config(
                demo = p.getBoolean("demo", d.demo),
                chip = p.getBoolean("chip", d.chip),
                metricStyle = p.getBoolean("metricStyle", d.metricStyle),
                yieldChip = p.getBoolean("yieldChip", d.yieldChip),
                dynamicIcon = p.getBoolean("dynamicIcon", d.dynamicIcon),
                slot = p.getBoolean("slot", d.slot),
                hook = p.getBoolean("hook", d.hook),
                hookPosition = p.getString("hookPosition", d.hookPosition)!!,
            )
        }
    }

    fun save(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
        putBoolean("demo", demo); putBoolean("chip", chip); putBoolean("metricStyle", metricStyle)
        putBoolean("yieldChip", yieldChip); putBoolean("dynamicIcon", dynamicIcon); putBoolean("slot", slot)
        putBoolean("hook", hook); putString("hookPosition", hookPosition)
    }
}

/** Where the SystemUI hook places the glucose view. Shared with the hook through the broadcast. */
object HookPosition {
    const val AFTER_CLOCK = "after_clock"
    const val BEFORE_CLOCK = "before_clock"
    /** "icons:0" = left of all right-side icons ("icons:-1", right of the battery, gets clipped by the corner). */
    const val ICONS_PREFIX = "icons:"
    /** "slot:wifi" = the system-icon slot output (3a), moved right after that icon. Needs both 3a and the hook. */
    const val SLOT_PREFIX = "slot:"
}

/** Process-wide state shared by the service and the UI. */
object Hub {
    data class State(
        val reading: Reading? = null,
        val source: String = "stopped",
        val otherLiveUpdates: List<String> = emptyList(),
        val slot: String = "off",
        val notes: List<String> = emptyList(),
        /** A value injected over adb stays until then, instead of being replaced by the source. */
        val pinnedUntilMs: Long = 0,
    )

    val state = MutableStateFlow(State())

    fun note(s: String) = state.update { it.copy(notes = (listOf(s) + it.notes).take(8)) }
}
