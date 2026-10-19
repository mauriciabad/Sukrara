// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.nsupload

/**
 * What the uploader is fed. These mirror Sukrara's own types (docs/25 "To bring into Sukrara") without depending on
 * them: [Reading] ≈ `core/Proto.Reading`, [Treatment] ≈ `treatments/Treatment`.
 */

/** Where a reading came from. Only [OWN_SENSOR] is uploaded unless the user explicitly chose to relay a person. */
enum class Origin { OWN_SENSOR, FOLLOWED }

/**
 * One sensor reading.
 * @param epochMs when the sensor measured it
 * @param rate mg/dL per minute from the sensor/algorithm (null: unknown → the uploader derives it from neighbours)
 * @param followedPerson for [Origin.FOLLOWED]: the stable id of the person being followed (Sukrara: their stream key)
 */
data class Reading(
    val epochMs: Long,
    val mgdl: Int,
    val rate: Double? = null,
    val origin: Origin = Origin.OWN_SENSOR,
    val followedPerson: String? = null,
)

/**
 * One entry of Sukrara's shared treatment log (last-writer-wins on [updatedMs], soft delete).
 * @param id a UUID, stable across edits: it becomes the Nightscout `identifier`
 * @param kind rapid | long | carbs | note
 */
data class Treatment(
    val id: String,
    val kind: String,
    val amount: Double,
    val epochMs: Long,
    val note: String = "",
    val updatedMs: Long,
    val deleted: Boolean = false,
)

/** A sensor was started (Libre 3: activation = insertion = start of the warm-up). */
data class SensorStart(val serial: String, val epochMs: Long)

/** The phone's own battery, for Nightscout's `upbat` pill and LoopFollow. */
data class UploaderBattery(val percent: Int, val charging: Boolean, val epochMs: Long)
