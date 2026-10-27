// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

/**
 * What every output says about the sensor a reading came from (investigation 23 + docs/22), driven by the profile:
 *
 * - **Local broadcasts** (xDrip+ `BgEstimate`, patched LibreLink, `glucodata.Minute`) only for a [SensorProfile.verified]
 *   family with a [SensorProfile.broadcastLabel]: a closed loop (AAPS) must never consume untested data, and the
 *   label it gets must be the real sensor's (AAPS turns on advanced filtering for "Libre3").
 * - **Device names** (Health Connect, Tidepool, Nightscout, the phone's Nightscout / xDrip+ APIs, the CSV export) are
 *   the family's brand and model, with "(untested)" where the format has free text; never Abbott / FreeStyle Libre 3
 *   for another family.
 * - **LibreView** only for [SensorProfile.libreView] (the Libre 3).
 */
object SensorOutputs {
    /** The `Reading.dev` tags that stand for the Libre 3 without being a profile id: Juggluco's Libre, debug data. */
    private val LIBRE_STAND_INS = setOf("juggluco", "synthetic", "debug")

    /** The family a reading came from, by its `dev` tag (the direct source tags each with `SensorProfile.id`). */
    fun profileOf(dev: String): SensorProfile? = SensorProfiles.byId(dev) ?: SensorProfiles.LIBRE_3.takeIf { dev in LIBRE_STAND_INS }

    /** The broadcasts' `SourceInfo` for [profile], or null = send none. */
    fun broadcastLabel(profile: SensorProfile?): String? = profile?.takeIf { it.verified }?.broadcastLabel

    /** The family whose untested support pauses the broadcasts (Settings → Apps on this phone says so), or null. */
    fun broadcastsPausedBy(profile: SensorProfile?): SensorProfile? = profile?.takeIf { broadcastLabel(it) == null }

    fun manufacturer(profile: SensorProfile): String = profile.brand

    /** The model as other services show it: "FreeStyle Libre 3", "Dexcom G7 / ONE+ (untested)". */
    fun model(profile: SensorProfile): String = (profile.deviceModel ?: profile.name) + if (profile.verified) "" else " (untested)"

    /**
     * A device field that names the uploader (Nightscout `device`, the CSV's Device): [base] ("Sukrara") for the
     * Libre 3 or an unknown family, as before (Nightscout dedupes on it), else "Sukrara · Dexcom G7 / ONE+ (untested)".
     */
    fun uploader(base: String, profile: SensorProfile?): String =
        if (profile == null || profile.id == SensorProfiles.LIBRE_3.id) base else "$base · ${model(profile)}"

    /** Health Connect's device (manufacturer, model) for a reading's `dev` tag; null = unknown (left empty). */
    fun healthConnectDevice(dev: String): Pair<String, String>? = profileOf(dev)?.let { manufacturer(it) to model(it) }

    /** LibreView may take this family's glucose and sensor start. */
    fun libreView(profile: SensorProfile?): Boolean = profile?.libreView == true
}
