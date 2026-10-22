// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.novopen.engine

import java.io.File
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Runs spikes/novopen-android/scenarios.json (DESIGN.md §3–§4.8) through [PenEngine]. The same driver runs in Sukrara
 * (`PenScenarioTest`, package renamed): the fixture is the contract.
 */
class PenScenarioTest {
    private val fixture: File = listOf(File("scenarios.json"), File("../../novopen-android/scenarios.json"), File("../novopen-android/scenarios.json"))
        .first { it.isFile }

    private val d0 = ZonedDateTime.of(2026, 10, 20, 0, 0, 0, 0, ZoneOffset.UTC).toEpochSecond()
    private val clock0 = d0 - 400 * 86400L

    private fun time(s: String): Long {
        var day = 0L; var hm = s
        if (s.startsWith("D")) { val sp = s.indexOf(' '); day = s.substring(1, sp).toLong(); hm = s.substring(sp + 1) }
        return d0 + day * 86400 + LocalTime.parse(hm).toSecondOfDay()
    }

    private class PenSim(var offset: Long = 0) { val mem = mutableListOf<Triple<Long, Double, Boolean>>() }

    @Test fun scenarios() {
        val root = Json.parseToJsonElement(fixture.readText()).jsonObject
        val failures = mutableListOf<String>()
        val list = root.getValue("scenarios").jsonArray
        for (sc in list) {
            val name = sc.jsonObject.getValue("name").jsonPrimitive.content
            try { run(sc.jsonObject) } catch (e: Throwable) { failures += "$name: ${e.message}" }
        }
        if (failures.isNotEmpty()) fail("${failures.size}/${list.size} scenarios failed:\n" + failures.joinToString("\n"))
        println("pen scenarios: ${list.size} passed")
    }

    private fun run(sc: JsonObject) {
        var n = 0
        val rules = sc["rules"]?.jsonObject?.get("primeBelow")?.jsonPrimitive?.double?.let { PenRules(primeBelow = it) } ?: PenRules()
        val engine = PenEngine(rules, newId = { "n${++n}" })
        val first = sc["first"]?.jsonPrimitive?.boolean == true
        val entries = LinkedHashMap<String, PenEntry>()
        sc["entries"]?.jsonArray?.forEach { e ->
            val o = e.jsonObject
            val id = o.getValue("id").jsonPrimitive.content
            entries[id] = PenEntry(id = id, k = o.getValue("k").jsonPrimitive.content, a = o.getValue("a").jsonPrimitive.double,
                t = time(o.getValue("t").jsonPrimitive.content), n = o["n"]?.jsonPrimitive?.content ?: "", u = 1000,
                o = o["o"]?.jsonPrimitive?.content ?: "phone")
        }
        val sims = mutableMapOf<String, PenSim>()
        val pens = mutableMapOf<String, Pen>()
        val kinds = sc["pens"]?.jsonArray?.associate { it.jsonObject.getValue("serial").jsonPrimitive.content to (it.jsonObject["kind"]?.jsonPrimitive?.content ?: "rapid") } ?: emptyMap()
        if (!first) {
            val serials = sc.getValue("steps").jsonArray.mapNotNull { it.jsonObject["scan"]?.jsonPrimitive?.content }.toSet() + kinds.keys
            serials.forEach { s -> pens[s] = Pen(serial = s, kind = kinds[s] ?: "rapid", since = time("D-30 00:00"), first = time("D-30 00:00")) }
        }
        var ledger = Ledger(pens)
        var last: Outcome? = null
        var offer: Offer? = null
        var removed: PenEntry? = null
        fun apply(o: Outcome) { last = o; ledger = o.ledger; o.writes.forEach { entries[it.id] = it } }
        fun live() = entries.values.filter { !it.del }.sortedWith(compareBy({ it.t }, { it.id }))
        fun ref(r: String) = if (r.startsWith("@")) live().filter { it.pen != null }[r.substring(1).toInt() - 1] else entries.getValue(r)
        fun openQs() = ledger.open().sortedBy { q -> q.keys.minOf { k -> ledger.pens.getValue(q.serial).doses.getValue(k).t } }
        fun keyAt(serial: String, at: String) = ledger.pens.getValue(serial).doses.entries.first { it.value.t == time(at) && it.value.role != PenEngine.REPLACED }.key

        sc.getValue("steps").jsonArray.forEachIndexed { i, st0 ->
            val st = st0.jsonObject
            val at = st["at"]?.jsonPrimitive?.content?.let { time(it) } ?: 0L
            val ms = at * 1000
            val where = "step ${i + 1}"
            when {
                "scan" in st -> {
                    val serial = st.getValue("scan").jsonPrimitive.content
                    val sim = sims.getOrPut(serial) { PenSim() }
                    st["add"]?.jsonArray?.forEach { d ->
                        if (d is JsonArray) sim.mem += Triple(time(d[0].jsonPrimitive.content), d[1].jsonPrimitive.double, d.size > 2)
                        else { val g = d.jsonObject; val from = time(g.getValue("from").jsonPrimitive.content)
                            repeat(g.getValue("n").jsonPrimitive.int) { k -> sim.mem += Triple(from + k * g.getValue("every").jsonPrimitive.long, g.getValue("a").jsonPrimitive.double, false) } }
                    }
                    if (st["reset"]?.jsonPrimitive?.boolean == true) sim.offset -= 50_000_000
                    val jitter = st["jitter"]?.jsonPrimitive?.long ?: 0
                    val doses = sim.mem.map { (t, a, inc) -> ReadDose(rel = t - clock0 + sim.offset, t = t + jitter, a = a, incomplete = inc) }
                    apply(engine.scan(ledger, PenScan(serial, at, at - clock0 + sim.offset, doses), entries.values.toList(), ms))
                    offer = last!!.report.offer
                }
                "refresh" in st -> { val t = time(st.getValue("refresh").jsonPrimitive.content); apply(engine.refresh(ledger, entries.values.toList(), t, t * 1000)) }
                "answer" in st -> {
                    val q = openQs()[st.getValue("answer").jsonPrimitive.int]
                    apply(when {
                        "suggested" in st -> engine.answerDoses(ledger, q.id, q.keys.filter { ledger.pens.getValue(q.serial).doses.getValue(it).p }.toSet(), entries.values.toList(), at, ms)
                        "primed" in st -> engine.answerDoses(ledger, q.id, st.getValue("primed").jsonArray.map { keyAt(q.serial, it.jsonPrimitive.content) }.toSet(), entries.values.toList(), at, ms)
                        "same" in st -> engine.answerMatch(ledger, q.id, st.getValue("same").jsonPrimitive.content, entries.values.toList(), at, ms)
                        else -> engine.answerMatch(ledger, q.id, null, entries.values.toList(), at, ms)
                    })
                }
                "log" in st -> {
                    val serial = st["pen"]?.jsonPrimitive?.content ?: "P1"
                    val primed = st.getValue("primed").jsonArray.map { it.jsonPrimitive.content }.toSet()
                    val doses = st.getValue("log").jsonArray.associate { keyAt(serial, it.jsonPrimitive.content) to (it.jsonPrimitive.content in primed) }
                    apply(engine.logDoses(ledger, serial, doses, entries.values.toList(), at, ms))
                }
                "logOffer" in st -> apply(engine.logDoses(ledger, "P1", offer!!.doses, entries.values.toList(), at, ms))
                "logReview" in st -> apply(engine.logReview(ledger, st.getValue("logReview").jsonPrimitive.content, entries.values.toList(), at, ms))
                "skipReview" in st -> apply(engine.skipReview(ledger, st.getValue("skipReview").jsonPrimitive.content, ms))
                "remove" in st -> { val e = ref(st.getValue("remove").jsonPrimitive.content); removed = e; apply(engine.removeEntry(ledger, e.pen!!, e.id, entries.values.toList(), ms)) }
                "restore" in st -> apply(engine.restore(ledger, removed!!.pen!!, removed!!, entries.values.toList(), ms))
                "undo" in st -> apply(engine.undo(ledger, last!!.undo!!, entries.values.toList(), ms))
                "delete" in st -> { val e = ref(st.getValue("delete").jsonPrimitive.content); entries[e.id] = e.copy(del = true, u = maxOf(ms, e.u + 1)) }
                "editA" in st -> { val e = ref(st.getValue("editA").jsonPrimitive.content); entries[e.id] = e.copy(a = st.getValue("a").jsonPrimitive.double, u = maxOf(ms, e.u + 1)) }
                "edit" in st -> { val e = ref(st.getValue("edit").jsonPrimitive.content); entries[e.id] = e.copy(n = st.getValue("n").jsonPrimitive.content, u = maxOf(ms, e.u + 1)) }
                "correct" in st -> {
                    val e = ref(st.getValue("correct").jsonPrimitive.content)
                    val primed = st.getValue("primed").jsonArray.map { time(it.jsonPrimitive.content) }.toSet()
                    val c = PenEngine.correct(e, e.pd!!.map { it.t in primed })
                    if (st["expectNull"]?.jsonPrimitive?.boolean == true) { assertNull("$where: correct()", c); return@forEachIndexed }
                    entries[e.id] = c!!.copy(u = if (st["keepU"]?.jsonPrimitive?.boolean == true) e.u else maxOf(ms, e.u + 1))
                }
                "forget" in st -> ledger = engine.forget(ledger, st.getValue("forget").jsonPrimitive.content)
                "kind" in st -> ledger = engine.update(ledger, st.getValue("kind").jsonPrimitive.content) { it.copy(kind = st.getValue("to").jsonPrimitive.content) }
                "lose" in st -> ledger = Ledger()
                "near" in st -> {
                    val q = st.getValue("near").jsonObject
                    val got = PenEngine.near(entries.values.toList(), q.getValue("k").jsonPrimitive.content, time(q.getValue("t").jsonPrimitive.content))
                    val want = (st["is"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.let { ref(it).id }
                    assertEquals("$where: near", want, got?.id)
                }
                "expect" in st -> {
                    st.getValue("expect").jsonObject["deleted"]?.jsonArray?.forEach { d -> assertEquals("$where: ${d.jsonPrimitive.content} deleted", true, entries[d.jsonPrimitive.content]?.del) }
                    expect(where, st.getValue("expect").jsonObject, live(), openQs(), ledger, last, ::time) { s -> engine.history(ledger, s, entries.values.toList()) }
                }
                else -> error("$where: unknown step $st")
            }
        }
    }

    private fun expect(where: String, x: JsonObject, live: List<PenEntry>, qs: List<Question>, ledger: Ledger, last: Outcome?,
                       time: (String) -> Long, history: (String) -> List<Session>) {
        x["entries"]?.jsonArray?.let { want ->
            assertEquals("$where: entries ${live.map { "${it.k} ${it.a}@${it.t - d0} pr=${it.pr} pen=${it.pen}" }}", want.size, live.size)
            want.forEachIndexed { j, w0 ->
                val w = w0.jsonObject; val e = live[j]; val at = "$where entry ${j + 1}"
                w["id"]?.let { assertEquals("$at id", it.jsonPrimitive.content, e.id) }
                w["k"]?.let { assertEquals("$at k", it.jsonPrimitive.content, e.k) }
                w["t"]?.let { assertEquals("$at t", time(it.jsonPrimitive.content), e.t) }
                w["a"]?.let { assertEquals("$at a", it.jsonPrimitive.double, e.a, 1e-9) }
                w["pr"]?.let { assertEquals("$at pr", it.jsonPrimitive.double, e.pr ?: 0.0, 1e-9) }
                w["n"]?.let { assertEquals("$at n", it.jsonPrimitive.content, e.n) }
                w["o"]?.let { assertEquals("$at o", it.jsonPrimitive.content, e.o) }
                w["pdCount"]?.let { assertEquals("$at pd size", it.jsonPrimitive.int, e.pd?.size) }
                if ("pen" in w) assertEquals("$at pen", (w["pen"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content, e.pen)
                w["pd"]?.jsonArray?.let { pd ->
                    assertEquals("$at pd", pd.map { val a = it.jsonArray; Triple(time(a[0].jsonPrimitive.content), a[1].jsonPrimitive.double, a[2].jsonPrimitive.boolean) },
                        e.pd?.map { Triple(it.t, it.a, it.p) })
                }
            }
        }
        x["questions"]?.jsonArray?.let { want ->
            assertEquals("$where: questions ${qs.map { it.type + it.keys }}", want.size, qs.size)
            want.forEachIndexed { j, w0 ->
                val w = w0.jsonObject; val q = qs[j]
                w["type"]?.let { assertEquals("$where q${j + 1} type", it.jsonPrimitive.content, q.type) }
                w["doses"]?.jsonArray?.let { ds ->
                    val doses = ledger.pens.getValue(q.serial).doses
                    assertEquals("$where q${j + 1} doses", ds.map { time(it.jsonArray[0].jsonPrimitive.content) to it.jsonArray[1].jsonPrimitive.double },
                        q.keys.map { doses.getValue(it).let { d -> d.t to d.a } })
                }
                w["cands"]?.jsonArray?.let { c -> assertEquals("$where q${j + 1} cands", c.map { it.jsonPrimitive.content }, q.cands) }
                w["suggest"]?.jsonArray?.let { sg ->
                    val doses = ledger.pens.getValue(q.serial).doses
                    assertEquals("$where q${j + 1} suggested priming", sg.map { time(it.jsonPrimitive.content) }, q.keys.map { doses.getValue(it) }.filter { it.p }.map { it.t })
                }
            }
        }
        x["writes"]?.let { assertEquals("$where: writes", it.jsonPrimitive.int, last!!.writes.size) }
        x["report"]?.jsonObject?.forEach { (k, v) ->
            val r = last!!.report
            val got: Any = when (k) {
                "created" -> r.created.size; "updated" -> r.updated.size; "merged" -> r.merged.size; "asked" -> r.asked.size
                "first" -> r.first; "pending" -> r.pending; "older" -> r.older; "future" -> r.future; "clockMatched" -> r.clockMatched
                "restored" -> r.restored; "logged" -> r.logged; "primed" -> r.primed
                "returning" -> r.returning; "clockChanged" -> r.clockChanged; "review" -> r.review
                "reviewSince" -> r.reviewSince ?: "null"; "bigSince" -> r.bigSince ?: "null"; "offerA" -> r.offer?.a ?: "null"; "offerMatch" -> r.offer?.match ?: "null"
                else -> error("unknown report key $k")
            }
            val want: Any = v.jsonPrimitive.let { p -> when {
                p is JsonNull -> "null"
                k == "reviewSince" || k == "bigSince" -> time(p.content)
                k == "offerMatch" -> p.content
                else -> p.booleanOrNull ?: if (got is Double) p.double else p.int
            } }
            assertEquals("$where: report.$k", want, got)
        }
        x["pens"]?.jsonObject?.forEach { (s, v) ->
            val p = ledger.pens.getValue(s)
            v.jsonObject["since"]?.let { assertEquals("$where: $s since", time(it.jsonPrimitive.content), p.since) }
            v.jsonObject["older"]?.let { assertEquals("$where: $s older", it.jsonPrimitive.int, p.older) }
            v.jsonObject["kind"]?.let { assertEquals("$where: $s kind", it.jsonPrimitive.content, p.kind) }
        }
        x["history"]?.jsonArray?.let { want ->
            val serial = ledger.pens.keys.first()
            val h = history(serial)
            assertEquals("$where: history ${h.map { "${it.status} ${it.a}/${it.pr}" }}", want.size, h.size)
            want.forEachIndexed { j, w0 ->
                val w = w0.jsonObject; val s = h[j]
                w["status"]?.let { assertEquals("$where history ${j + 1} status", it.jsonPrimitive.content, s.status) }
                w["a"]?.let { assertEquals("$where history ${j + 1} a", it.jsonPrimitive.double, s.a, 1e-9) }
                w["pr"]?.let { assertEquals("$where history ${j + 1} pr", it.jsonPrimitive.double, s.pr, 1e-9) }
                w["incomplete"]?.let { assertEquals("$where history ${j + 1} incomplete", it.jsonPrimitive.boolean, s.incomplete) }
            }
        }
        if ("wake" in x) assertEquals("$where: wake", (x["wake"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.let(time), PenEngine.nextWake(ledger))
    }
}
