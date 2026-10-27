// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Test shim for the showcase 0.11.53 protocol code compiled from poc/ (share/build.gradle.kts `pocWireSources`):
// its Proto.kt uses exactly these members of poc/…/core/SensorLife.kt, which itself pulls Android prefs and the whole
// settings stack. Same names, same values (Libre 3 warm-up 60 min); nothing else of SensorLife is used.

data class SensorInfo(
    val serial: String,
    val startEpoch: Long,
    val endEpoch: Long,
    val warmupEndEpoch: Long = startEpoch + 60L * SensorLife.DEFAULT_WARMUP_MIN,
)

object SensorLife {
    const val DEFAULT_WARMUP_MIN: Int = 60
    val current: StateFlow<SensorInfo?> = MutableStateFlow(null)
}
