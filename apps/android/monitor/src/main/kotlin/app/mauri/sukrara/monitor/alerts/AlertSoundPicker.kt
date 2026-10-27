// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.SoundSettings

/**
 * Which file an alert plays: the sound packs of contracts/alerts/sounds (`res/raw/<pack>_<kind>_<n>.ogg`), the system
 * notification sound, or silence. Pure, so the choice is tested on the JVM.
 */
class AlertSoundPicker(private val random: (IntRange) -> Int = { it.random() }) {
    private class Pick(val name: String, val atMs: Long)

    private val picks = HashMap<AlertKind, Pick>()
    private val lastVariant = HashMap<AlertKind, Int>()

    /**
     * The raw resource name for [kind] now, [SYSTEM] or [SILENCE]. One notification opens the URI more than once (the
     * haptics probe, then playback): one pick per kind is kept for [HOLD_MS] so both get the same file, and a shuffle
     * never repeats the previous variant.
     */
    @Synchronized
    fun pick(kind: AlertKind, s: SoundSettings, nowMs: Long): String {
        picks[kind]?.let { if (nowMs - it.atMs < HOLD_MS) return it.name }
        val pack = s.soundOf(kind)
        val name = when (pack) {
            SYSTEM -> SYSTEM
            !in PACKS -> SILENCE
            else -> {
                val n = if (!s.shuffle) 1 else (1..VARIANTS).filter { it != lastVariant[kind] }.let { it[random(it.indices)] }
                lastVariant[kind] = n
                "${pack}_${kind.wire}_$n"
            }
        }
        picks[kind] = Pick(name, nowMs)
        return name
    }

    companion object {
        val PACKS = listOf("arcade", "kart", "quest", "soft")
        const val SYSTEM = "system"
        const val SILENCE = "silence"
        const val VARIANTS = 3
        const val HOLD_MS = 5_000L
    }
}
