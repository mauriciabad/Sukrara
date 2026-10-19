// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.nsupload.cli

import dev.spike.nsupload.NsApi
import dev.spike.nsupload.NsAuth
import dev.spike.nsupload.NsConfig
import dev.spike.nsupload.Origin
import dev.spike.nsupload.Reading
import dev.spike.nsupload.Uploader
import dev.spike.nsupload.UploaderBattery
import dev.spike.nsupload.UrlConnectionHttp
import java.io.File
import kotlin.system.exitProcess

private const val MIN = 60_000L
private const val HELP = """nightscout-upload spike (investigation 25) — synthetic data only

  seed     --dir D [--hours 24] [--gap 40..20]   synthetic 1-min readings up to now (gap = minutes ago, left out),
                                                 3 treatments and a sensor start 3 days ago; nothing queued yet
  tick     --dir D                               readings from the last one up to now (as if the sensor delivered them)
  late     --dir D --gap 40..20                  the readings of a gap arrive late (sensor backfill after a BLE loss)
  follow   --dir D [--person P]                  readings of a *followed* person land in the store (role guard demo)
  treat    --dir D add <rapid|long|carbs|note> <amount> [--ago 30] [--note text]
  treat    --dir D edit <id> <amount> | move <id> <minutesAgo> | delete <id> | list
  sync     --dir D --url U [--api v1|v3] [--device Sukrara] [--battery 80] [--relay P] [--accept-conflict]
           secret/token come from the environment, never argv: NS_API_SECRET or NS_TOKEN
  check    --url U [--hours 3]                   what LoopFollow / nightguard / Trio / Home Assistant would read
           (NS_API_SECRET for Trio-style api-secret reads, NS_READ_TOKEN for token= / Bearer reads)
"""

fun main(argv: Array<String>) {
    val a = Args(argv)
    when (a.cmd) {
        "seed" -> seed(a)
        "tick" -> withLocal(a) { val last = it.readings.lastKey(); println("tick: +" + it.synth(last + MIN, System.currentTimeMillis(), inbox = true)) }
        "late" -> withLocal(a) { val (from, to) = a.gap()!!; println("late: +" + it.synth(from, to, inbox = true)) }
        "follow" -> withLocal(a) {
            val now = System.currentTimeMillis() - System.currentTimeMillis() % MIN
            val p = a.opt("person") ?: "friend-stream-key"
            for (i in 0 until 10) { val t = now - i * MIN + 30_000; it.readings[t] = Reading(t, 222, 0.0, Origin.FOLLOWED, p); it.inboxReadings += t }
            println("follow: 10 readings of '$p' (FOLLOWED) at :30 s")
        }
        "treat" -> treat(a)
        "sync" -> sync(a)
        "check" -> exitProcess(FollowerCheck(a.req("url"), a.opt("hours")?.toInt() ?: 3).run())
        else -> { print(HELP); exitProcess(if (a.cmd == "help") 0 else 2) }
    }
}

private fun seed(a: Args) {
    val dir = File(a.req("dir"))
    File(dir, "local.json").delete(); dir.listFiles { f -> f.name.startsWith("uploader-") }?.forEach { it.delete() }
    val l = LocalFile(File(dir, "local.json"))
    val now = System.currentTimeMillis()
    val gap = a.gap()
    val n = l.synth(now - a.opt("hours")!!.toLong() * 60 * MIN, now, inbox = false) { t -> gap != null && t in gap.first..gap.second }
    l.inboxTreatments.clear()
    l.addTreatment("carbs", 45.0, now - 95 * MIN, "breakfast")
    l.addTreatment("rapid", 4.5, now - 100 * MIN)
    l.addTreatment("long", 14.0, now - 13 * 60 * MIN)
    l.sensor = dev.spike.nsupload.SensorStart("SYNTH0001", now - 3 * 24 * 60 * MIN); l.inboxSensor = true
    l.save()
    println("seed: $n readings (${gap?.let { "gap ${(now - it.first) / MIN}..${(now - it.second) / MIN} min ago" } ?: "no gap"}), 3 treatments, sensor start")
}

private fun treat(a: Args) = withLocal(a) { l ->
    val p = a.pos
    when (p.getOrNull(0)) {
        "add" -> {
            val t = l.addTreatment(p[1], p[2].toDouble(), System.currentTimeMillis() - (a.opt("ago")?.toLong() ?: 0) * MIN, a.opt("note") ?: "")
            println("added ${t.id} ${t.kind} ${t.amount}")
        }
        "edit" -> l.treatments[p[1]]!!.let { l.treatments[it.id] = it.copy(amount = p[2].toDouble(), updatedMs = System.currentTimeMillis()); l.inboxTreatments += it.id }
        "move" -> l.treatments[p[1]]!!.let { l.treatments[it.id] = it.copy(epochMs = System.currentTimeMillis() - p[2].toLong() * MIN, updatedMs = System.currentTimeMillis()); l.inboxTreatments += it.id }
        "delete" -> l.treatments[p[1]]!!.let { l.treatments[it.id] = it.copy(deleted = true, updatedMs = System.currentTimeMillis()); l.inboxTreatments += it.id }
        else -> l.treatments.values.forEach { println("${it.id} ${it.kind} ${it.amount} ${(System.currentTimeMillis() - it.epochMs) / MIN} min ago${if (it.deleted) " (deleted)" else ""}") }
    }
}

private fun sync(a: Args) {
    val dir = File(a.req("dir"))
    val l = LocalFile(File(dir, "local.json"))
    val auth = System.getenv("NS_TOKEN")?.takeIf { it.isNotEmpty() }?.let { NsAuth.AccessToken(it) }
        ?: System.getenv("NS_API_SECRET")?.takeIf { it.isNotEmpty() }?.let { NsAuth.ApiSecret(it) }
        ?: run { System.err.println("set NS_API_SECRET or NS_TOKEN"); exitProcess(2) }
    val api = if (a.opt("api") == "v3") NsApi.V3 else NsApi.V1
    val cfg = NsConfig(a.req("url"), auth, api, device = a.opt("device") ?: "Sukrara", relayPerson = a.opt("relay"))
    val up = Uploader(cfg, l, FileStateStore(File(dir, "uploader-${api.name.lowercase()}.json")), UrlConnectionHttp(),
        log = { println("  · $it") })
    if (a.flag("accept-conflict")) up.acceptConflict()
    up.onReadings(l.inboxReadings.mapNotNull { l.readings[it] })
    l.inboxTreatments.mapNotNull { l.treatments[it] }.forEach(up::onTreatmentChanged)
    if (l.inboxSensor) l.sensor?.let(up::onSensorStart)
    a.opt("battery")?.let { up.onBattery(UploaderBattery(it.toInt(), false, System.currentTimeMillis())) }
    l.inboxReadings.clear(); l.inboxTreatments.clear(); l.inboxSensor = false
    l.save()   // handed over: the uploader's own queue file owns them now
    val t0 = System.nanoTime()
    val rep = up.sync()
    println("sync ${api.name} ${auth.javaClass.simpleName}: $rep  (${(System.nanoTime() - t0) / 1_000_000} ms)")
    if (rep.health.name in setOf("AUTH_FAILED", "BACKING_OFF")) exitProcess(3)
}

private fun withLocal(a: Args, f: (LocalFile) -> Unit) {
    val l = LocalFile(File(a.req("dir"), "local.json"))
    f(l)
    l.save()
}

private class Args(argv: Array<String>) {
    val cmd = argv.firstOrNull() ?: "help"
    private val opts = HashMap<String, String?>()
    val pos = ArrayList<String>()

    init {
        var i = 1
        while (i < argv.size) {
            val s = argv[i]
            if (s.startsWith("--")) {
                val k = s.removePrefix("--")
                if (i + 1 < argv.size && !argv[i + 1].startsWith("--")) { opts[k] = argv[i + 1]; i++ } else opts[k] = null
            } else pos += s
            i++
        }
        if (cmd == "seed") opts.putIfAbsent("hours", "24")
    }

    fun opt(k: String) = opts[k]
    fun flag(k: String) = opts.containsKey(k)
    fun req(k: String) = opts[k] ?: run { System.err.println("missing --$k"); exitProcess(2) }

    /** `--gap 40..20` → [now-40 min, now-20 min] */
    fun gap(): Pair<Long, Long>? = opts["gap"]?.split("..")?.let { (f, t) ->
        val now = System.currentTimeMillis()
        (now - f.toLong() * MIN) to (now - t.toLong() * MIN)
    }
}
