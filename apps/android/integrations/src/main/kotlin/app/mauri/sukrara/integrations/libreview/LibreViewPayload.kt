// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.libreview

import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentKind
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One entry as LibreView sees it: [u] = the version to send, [lv] = the version LibreView already has (0 = none). */
data class LvEntry(
    val id: String,
    /** `rapid` | `long` | `carbs` | `note` (contracts/treatments/SPEC.md §1). */
    val k: String,
    val a: Double,
    /** When it happened, epoch s. */
    val t: Long,
    val n: String,
    val u: Long,
    val del: Boolean,
    val lv: Long,
) {
    companion object {
        fun of(
            t: Treatment,
            uploaded: Long,
        ) = LvEntry(t.id, kindName(t.kind), t.amount, t.at.epochSecond, t.note, t.modifiedAt.toEpochMilli(), t.deleted, uploaded)

        fun kindName(k: TreatmentKind) =
            when (k) {
                TreatmentKind.RapidInsulin -> "rapid"
                TreatmentKind.LongInsulin -> "long"
                TreatmentKind.Carbs -> "carbs"
                TreatmentKind.Note -> "note"
            }
    }
}

/** One Libre 3 reading: [lifeCount] = minutes since the sensor started, the sensor's own record id. */
data class LvGlucose(
    val lifeCount: Int,
    val mgdl: Int,
    val epochSec: Long,
    val rate: Double? = null,
)

/** The sensor part of one upload. */
data class LvSensor(
    val serial: String,
    val startEpoch: Long,
    val current: LvGlucose? = null,
    /** 5-minute values, ascending. */
    val history: List<LvGlucose> = emptyList(),
    /** Include the one-off `sensorstart` record. */
    val sendStart: Boolean = false,
)

/**
 * The `/api/measurements` body, byte for byte as contracts/treatments/SPEC.md §3 defines it (checked against
 * `libreview-fixture.json`): what the FreeStyle Libre 3 app sends, so the person's clinic sees ordinary Libre data.
 */
object LibreViewPayload {
    const val GATEWAY = "FSLibreLink3.Android"
    const val CULTURE = "en-ES"

    private val CAPS =
        listOf("scheduledContinuousGlucose", "unscheduledContinuousGlucose", "currentGlucose", "insulin", "food") +
            listOf(
                "exercise",
                "ondemandalarm.low",
                "ondemandalarm.high",
                "ondemandalarm.projectedlow",
                "ondemandalarm.projectedhigh",
                "customnote",
                "sensorstart",
                "sensorEnd",
                "isfGlucoseAlarm",
                "alarmSetting",
                "bleDisconnect",
                "bleReconnect",
                "error",
            ).map { "generic-com.abbottdiabetescare.informatics.$it" }

    private val TYPE = mapOf("rapid" to 1, "long" to 3, "carbs" to 4, "note" to 16)

    fun jstr(s: String) =
        buildString {
            append('"')
            for (ch in s) {
                when {
                    ch == '"' -> append("\\\"")
                    ch == '\\' -> append("\\\\")
                    ch == '\n' -> append("\\n")
                    ch == '\t' -> append("\\t")
                    ch < ' ' -> append("\\u%04x".format(ch.code))
                    else -> append(ch)
                }
            }
            append('"')
        }

    private val UTC_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm':00Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val UTC_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'.000Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val LOCAL_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm':00'xxx", Locale.ROOT)
    private val LOCAL_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'.000'xxx", Locale.ROOT)

    private fun utcMin(epoch: Long) = UTC_MIN.format(Instant.ofEpochSecond(epoch))

    private fun utcMs(epoch: Long) = UTC_MS.format(Instant.ofEpochSecond(epoch))

    private fun localMin(
        epoch: Long,
        zone: ZoneId,
    ) = LOCAL_MIN.format(Instant.ofEpochSecond(epoch).atZone(zone))

    private fun localMs(
        epoch: Long,
        zone: ZoneId,
    ) = LOCAL_MS.format(Instant.ofEpochSecond(epoch).atZone(zone))

    /**
     * One LibreView record per *version* of an entry: the first 40 bits of SHA-256("id:version"), then the Libre record
     * type. LibreView ignores a changed value under an existing record number, so an edit is a retraction of the uploaded
     * version plus a new record.
     */
    fun recordNumber(
        id: String,
        kind: String,
        version: Long,
    ): Long {
        val h = MessageDigest.getInstance("SHA-256").digest("$id:$version".toByteArray())
        val top = (0 until 5).fold(0L) { acc, i -> (acc shl 8) or (h[i].toLong() and 0xFF) }
        return (top shl 8) or TYPE.getValue(kind).toLong()
    }

    /** Retracts the uploaded version if it's stale (edited or deleted since), then adds the current one. */
    fun entries(
        e: LvEntry,
        zone: ZoneId,
    ): List<String> =
        buildList {
            if (e.lv != 0L && (e.del || e.lv != e.u)) add(entry(e, recordNumber(e.id, e.k, e.lv), deleted = true, zone))
            if (!e.del) add(entry(e, recordNumber(e.id, e.k, e.u), deleted = false, zone))
        }

    /** The meal LibreView files carbs under (more than 50 g by local hour, else a snack). */
    fun foodType(
        grams: Double,
        epoch: Long,
        zone: ZoneId,
    ): String {
        if (grams > 50) {
            val h = Instant.ofEpochSecond(epoch).atZone(zone).hour
            if (h in 4..10) return "Breakfast"
            if (h in 11..15) return "Lunch"
            if (h in 16..21) return "Dinner"
        }
        return "Snack"
    }

    private fun entry(
        e: LvEntry,
        rn: Long,
        deleted: Boolean,
        zone: ZoneId,
    ): String {
        val del = if (deleted) "\"action\":\"deleted\"," else ""
        val fts = "\"factoryTimestamp\":\"${utcMin(e.t)}\","
        val tail = "\"linkedGlucoseRecordNumber\":\"0\"},\"recordNumber\":$rn,\"timestamp\":\"${localMin(e.t, zone)}\"}"
        return when (e.k) {
            "rapid", "long" -> {
                "{\"insulinType\":\"${if (e.k == "rapid") "RapidActing" else "LongActing"}\"," +
                    "\"units\":${"%.1f".format(Locale.ROOT, e.a)},\"extendedProperties\":{$fts$del$tail"
            }

            "carbs" -> {
                "{\"foodType\":\"${foodType(e.a, e.t, zone)}\",\"gramsCarbs\":${Math.round(e.a)}," +
                    "\"extendedProperties\":{$fts$del$tail"
            }

            else -> {
                "{\"type\":\"com.abbottdiabetescare.informatics.customnote\",\"extendedProperties\":{$fts" +
                    "\"text\":${jstr(e.n)},$del$tail"
            }
        }
    }

    // MARK: glucose

    /** The base-32 serial alphabet Libre serials use. */
    private fun unalf(c: Char): Long {
        val ch =
            when (val u = c.uppercaseChar()) {
                'B' -> '8'
                'I' -> '1'
                'O' -> '0'
                'S' -> '5'
                else -> u
            }
        return when {
            ch <= '9' -> ch - '0'
            ch == 'A' -> 10
            ch <= 'H' -> ch - 'A' + 9
            ch <= 'N' -> ch - 'A' + 8
            ch <= 'R' -> ch - 'A' + 7
            else -> ch - 'A' + 6
        }.toLong()
    }

    /** The sensor's record-number base: last 9 serial chars, 5 bits each, shifted left 19 (wrapping). */
    fun histor(serial: String): Long = serial.takeLast(9).fold(0L) { acc, c -> (acc shl 5) or unalf(c) } shl 19

    fun trendArrow(rate: Double?) =
        when {
            rate == null || rate.isNaN() -> "Undetermined"
            rate > 2 -> "RisingQuickly"
            rate > 1 -> "Rising"
            rate >= -1 -> "Stable"
            rate >= -2 -> "Falling"
            else -> "FallingQuickly"
        }

    private fun current(
        h: Long,
        g: LvGlucose,
        zone: ZoneId,
    ) = "{\"extendedProperties\":{\"trendArrow\":\"${trendArrow(g.rate)}\",\"isActionable\":true,\"isViewed\":false," +
        "\"factoryTimestamp\":\"${utcMs(g.epochSec)}\",\"isFirstAfterTimeChange\":false},\"recordNumber\":${h or g.lifeCount.toLong()}," +
        "\"timestamp\":\"${localMs(g.epochSec, zone)}\",\"valueInMgPerDl\":${g.mgdl}.0}"

    private fun history(
        h: Long,
        g: LvGlucose,
        zone: ZoneId,
    ) = "{\"extendedProperties\":{\"canMerge\":true,\"isFirstAfterTimeChange\":false,\"factoryTimestamp\":\"${utcMs(g.epochSec)}\"}," +
        "\"recordNumber\":${h or g.lifeCount.toLong()},\"timestamp\":\"${localMs(g.epochSec, zone)}\",\"valueInMgPerDl\":${g.mgdl}.0}"

    private fun sensorStart(
        h: Long,
        s: LvSensor,
        now: Long,
        zone: ZoneId,
    ) =
        "{\"type\":\"com.abbottdiabetescare.informatics.sensorstart\",\"extendedProperties\":{\"factoryTimestamp\":\"${utcMs(
            s.startEpoch,
        )}\"," +
            "\"puckGen\":0,\"wearDuration\":21600,\"warmupTime\":60,\"productType\":4},\"recordNumber\":$h,\"timestamp\":\"${localMs(
                now,
                zone,
            )}\"}"

    fun payload(
        entries: List<LvEntry>,
        deviceId: String,
        token: String,
        now: Long,
        zone: ZoneId,
        sensor: LvSensor? = null,
    ): String {
        val trs = entries.sortedWith(compareBy({ it.t }, { it.id }))

        fun of(vararg kinds: String) = trs.filter { it.k in kinds }.flatMap { entries(it, zone) }
        val h = sensor?.let { histor(it.serial) } ?: 0L
        val generic = listOfNotNull(sensor?.takeIf { it.sendStart }?.let { sensorStart(h, it, now, zone) }) + of("note")
        val currents = listOfNotNull(sensor?.current?.let { current(h, it, zone) })
        val hist = sensor?.history.orEmpty().map { history(h, it, zone) }
        return "{\"DeviceData\":{\"deviceSettings\":{\"factoryConfig\":{\"UOM\":\"mg/dL\"},\"firmwareVersion\":\"3.3.0\"," +
            "\"miscellaneous\":{\"selectedLanguage\":\"$CULTURE\",\"valueGlucoseTargetRangeLowInMgPerDl\":70," +
            "\"valueGlucoseTargetRangeHighInMgPerDl\":140,\"selectedTimeFormat\":\"24hr\",\"selectedCarbType\":\"grams of carbs\"}," +
            "\"timestamp\":\"${localMs(now, zone)}\"},\"header\":{\"device\":{\"hardwareDescriptor\":\"GlucoBridge-spike\"," +
            "\"hardwareName\":\"GlucoBridge\",\"modelName\":\"com.freestylelibre3.app.de\",\"osType\":\"Android\",\"osVersion\":\"36\"," +
            "\"uniqueIdentifier\":\"$deviceId\"}},\"measurementLog\":{\"capabilities\":[${CAPS.joinToString(",") { jstr(it) }}]," +
            "\"currentGlucoseEntries\":[${currents.joinToString(",")}],\"foodEntries\":[${of("carbs").joinToString(",")}]," +
            "\"genericEntries\":[${generic.joinToString(",")}],\"insulinEntries\":[${of("rapid", "long").joinToString(",")}]," +
            "\"scheduledContinuousGlucoseEntries\":[${hist.joinToString(",")}],\"unscheduledContinuousGlucoseEntries\":[]}}," +
            "\"UserToken\":${jstr(token)},\"Domain\":\"Libreview\",\"GatewayType\":\"$GATEWAY\"}"
    }
}
