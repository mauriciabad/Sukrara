// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.libre3

import java.util.UUID

/** The Libre 3 / 3 Plus GATT layout, packet kinds and timings (after Juggluco's Libre3GattCallback; unchanged from the PoC). */
internal object Libre3Protocol {
    private fun u(short: String) = UUID.fromString("0898$short-EF89-11E9-81B4-2A2AE2DBCCE4")

    val CH_COMMAND: UUID = u("2198")
    val CH_CHALLENGE: UUID = u("22CE")
    val CH_CERT: UUID = u("23FA")
    val CH_PATCH_CONTROL: UUID = u("1338")
    val CH_PATCH_STATUS: UUID = u("1482")
    val CH_EVENT_LOG: UUID = u("1BEE")
    val CH_GLUCOSE: UUID = u("177A")
    val CH_HISTORIC: UUID = u("195A")
    val CH_CLINICAL: UUID = u("1AB8")
    val CH_FACTORY: UUID = u("1D24")

    /**
     * Every notifying characteristic, in Juggluco's order. The sensor runs a full data cycle every minute and drops the
     * link if we are not subscribed to all of them (subscribing to fewer cost a disconnect at exactly 60 s, 2026-10-15).
     */
    val SUBSCRIPTIONS = listOf(CH_PATCH_CONTROL, CH_EVENT_LOG, CH_HISTORIC, CH_CLINICAL, CH_FACTORY, CH_GLUCOSE, CH_PATCH_STATUS)

    /** Authorized but no glucose for this long: reconnect (or, after the sensor's end, stop). */
    const val GLUCOSE_TIMEOUT_MS = 120_000L

    /** A drop this soon after authorizing means the sensor did not accept the session setup (logged). */
    const val EARLY_DROP_MS = 90_000L

    /** Glucose the sensor reports; anything outside is not a reading. */
    val VALID_MGDL = 39..501

    /** The sensor stores one historic value per 5 minutes. */
    const val HISTORY_STEP = 5

    /** How far back to backfill on connect (minutes): 24 h. */
    const val BACKFILL_MINUTES = 24 * 60

    /** AES-CCM packet descriptors by kind (index = kind). */
    val DESCRIPTORS = arrayOf(
        byteArrayOf(0, 0, 0), byteArrayOf(0, 0, 0x0F), byteArrayOf(0, 0, 0xF0.toByte()),
        byteArrayOf(0, 0x0F, 0), byteArrayOf(0, 0xF0.toByte(), 0), byteArrayOf(0x0F, 0, 0),
        byteArrayOf(0xF0.toByte(), 0, 0), byteArrayOf(0x44, 0, 0),
    )
    const val KIND_CONTROL = 0
    const val KIND_PATCH_STATUS = 2
    const val KIND_GLUCOSE = 3
    const val KIND_HISTORIC = 4

    /**
     * App certificate for security level 1, sent during a fresh pairing (public; from Juggluco's KEYSCrypto.java,
     * LIBRE3_APP_CERTIFICATES_B[1]).
     */
    val APP_CERTIFICATE: ByteArray = (
        "03030102030405060708090a0b0c0d0e0f10000161897655010000000000000000" +
            "048242be33f1a330880112fa62cc4842a43d1204922ad201d8775bb226f611f75b0ef3d5bc6cc4317caa457584ab003f" +
            "1712336089d3a4f29838ed0dc666deaea2" +
            "d65a00dfff5d7bcae21655e302e3458e774daaaaca87af75f1b87884b18d4ce875d0d108c903a834471a4ff674b2d30b" +
            "cba062373014b7786e4437b177aec3c8"
        ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
