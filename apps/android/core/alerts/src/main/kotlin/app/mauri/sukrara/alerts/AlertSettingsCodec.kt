// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The SPEC §3 JSON ⇄ [AlertSettings]. The shape is protocol: followers receive the wearer's settings in it
 * (PROTOCOL-v2 "Settings"), and the Mac writes the same keys. Decoding is lenient by design — a newer peer may add
 * keys: unknown keys are ignored, a missing or mistyped value falls back to its default and a number out of range is
 * coerced into its schema range ([AlertLimits]), so a bad payload can never switch an alert off by accident.
 */
object AlertSettingsCodec {
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(s: AlertSettings): String = json.encodeToString(JsonObject.serializer(), toJson(s))

    /** [text] as settings; null when it isn't a JSON object at all (the caller keeps what it had). */
    fun decode(text: String): AlertSettings? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        return fromJson(root)
    }

    fun toJson(s: AlertSettings): JsonObject = buildJsonObject {
        put("v", VERSION)
        put("on", s.on)
        putJsonObject("alerts") {
            for (kind in AlertKind.entries) {
                val rule = s.rule(kind)
                putJsonObject(kind.wire) {
                    put("on", rule.on)
                    when (thresholdKey(kind)) {
                        RATE -> put(RATE, rule.threshold)
                        else -> put(thresholdKey(kind), rule.threshold.toInt())
                    }
                    put("repeatMin", rule.repeatMin)
                    put("alarm", rule.alarm)
                }
            }
        }
        putQuiet("muteAfterInsulin", s.afterInsulin)
        putQuiet("muteFallingHigh", s.fallingHigh)
        putQuiet("muteAfterCarbs", s.afterCarbs)
        putJsonObject("sounds") {
            put("pack", s.sounds.pack)
            putJsonObject("kinds") { s.sounds.perKind.forEach { (k, v) -> put(k.wire, v) } }
            put("shuffle", s.sounds.shuffle)
        }
        putJsonObject("notificationActions") {
            put("muteMin", s.actions.muteMin)
            put("relaxMin", s.actions.relaxMin)
            put("backMgdl", s.actions.backMgdl)
            put("backMin", s.actions.backMin)
        }
        put("source", s.source.wire)
    }

    fun fromJson(j: JsonObject): AlertSettings {
        val d = AlertSettings()
        val alerts = j.obj("alerts")
        val rules = AlertKind.entries.associateWith { kind ->
            val def = d.rule(kind)
            val o = alerts?.obj(kind.wire) ?: return@associateWith def
            KindRule(
                on = o.bool("on") ?: def.on,
                threshold = AlertLimits.threshold(kind).coerce(o.num(thresholdKey(kind)) ?: def.threshold),
                repeatMin = AlertLimits.ALERT_REPEAT.coerce(o.num("repeatMin")?.toInt() ?: def.repeatMin),
                alarm = o.bool("alarm") ?: def.alarm,
            )
        }
        val sounds = j.obj("sounds")
        val actions = j.obj("notificationActions")
        fun action(key: String, limit: NumberLimit, def: Int) = limit.coerce(actions?.num(key)?.toInt() ?: def)
        return AlertSettings(
            on = j.bool("on") ?: d.on,
            rules = rules,
            afterInsulin = j.quiet("muteAfterInsulin", d.afterInsulin, AlertLimits.MUTE_INSULIN),
            fallingHigh = j.quiet("muteFallingHigh", d.fallingHigh, AlertLimits.MUTE_FALLING),
            afterCarbs = j.quiet("muteAfterCarbs", d.afterCarbs, AlertLimits.MUTE_CARBS),
            sounds = SoundSettings(
                pack = sounds?.str("pack") ?: d.sounds.pack,
                perKind = sounds?.obj("kinds")?.entries
                    ?.mapNotNull { (k, v) -> AlertKind.fromWire(k)?.let { kind -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { kind to it.content } } }
                    ?.toMap() ?: emptyMap(),
                shuffle = sounds?.bool("shuffle") ?: d.sounds.shuffle,
            ),
            actions = NotificationActions(
                muteMin = action("muteMin", AlertLimits.ACTION_MUTE, d.actions.muteMin),
                relaxMin = action("relaxMin", AlertLimits.ACTION_RELAX, d.actions.relaxMin),
                backMgdl = action("backMgdl", AlertLimits.BACK_MGDL, d.actions.backMgdl),
                backMin = action("backMin", AlertLimits.BACK_MIN, d.actions.backMin),
            ),
            source = AlertSource.entries.firstOrNull { it.wire == j.str("source") } ?: d.source,
        )
    }

    private const val RATE = "rate"

    private fun thresholdKey(kind: AlertKind) = when (kind.group) {
        AlertGroup.RATE -> RATE
        AlertGroup.NO_DATA -> "min"
        AlertGroup.LEVEL -> "mgdl"
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putQuiet(key: String, q: QuietRule) =
        putJsonObject(key) { put("on", q.on); put("min", q.min) }

    private fun JsonObject.quiet(key: String, def: QuietRule, limit: NumberLimit): QuietRule {
        val o = obj(key) ?: return def
        return QuietRule(on = o.bool("on") ?: def.on, min = limit.coerce(o.num("min")?.toInt() ?: def.min))
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
    private fun JsonObject.bool(key: String): Boolean? = prim(key)?.takeIf { !it.isString }?.booleanOrNull
    private fun JsonObject.num(key: String): Double? = prim(key)?.takeIf { !it.isString }?.doubleOrNull
    private fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it.isString }?.content
}
