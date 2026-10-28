// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.systemui

/**
 * The fingerprint of the code System UI runs: this package's sources (the module, its text and icon logic). The
 * module says it in its hello; when System UI still runs a build with another fingerprint, the home card and the
 * status-bar page ask for a System UI restart. A new install with the same System UI code asks for nothing.
 * `HookCodeTest` recomputes it and fails with the new value when the code changed: update it in the same change.
 */
object HookCode {
    const val VERSION = "68a900e47b7f50bb"
}
