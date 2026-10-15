package dev.spike.glucobridge.libreview

import dev.spike.glucobridge.treatments.Treatment
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The `/api/measurements` body, byte for byte as in spikes/libreview/SPEC.md §3 (checked against
 * fixture.json by LibreViewPayloadTest; the Mac builds the same bytes). Modelled on Juggluco's
 * `net/libreview/newlibre3.cpp` + `librenumbers.hpp`, i.e. what the Libre 3 app sends.
 */
object LibreViewPayload {
    const val GATEWAY = "FSLibreLink3.Android"
    const val CULTURE = "en-ES"

    private val CAPS = listOf("scheduledContinuousGlucose", "unscheduledContinuousGlucose", "currentGlucose", "insulin", "food") +
        listOf("exercise", "ondemandalarm.low", "ondemandalarm.high", "ondemandalarm.projectedlow", "ondemandalarm.projectedhigh",
            "customnote", "sensorstart", "sensorEnd", "isfGlucoseAlarm", "alarmSetting", "bleDisconnect", "bleReconnect", "error")
            .map { "generic-com.abbottdiabetescare.informatics.$it" }

    private val TYPE = mapOf("rapid" to 1, "long" to 3, "carbs" to 4, "note" to 16)

    /** One sensor reading. [lifeCount] = minutes since sensor start, the sensor's own record id. */
    data class Glucose(val lifeCount: Int, val mgdl: Int, val epochSec: Long, val rate: Double? = null)

    data class Sensor(
        val serial: String,
        val startEpoch: Long,
        val current: Glucose? = null,
        val history: List<Glucose> = emptyList(),   // 5-minute values, ascending
        val sendStart: Boolean = false,             // include the one-off sensorstart record
    )

    fun jstr(s: String) = buildString {
        append('"')
        for (ch in s) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\t' -> append("\\t")
            ch < ' ' -> append("\\u%04x".format(ch.code))
            else -> append(ch)
        }
        append('"')
    }

    private val UTC_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm':00Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val UTC_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'.000Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val LOCAL_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm':00'xxx", Locale.ROOT)
    private val LOCAL_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'.000'xxx", Locale.ROOT)

    fun utcMin(epoch: Long): String = UTC_MIN.format(Instant.ofEpochSecond(epoch))
    fun utcMs(epoch: Long): String = UTC_MS.format(Instant.ofEpochSecond(epoch))
    fun localMin(epoch: Long, zone: ZoneId): String = LOCAL_MIN.format(Instant.ofEpochSecond(epoch).atZone(zone))
    fun localMs(epoch: Long, zone: ZoneId): String = LOCAL_MS.format(Instant.ofEpochSecond(epoch).atZone(zone))

    /**
     * One LibreView record per *version* of a treatment (first 40 bits of SHA-256("id:version"), then the Libre
     * record type), the same from either app. LibreView ignores changes to an existing record number, so an edit
     * is a delete of the uploaded version plus a new record — what Juggluco does too.
     */
    fun recordNumber(tr: Treatment, version: Long): Long {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest("${tr.id}:$version".toByteArray())
        val top = (0 until 5).fold(0L) { acc, i -> (acc shl 8) or (h[i].toLong() and 0xFF) }
        return (top shl 8) or TYPE.getValue(tr.k).toLong()
    }

    /** Retract the uploaded version if it is stale (edited or deleted since), then add the current one. */
    fun treatmentEntries(tr: Treatment, zone: ZoneId): List<String> = buildList {
        if (tr.lv != 0L && (tr.del || tr.lv != tr.u)) add(treatmentEntry(tr, recordNumber(tr, tr.lv), deleted = true, zone))
        if (!tr.del) add(treatmentEntry(tr, recordNumber(tr, tr.u), deleted = false, zone))
    }

    /** Juggluco's `getmealtype`. */
    fun foodType(grams: Double, epoch: Long, zone: ZoneId): String {
        if (grams > 50) {
            val h = Instant.ofEpochSecond(epoch).atZone(zone).hour
            if (h in 4..10) return "Breakfast"
            if (h in 11..15) return "Lunch"
            if (h in 16..21) return "Dinner"
        }
        return "Snack"
    }

    private fun treatmentEntry(tr: Treatment, rn: Long, deleted: Boolean, zone: ZoneId): String {
        val del = if (deleted) "\"action\":\"deleted\"," else ""
        val fts = "\"factoryTimestamp\":\"${utcMin(tr.t)}\","
        val tail = "\"linkedGlucoseRecordNumber\":\"0\"},\"recordNumber\":$rn,\"timestamp\":\"${localMin(tr.t, zone)}\"}"
        return when (tr.k) {
            "rapid", "long" -> "{\"insulinType\":\"${if (tr.k == "rapid") "RapidActing" else "LongActing"}\"," +
                "\"units\":${"%.1f".format(Locale.ROOT, tr.a)},\"extendedProperties\":{$fts$del$tail"
            "carbs" -> "{\"foodType\":\"${foodType(tr.a, tr.t, zone)}\",\"gramsCarbs\":${Math.round(tr.a)}," +
                "\"extendedProperties\":{$fts$del$tail"
            else -> "{\"type\":\"com.abbottdiabetescare.informatics.customnote\",\"extendedProperties\":{$fts" +
                "\"text\":${jstr(tr.n)},$del$tail"
        }
    }

    // MARK: glucose (phone only)

    /** Juggluco's base-32 serial alphabet (`share/serial.hpp` unalf). */
    private fun unalf(c: Char): Long {
        val ch = when (val u = c.uppercaseChar()) { 'B' -> '8'; 'I' -> '1'; 'O' -> '0'; 'S' -> '5'; else -> u }
        return when {
            ch <= '9' -> ch - '0'
            ch == 'A' -> 10
            ch <= 'H' -> ch - 'A' + 9
            ch <= 'N' -> ch - 'A' + 8
            ch <= 'R' -> ch - 'A' + 7
            else -> ch - 'A' + 6
        }.toLong()
    }

    /** Juggluco's `libreviewHistor3`: last 9 serial chars, 5 bits each, shifted left 19 (wrapping). */
    fun histor(serial: String): Long = serial.takeLast(9).fold(0L) { acc, c -> (acc shl 5) or unalf(c) } shl 19

    fun trendArrow(rate: Double?) = when {
        rate == null || rate.isNaN() -> "Undetermined"
        rate > 2 -> "RisingQuickly"
        rate > 1 -> "Rising"
        rate >= -1 -> "Stable"
        rate >= -2 -> "Falling"
        else -> "FallingQuickly"
    }

    private fun current(h: Long, g: Glucose, zone: ZoneId) =
        "{\"extendedProperties\":{\"trendArrow\":\"${trendArrow(g.rate)}\",\"isActionable\":true,\"isViewed\":false," +
            "\"factoryTimestamp\":\"${utcMs(g.epochSec)}\",\"isFirstAfterTimeChange\":false},\"recordNumber\":${h or g.lifeCount.toLong()}," +
            "\"timestamp\":\"${localMs(g.epochSec, zone)}\",\"valueInMgPerDl\":${g.mgdl}.0}"

    private fun history(h: Long, g: Glucose, zone: ZoneId) =
        "{\"extendedProperties\":{\"canMerge\":true,\"isFirstAfterTimeChange\":false,\"factoryTimestamp\":\"${utcMs(g.epochSec)}\"}," +
            "\"recordNumber\":${h or g.lifeCount.toLong()},\"timestamp\":\"${localMs(g.epochSec, zone)}\",\"valueInMgPerDl\":${g.mgdl}.0}"

    private fun sensorStart(h: Long, s: Sensor, now: Long, zone: ZoneId) =
        "{\"type\":\"com.abbottdiabetescare.informatics.sensorstart\",\"extendedProperties\":{\"factoryTimestamp\":\"${utcMs(s.startEpoch)}\"," +
            "\"puckGen\":0,\"wearDuration\":21600,\"warmupTime\":60,\"productType\":4},\"recordNumber\":$h,\"timestamp\":\"${localMs(now, zone)}\"}"

    fun payload(treatments: List<Treatment>, deviceId: String, token: String, now: Long, zone: ZoneId, sensor: Sensor? = null): String {
        val trs = treatments.sortedWith(compareBy({ it.t }, { it.id }))
        fun entries(vararg kinds: String) = trs.filter { it.k in kinds }.flatMap { treatmentEntries(it, zone) }
        val h = sensor?.let { histor(it.serial) } ?: 0L
        val generic = listOfNotNull(sensor?.takeIf { it.sendStart }?.let { sensorStart(h, it, now, zone) }) + entries("note")
        val currents = listOfNotNull(sensor?.current?.let { current(h, it, zone) })
        val hist = sensor?.history.orEmpty().map { history(h, it, zone) }
        return "{\"DeviceData\":{\"deviceSettings\":{\"factoryConfig\":{\"UOM\":\"mg/dL\"},\"firmwareVersion\":\"3.3.0\"," +
            "\"miscellaneous\":{\"selectedLanguage\":\"$CULTURE\",\"valueGlucoseTargetRangeLowInMgPerDl\":70," +
            "\"valueGlucoseTargetRangeHighInMgPerDl\":140,\"selectedTimeFormat\":\"24hr\",\"selectedCarbType\":\"grams of carbs\"}," +
            "\"timestamp\":\"${localMs(now, zone)}\"},\"header\":{\"device\":{\"hardwareDescriptor\":\"GlucoBridge-spike\",\"hardwareName\":\"GlucoBridge\"," +
            "\"modelName\":\"com.freestylelibre3.app.de\",\"osType\":\"Android\",\"osVersion\":\"36\",\"uniqueIdentifier\":\"$deviceId\"}}," +
            "\"measurementLog\":{\"capabilities\":[${CAPS.joinToString(",") { jstr(it) }}]," +
            "\"currentGlucoseEntries\":[${currents.joinToString(",")}],\"foodEntries\":[${entries("carbs").joinToString(",")}]," +
            "\"genericEntries\":[${generic.joinToString(",")}],\"insulinEntries\":[${entries("rapid", "long").joinToString(",")}]," +
            "\"scheduledContinuousGlucoseEntries\":[${hist.joinToString(",")}],\"unscheduledContinuousGlucoseEntries\":[]}}," +
            "\"UserToken\":${jstr(token)},\"Domain\":\"Libreview\",\"GatewayType\":\"$GATEWAY\"}"
    }
}
