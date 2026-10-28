// Sukrara — https://github.com/mauriciabad/sukrara
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
    private class Pick(val name: String, val atMs: Long, val pack: String, val shuffle: Boolean)

    private val picks = HashMap<AlertKind, Pick>()
    private val lastVariant = HashMap<AlertKind, Int>()

    /**
     * The raw resource name for [kind] now, [SYSTEM] or [SILENCE] (only when the person chose [NONE]). One notification opens the URI more than once (the
     * haptics probe, then playback): one pick per kind is kept for [HOLD_MS] so both get the same file, and a shuffle
     * never repeats the previous variant.
     */
    @Synchronized
    fun pick(kind: AlertKind, s: SoundSettings, nowMs: Long): String {
        val pack = s.soundOf(kind)
        // the same file for one notification's opens; a changed pack or shuffle (Settings, then a test) picks anew
        picks[kind]?.let { if (nowMs - it.atMs < HOLD_MS && it.pack == pack && it.shuffle == s.shuffle) return it.name }
        val name =
            when (pack) {
                SYSTEM -> {
                    SYSTEM
                }

                NONE -> {
                    SILENCE
                }

                else -> {
                    // an unknown pack (a newer peer's, a bad value) plays the default pack: never silence by accident
                    val known = pack.takeIf { it in PACKS } ?: DEFAULT_PACK
                    val n = if (!s.shuffle) 1 else (1..VARIANTS).filter { it != lastVariant[kind] }.let { it[random(it.indices)] }
                    lastVariant[kind] = n
                    "${known}_${kind.wire}_$n"
                }
            }
        picks[kind] = Pick(name, nowMs, pack, s.shuffle)
        return name
    }

    companion object {
        val PACKS = listOf("arcade", "kart", "quest", "soft")
        const val DEFAULT_PACK = "quest"
        const val SYSTEM = "system"

        /** The pack value that means "no sound" (the person chose it). */
        const val NONE = "none"
        const val SILENCE = "silence"
        const val VARIANTS = 3
        const val HOLD_MS = 5_000L
    }
}
