// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.agp

import java.io.File
import kotlin.system.exitProcess

/**
 *   fixtures            regenerate fixtures/<scenario>.json from Synthetic (readings + expected numbers)
 *   check               recompute every fixture from its readings and compare with its `expect`
 *   render <dir>        <dir>/<name>.png (Java2D, 2×) and <dir>/<name>.page.json (display list for the Mac painter)
 */
fun main(args: Array<String>) {
    val dir = File("fixtures")
    when (args.firstOrNull()) {
        "fixtures" -> for (s in Synthetic.SCENARIOS) {
            val rs = Synthetic.readings(s)
            val r = Agp.report(rs, Period(s.zone, s.first, s.days))
            Fixtures.write(File(dir, "${s.name}.json"), s, rs, r)
            println("${s.name}: ${rs.size} readings, active %.1f %%, profile ${r.sufficiency.profile}".format(r.metrics.active * 100))
        }
        "check" -> {
            var bad = 0
            for (f in dir.listFiles { x -> x.name.endsWith(".json") }!!.sorted()) {
                val fx = Fixtures.read(f)
                val d = Fixtures.diff(fx.expect, Fixtures.expect(Agp.report(fx.readings, fx.period, fx.bands, fx.options)))
                println("${fx.name}: ${if (d.isEmpty()) "ok" else d.take(10).joinToString("\n  ", "\n  ")}")
                bad += d.size
            }
            if (bad > 0) exitProcess(1)
        }
        "render" -> {
            val out = File(args.getOrElse(1) { "build/render" })
            for (f in dir.listFiles { x -> x.name.endsWith(".json") }!!.sorted()) {
                val fx = Fixtures.read(f)
                val page = AgpPage.layout(Agp.report(fx.readings, fx.period, fx.bands, fx.options), "Synthetic data: ${fx.name}")
                Java2dPainter.png(page, File(out, "${fx.name}.png"))
                PageJson.write(page, File(out, "${fx.name}.page.json"))
                println("${fx.name}: ${page.ops.size} ops → ${File(out, fx.name + ".png")}")
            }
        }
        else -> { System.err.println("usage: fixtures | check | render <dir>"); exitProcess(2) }
    }
}
