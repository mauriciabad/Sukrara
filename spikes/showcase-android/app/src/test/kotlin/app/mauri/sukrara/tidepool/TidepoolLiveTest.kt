// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.tidepool

import app.mauri.sukrara.tidepool.TidepoolData.Glucose
import app.mauri.sukrara.treatments.Treatment
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end against the real Tidepool API with synthetic data (docs/27 "Verification"). Skipped unless
 * `TIDEPOOL_E2E_EMAIL` and `TIDEPOOL_E2E_PASSWORD_FILE` are set (never in CI). It creates its own clearly named upload
 * record ("Sukrara E2E test (synthetic)") on a night in 2021 (the clocks went back in Madrid), runs the app's own
 * planner and sender over it, checks what Tidepool stored, and deletes the record and its notes again, checking that
 * nothing is left. The data set id goes to `TIDEPOOL_E2E_LEDGER` (a gitignored file) first, so a crash can be cleaned
 * up by hand. Prints no token, password or user id.
 */
class TidepoolLiveTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private fun epoch(utc: String) = LocalDateTime.parse(utc).atZone(ZoneId.of("UTC")).toEpochSecond()
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun uploadVerifyAndDelete() {
        val email = System.getenv("TIDEPOOL_E2E_EMAIL")
        val pwFile = System.getenv("TIDEPOOL_E2E_PASSWORD_FILE")
        assumeTrue("set TIDEPOOL_E2E_EMAIL and TIDEPOOL_E2E_PASSWORD_FILE to run", email != null && pwFile != null)
        val ledger = File(System.getenv("TIDEPOOL_E2E_LEDGER") ?: error("set TIDEPOOL_E2E_LEDGER"))
        val log = { m: String -> println("[tidepool-e2e] $m") }

        val client = TidepoolClient("0.0.0-e2e")
        client.login(email, File(pwFile).readText())
        log("signed in")
        lifetimes(client.session!!.token).forEach { log("token part: $it") }

        val device = "Sukrara-e2e-test-" + java.util.UUID.randomUUID().toString().take(8)
        val nightStart = epoch("2021-10-30T22:00:00")   // 00:00 CEST on 31 Oct 2021; 03:00 CEST → 02:00 CET at 01:00 UTC
        val dataSet = client.createDataSet(TidepoolData.dataSet(device, "Sukrara E2E test (synthetic)", "0.0.0-e2e", zone, nightStart + 6 * 3600))
        ledger.appendText("$dataSet\n")
        log("created test data set ${dataSet.take(6)}…")
        val noteIds = mutableListOf<String>()
        try {
            // 4 h of 1-minute synthetic readings across the change → 48 five-minute slots
            val readings = (0 until 240).map { Glucose(nightStart + it * 60L, 100 + (40 * kotlin.math.sin(it / 30.0)).toInt(), 0.5) }
            val trs = listOf(
                Treatment("e2e-carbs", "carbs", 42.0, nightStart + 3600, "synthetic snack", 1, o = "phone"),
                Treatment("e2e-rapid", "rapid", 3.5, nightStart + 3660, "", 1, o = "phone"),
                Treatment("e2e-long", "long", 12.0, nightStart + 3 * 3600, "", 1, o = "phone"),
                Treatment("e2e-note", "note", 0.0, nightStart + 2 * 3600, "synthetic test note", 1, o = "phone"),
            )
            var state = TidepoolQueue.State(from = nightStart)
            val now = nightStart + 6 * 3600
            val p1 = TidepoolQueue.plan(readings, trs, state, true, true, now)
            assertEquals(48, p1.cbg.size)
            state = TidepoolSync.send(client, dataSet, p1, state, zone, device, "0.0.0-e2e", now) { }
            state.notes.values.forEach { noteIds += it }

            var data = client.data("uploadId=$dataSet&startDate=2021-10-30T00:00:00Z&endDate=2021-11-01T00:00:00Z")
            val byType = data.groupBy { it.jsonObject.s("type") }
            log("stored: " + byType.mapValues { it.value.size })
            assertEquals(48, byType["cbg"]?.size); assertEquals(1, byType["food"]?.size); assertEquals(2, byType["insulin"]?.size)
            val offsets = byType["cbg"]!!.map { it.jsonObject["timezoneOffset"]!!.jsonPrimitive.int }.toSet()
            assertEquals(setOf(60, 120), offsets)
            val cbg = byType["cbg"]!!.first().jsonObject
            // Tidepool normalizes to mmol/L on the way in (factor 18.01559); the mg/dL value comes back exactly
            assertEquals("mmol/L", cbg.s("units"))
            val sent = TidepoolData.perSlot(readings).mapValues { it.value.sgv }   // the earliest reading of each slot
            for (d in byType["cbg"]!!) {
                val o = d.jsonObject
                val t = java.time.Instant.parse(o.s("time")).epochSecond
                assertEquals(sent[TidepoolData.slot(t)], Math.round(o.s("value").toDouble() * 18.01559).toInt())
                assertEquals(t, sent.keys.first { it == TidepoolData.slot(t) } * TidepoolData.SLOT_S + (t % TidepoolData.SLOT_S))
            }
            log("cbg sample: units=${cbg.s("units")} value=${cbg.s("value")} deviceTime=${cbg.s("deviceTime")}")
            assertTrue(client.notes("2021-10-30T00:00:00Z", "2021-11-01T00:00:00Z").any { it.jsonObject["messagetext"]?.jsonPrimitive?.content == "synthetic test note" })

            // the same slots again with other values + an edit, a deletion and a note edit: still one datum per slot
            val again = readings.map { it.copy(sgv = it.sgv + 1) }
            val reSend = TidepoolQueue.plan(again, emptyList(), TidepoolQueue.State(from = nightStart), true, false, now)
            TidepoolSync.send(client, dataSet, reSend, TidepoolQueue.State(from = nightStart), zone, device, "0.0.0-e2e", now) { }
            val edits = listOf(trs[0].copy(a = 50.0, u = 2), trs[1].copy(del = true, u = 2), trs[3].copy(n = "synthetic test note, edited", u = 2))
            val p2 = TidepoolQueue.plan(emptyList(), edits, state, false, true, now)
            assertEquals(1, p2.upserts.size); assertEquals(1, p2.deletes.size); assertEquals(1, p2.noteEdits.size)
            state = TidepoolSync.send(client, dataSet, p2, state, zone, device, "0.0.0-e2e", now) { }

            data = client.data("uploadId=$dataSet&startDate=2021-10-30T00:00:00Z&endDate=2021-11-01T00:00:00Z")
            val after = data.groupBy { it.jsonObject.s("type") }
            log("after resend/edit/delete: " + after.mapValues { it.value.size })
            assertEquals(48, after["cbg"]?.size)
            assertEquals(1, after["insulin"]?.size)
            assertEquals("50.0", after["food"]!!.single().jsonObject["nutrition"]!!.jsonObject["carbohydrate"]!!.jsonObject.s("net").let { "%.1f".format(it.toDouble()) })
            assertTrue(client.notes("2021-10-30T00:00:00Z", "2021-11-01T00:00:00Z").any { it.jsonObject["messagetext"]?.jsonPrimitive?.content == "synthetic test note, edited" })
            val before = expiries(client.session!!.token)
            Thread.sleep(20_000)   // so a sliding refresh window shows as ~20 s, a fixed session maximum as 0
            client.refresh()
            val after2 = expiries(client.session!!.token)
            log("refresh via GET /auth/login: ok; " + lifetimes(client.session!!.token).joinToString())
            log("refresh moved exp by: " + before.zip(after2).joinToString { (a, b) -> "${a.first} ${b.second - a.second} s" })
        } finally {
            noteIds.forEach { runCatching { client.removeNote(it) }.onFailure { e -> log("note delete failed: ${e.message}") } }
            client.deleteDataSet(dataSet)
            log("deleted test data set")
        }
        // nothing of ours is left
        val earlier = ledger.readLines().filter { it.isNotBlank() && !it.startsWith("deleted") && it != dataSet }
        for (id in earlier) {
            runCatching { client.deleteDataSet(id) }
            assertEquals("data left from an earlier run", 0, live(client.data("uploadId=$id")))
        }
        log("earlier runs checked: ${earlier.size}")
        val left = client.data("uploadId=$dataSet")
        val sets = client.dataSets().filter { it.jsonObject["deviceId"]?.jsonPrimitive?.content?.startsWith("Sukrara-e2e-test-") == true }
        val notes = client.notes("2021-10-30T00:00:00Z", "2021-11-01T00:00:00Z")
        log("left: data=${live(left)} (deleted upload records Tidepool keeps: ${left.size - live(left)}) e2e data sets=${sets.size} notes=${notes.size}")
        assertEquals(0, live(left)); assertEquals(0, sets.size); assertEquals(0, notes.size)
        ledger.appendText("deleted $dataSet\n")
        client.logout()
    }

    /**
     * Data still there, not counting the upload record itself once deleted: `DELETE /v1/datasets/{id}` removes the data
     * and keeps the record as a tombstone with `deletedTime` (platform `DeleteDataSet`; no API removes it, same as a
     * deletion in Tidepool web), which `GET /data` still lists.
     */
    private fun live(a: kotlinx.serialization.json.JsonArray) = a.count { it.jsonObject["deletedTime"] == null }

    private fun expiries(token: String): List<Pair<String, Long>> =
        Regex("[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]+").findAll(token).map { m ->
            val c = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(m.value.split('.')[1]))).jsonObject
            (c["typ"]?.jsonPrimitive?.content ?: "?") to (c["exp"]?.jsonPrimitive?.long ?: 0)
        }.toList()

    /** Lifetimes of the JWTs packed in the session token (typ + seconds until exp), never their contents. */
    private fun lifetimes(token: String): List<String> =
        Regex("[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]+").findAll(token).map { m ->
            val claims = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(m.value.split('.')[1]))).jsonObject
            val typ = claims["typ"]?.jsonPrimitive?.content ?: "?"
            val exp = claims["exp"]?.jsonPrimitive?.long ?: 0
            val iat = claims["iat"]?.jsonPrimitive?.long ?: 0
            "$typ lifetime ${if (exp == 0L) "none (no exp)" else "${exp - iat} s"}"
        }.toList()
}
