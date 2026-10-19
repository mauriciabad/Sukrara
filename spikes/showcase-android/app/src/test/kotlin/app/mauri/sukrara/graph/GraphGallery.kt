// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.treatments.Treatment
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Investigation 16's picture sheets: the real renderer ([GlucoseCard], [GlucoseGraph], [TirRing]) on synthetic data,
 * drawn on the JVM with Robolectric's native graphics. `./gradlew testDebugUnitTest --tests '*GraphGallery*'
 * -Dgallery.out=…` writes the PNGs (default: app/build/gallery).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class GraphGallery {
    private val out = File(System.getProperty("gallery.out") ?: "build/gallery").also { it.mkdirs() }
    private val d = 2f   // px per dp in the sheets

    /** What App.onCreate sets up for drawing (the rounded numbers, the words); Robolectric tests run on a plain Application. */
    @org.junit.Before fun drawingSetup() {
        val a = org.robolectric.RuntimeEnvironment.getApplication()
        RoundedFont.init(a)
        app.mauri.sukrara.core.Words.loader = { app.mauri.sukrara.core.Words.of(a.resources) }
    }

    /** 22:14 local on 2026-10-17: the mock-up's "10 PM" end. */
    private val now = java.util.Calendar.getInstance().run {
        clear(); set(2026, 9, 17, 22, 14); timeInMillis / 1000
    }

    @Test fun real() {
        val f = System.getProperty("gallery.real")?.let(::File)?.takeIf { it.exists() } ?: return
        val arr = org.json.JSONArray(f.readText())
        val data = (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Reading(it.getLong("date") / 1000, it.getInt("sgv"), it.optDouble("delta", Double.NaN).takeIf { d -> !d.isNaN() }) }
            .sortedBy { it.t }
        val end = data.last().t + 20
        val pal = GraphPalette.DARK
        sheet("real-card", listOf(Tile("your last 12 h", "") { cv, w, h ->
            GlucoseCard.draw(cv, w, h, data, emptyList(), end, CardStyle(rings = listOf(RingSpec(86400), RingSpec(86400, TirRange.TIGHT))), pal, d)
        }), 560f, 270f, cols = 1, pal)
    }

    @Test fun mockup() {
        val data = Synthetic.days(now, 15)
        for (dark in listOf(true, false)) {
            val pal = GraphPalette.of(dark)
            sheet("card-${if (dark) "dark" else "light"}", listOf(Tile("12 h · rings 1d + 7d", "") { cv, w, h ->
                GlucoseCard.draw(cv, w, h, data, Synthetic.treatments(now), now, CardStyle(graph = GraphStyle(hour24 = false)), pal, d)
            }), 560f, 270f, cols = 1, pal)
        }
    }

    @Test fun freshness() {
        val data = Synthetic.days(now, 15)
        val pal = GraphPalette.DARK
        val ages = listOf(30L to "fresh · 30 s", 3 * 60L to "late · 3 min", 12 * 60L to "stale · 12 min",
            75 * 60L to "old · 75 min", 5 * 3600L to "gone · 5 h")
        sheet("freshness", ages.map { (age, label) ->
            Tile(label, "") { cv, w, h ->
                GlucoseCard.draw(cv, w, h, data.filter { it.t <= now - age }, emptyList(), now, CardStyle(graph = GraphStyle(hour24 = false)), pal, d)
            }
        }, 360f, 190f, cols = 2, pal)
    }

    @Test fun rings() {
        val data = Synthetic.days(now, 15)
        val pal = GraphPalette.DARK
        val day = 86400L
        val v = listOf(
            "1 ring · in range" to listOf(RingSpec(day)),
            "2 rings · 1d + 7d" to listOf(RingSpec(day), RingSpec(7 * day)),
            "3 rings · 1d + 7d + 14d" to listOf(RingSpec(day), RingSpec(7 * day), RingSpec(14 * day)),
            "3 rings · very good 70–140" to listOf(RingSpec(day, TirRange.TIGHT), RingSpec(7 * day, TirRange.TIGHT), RingSpec(14 * day, TirRange.TIGHT)),
        )
        sheet("rings", v.map { (label, rings) ->
            Tile(label, "") { cv, w, h -> GlucoseCard.draw(cv, w, h, data, emptyList(), now, CardStyle(graph = null, rings = rings), pal, d) }
        }, 360f, 76f, cols = 2, pal)
    }

    /** The ring's number next to the > 70 % goal (ShareText): 70.2 not "70", 8.3 under 10 %, 86 whole. */
    @Test fun ringShares() {
        val pal = GraphPalette.DARK
        fun tir(inRange: Double, tight: Double) = TimeInRange(0.01, 0.03, tight, inRange - tight, 0.96 - inRange, 0.0, 1.0)
        val v = listOf(
            "in range 70.2 %" to (tir(0.702, 0.4) to RingSpec(86400L)),
            "in range 69.8 %" to (tir(0.698, 0.4) to RingSpec(86400L)),
            "very good 8.3 %" to (tir(0.3, 0.083) to RingSpec(86400L, TirRange.TIGHT)),
            "in range 86 %" to (tir(0.857, 0.5) to RingSpec(7 * 86400L)),
        )
        sheet("ring-shares", v.map { (label, p) ->
            Tile(label, "") { cv, w, h -> TirRing.draw(cv, w / 2, h / 2, h / 2 - 4 * d, p.first, p.second, pal, 1f, d) }
        }, 120f, 76f, cols = 4, pal)
    }

    /** Investigation 18: report numbers in the slots. */
    @Test fun slots() {
        val data = Synthetic.days(now, 31)
        for (dark in listOf(true, false)) {
            val pal = GraphPalette.of(dark)
            val day = 86400L
            val v = listOf(
                "TIR 1d · TIR 7d · GMI 14d (faded)" to listOf(RingSpec(day), RingSpec(7 * day), RingSpec(14 * day, kind = SlotKind.GMI)),
                "… GMI 30d at full strength" to listOf(RingSpec(day), RingSpec(7 * day), RingSpec(30 * day, kind = SlotKind.GMI, faded = false)),
                "GMI · average · CV, 14d" to listOf(RingSpec(14 * day, kind = SlotKind.GMI), RingSpec(14 * day, kind = SlotKind.AVERAGE, faded = false),
                    RingSpec(14 * day, kind = SlotKind.CV, faded = false)),
            )
            sheet("slots-${if (dark) "dark" else "light"}", v.map { (label, rings) ->
                Tile(label, "") { cv, w, h -> GlucoseCard.draw(cv, w, h, data, emptyList(), now, CardStyle(graph = null, rings = rings), pal, d) }
            }, 440f, 76f, cols = 1, pal)
            sheet("widget-gmi-${if (dark) "dark" else "light"}", listOf(Tile("4×2 combined, GMI 30d in the third slot", "") { cv, w, h ->
                GlucoseCard.draw(cv, w, h, data, Synthetic.treatments(now), now, CardStyle(graph = GraphStyle(hour24 = true),
                    rings = listOf(RingSpec(day), RingSpec(7 * day), RingSpec(30 * day, kind = SlotKind.GMI, faded = false))), pal, d)
            }), 340f, 170f, cols = 1, pal)
        }
    }

    @Test fun sizes() {
        val data = Synthetic.days(now, 15)
        for (dark in listOf(true, false)) {
            val pal = GraphPalette.of(dark)
            val g = GraphStyle(hour24 = true)
            val v = listOf(
                Triple("4×2 combined", 300f to 150f, CardStyle(graph = g)),
                Triple("4×1 combined (side by side)", 300f to 70f, CardStyle(graph = g)),
                Triple("5×3 combined, 24 h", 380f to 230f, CardStyle(graph = g.copy(windowS = 24 * 3600L))),
                Triple("4×2 graph only, 6 h", 300f to 150f, CardStyle(graph = g.copy(windowS = 6 * 3600L), value = false)),
                Triple("2×1 value + 1 ring", 150f to 60f, CardStyle(graph = null, rings = listOf(RingSpec(86400)))),
                Triple("3×1 value + 2 rings", 230f to 60f, CardStyle(graph = null)),
                Triple("notification (no value, 12 h)", 340f to 110f, CardStyle(graph = g.copy(treatments = true), value = false)),
                Triple("the same 4×2 widget, reconfigured: 3 h, 1 ring (very good), no side", 300f to 150f,
                    CardStyle(graph = g.copy(windowS = 3 * 3600L, side = false), rings = listOf(RingSpec(86400, TirRange.TIGHT)))),
            )
            sheet("sizes-${if (dark) "dark" else "light"}", v.map { (label, sz, st) ->
                Tile(label, "", sz.first, sz.second) { cv, w, h -> GlucoseCard.draw(cv, w, h, data, Synthetic.treatments(now), now, st, pal, d) }
            }, 380f, 230f, cols = 2, pal)
        }
    }

    /** Round sensor-life: the badge (13d / 20h / 5h with the countdown ring / ended), the warm-up and the ended card. */
    @Test fun sensorLife() {
        val data = Synthetic.days(now, 15)
        val len = 15 * 86_400L
        fun sensor(left: Long) = app.mauri.sukrara.core.SensorInfo("S", now + left - len, now + left)
        val fresh = app.mauri.sukrara.core.SensorLife.fromStart("S2", now - 18 * 60, 0)
        for (dark in listOf(true, false)) {
            val pal = GraphPalette.of(dark)
            val g = GraphStyle(hour24 = true)
            val v = listOf(
                Triple("13 days left", 13 * 86_400L + 5 * 3600, data),
                Triple("20 h left (8 h window: no ring)", 20 * 3600L, data),
                Triple("5 h left: the countdown ring", 5 * 3600L, data),
                Triple("45 min left", 45 * 60L, data),
            )
            val tiles = v.map { (label, left, rd) ->
                Tile("4×2 · $label", "", 300f, 150f) { cv, w, h ->
                    GlucoseCard.draw(cv, w, h, rd, emptyList(), now, CardStyle(graph = g), pal, d, sensor = sensor(left))
                }
            } + listOf(
                Tile("warming up, 18 min in (old sensor's data)", "", 300f, 150f) { cv, w, h ->
                    GlucoseCard.draw(cv, w, h, data.filter { it.t <= now - 40 * 60 }, emptyList(), now, CardStyle(graph = g), pal, d, sensor = fresh)
                },
                Tile("ended 2 h ago, no readings since", "", 300f, 150f) { cv, w, h ->
                    GlucoseCard.draw(cv, w, h, data.filter { it.t <= now - 2 * 3600 }, emptyList(), now, CardStyle(graph = g), pal, d,
                        sensor = sensor(-2 * 3600L))
                },
                Tile("3×1 · 5 h left", "", 230f, 60f) { cv, w, h ->
                    GlucoseCard.draw(cv, w, h, data, emptyList(), now, CardStyle(graph = null), pal, d, sensor = sensor(5 * 3600L))
                },
                Tile("3×1 · ended 10 min ago, last value still fresh", "", 230f, 60f) { cv, w, h ->
                    GlucoseCard.draw(cv, w, h, data.filter { it.t <= now - 600 }, emptyList(), now, CardStyle(graph = null), pal, d,
                        sensor = sensor(-600L))
                },
                Tile("2×1 · ended 10 min ago", "", 150f, 60f) { cv, w, h ->
                    GlucoseCard.draw(cv, w, h, data.filter { it.t <= now - 600 }, emptyList(), now, CardStyle(graph = null, rings = listOf(RingSpec(86400))),
                        pal, d, sensor = sensor(-600L))
                },
                Tile("4×1 side by side · ended yesterday", "", 300f, 70f) { cv, w, h ->
                    GlucoseCard.draw(cv, w, h, data.filter { it.t <= now - 26 * 3600 }, emptyList(), now, CardStyle(graph = GraphStyle(hour24 = true)),
                        pal, d, sensor = sensor(-26 * 3600L))
                },
            )
            sheet("sensor-${if (dark) "dark" else "light"}", tiles, 300f, 150f, cols = 2, pal)
        }
    }

    /** 2026-10-18 round: the status badge under the value, at the widget sizes and in both themes. */
    @Test fun badge() {
        val base = Synthetic.days(now, 31)
        val a = AlertSettings()
        val day = 86400L
        fun tr(k: String, a: Double, minAgo: Int) = Treatment(id = "$k$minAgo", t = now - minAgo * 60L, k = k, a = a, u = 0, o = "phone")
        val states = listOf(
            Triple("in range: no badge", Synthetic.endAt(base, now, 120), emptyList<Treatment>()),
            Triple("low (62), nothing logged: eat carbs", Synthetic.endAt(base, now, 62), emptyList()),
            Triple("low, 15g logged 6 min ago: muted", Synthetic.endAt(base, now, 62), listOf(tr("carbs", 15.0, 6))),
            Triple("high (212), nothing logged: take insulin", Synthetic.endAt(base, now, 212), emptyList()),
            Triple("high, 3u 22 min ago: muted", Synthetic.endAt(base, now, 212), listOf(tr("rapid", 3.0, 22))),
            Triple("extremely high (318) after insulin: never muted", Synthetic.endAt(base, now, 318), listOf(tr("rapid", 4.0, 10))),
        )
        for (dark in listOf(true, false)) {
            val pal = GraphPalette.of(dark)
            val g = GraphStyle(hour24 = true, windowS = 6 * 3600L)
            val sizes = listOf(
                Triple("4×2 combined", 300f to 150f, CardStyle(graph = g)),
                Triple("3×1 value + 2 rings", 230f to 60f, CardStyle(graph = null)),
                Triple("2×1 value + 1 ring", 150f to 60f, CardStyle(graph = null, rings = listOf(RingSpec(day)))),
                Triple("4×1 side by side", 300f to 70f, CardStyle(graph = g)),
            )
            for ((si, sz) in sizes.withIndex()) {
                sheet("badge-${si + 1}-${if (dark) "dark" else "light"}", states.map { (label, data, trs) ->
                    Tile("${sz.first} · $label", "", sz.second.first, sz.second.second) { cv, w, h ->
                        GlucoseCard.draw(cv, w, h, data, trs, now, sz.third, pal, d, alerts = a)
                    }
                }, 300f, 150f, cols = 2, pal)
            }
        }
    }

    /** 2026-10-18 round: the last-treatment slot (states, places, fallback) and the marks' times and mute lines. */
    @Test fun lastTreatment() {
        val data = Synthetic.endAt(Synthetic.days(now, 31), now, 150)
        val a = AlertSettings()
        val day = 86400L
        fun tr(k: String, a: Double, minAgo: Int) = Treatment(id = "$k$minAgo", t = now - minAgo * 60L, k = k, a = a, u = 0, o = "phone")
        val rings = listOf(RingSpec(day), RingSpec(7 * day))
        val fallback = RingSpec(30 * day)
        val states = listOf(
            "rapid 4.5u, 12 min ago (mute 60 min)" to listOf(tr("rapid", 4.5, 12), tr("carbs", 45.0, 12)),
            "carbs 15g, 10 min ago (mute 15 min)" to listOf(tr("carbs", 15.0, 10)),
            "rapid 6u, 2 h ago: mute over, grey ring" to listOf(tr("rapid", 6.0, 125)),
            "rapid 6u, 5 h ago: hidden → 30d ring" to listOf(tr("rapid", 6.0, 300)),
            "long-acting only: not shown → 30d ring" to listOf(tr("long", 18.0, 40)),
        )
        for (dark in listOf(true, false)) {
            val pal = GraphPalette.of(dark)
            sheet("last-${if (dark) "dark" else "light"}", states.flatMap { (label, trs) ->
                listOf(
                    Tile("3×1 · $label", "", 230f, 60f) { cv, w, h ->
                        GlucoseCard.draw(cv, w, h, data, trs, now, CardStyle(graph = null, rings = rings, last = LastPlace.NEAR_VALUE, lastElse = fallback), pal, d, alerts = a)
                    },
                    Tile("4×2 combined · next to the number", "", 300f, 150f) { cv, w, h ->
                        GlucoseCard.draw(cv, w, h, data, trs, now, CardStyle(graph = GraphStyle(hour24 = true, windowS = 6 * 3600L), rings = rings,
                            last = LastPlace.NEAR_VALUE, lastElse = fallback), pal, d, alerts = a)
                    },
                )
            }, 300f, 150f, cols = 2, pal)
            sheet("last-places-${if (dark) "dark" else "light"}", listOf(
                Tile("at the right end", "", 380f, 100f) { cv, w, h ->
                    GlucoseCard.draw(cv, w, h, data, states[0].second, now, CardStyle(graph = null, rings = rings, last = LastPlace.END, lastElse = fallback), pal, d, alerts = a)
                },
                Tile("next to the number: room for two, the 7d ring gives way", "", 380f, 100f) { cv, w, h ->
                    GlucoseCard.draw(cv, w, h, data, states[0].second, now, CardStyle(graph = null, rings = rings, last = LastPlace.NEAR_VALUE, lastElse = fallback), pal, d, alerts = a)
                },
                Tile("no recent one, fallback off", "", 380f, 100f) { cv, w, h ->
                    GlucoseCard.draw(cv, w, h, data, emptyList(), now, CardStyle(graph = null, rings = rings, last = LastPlace.NEAR_VALUE, lastElse = null), pal, d, alerts = a)
                },
                Tile("2×1, room for one: the main ring stays", "", 150f, 60f) { cv, w, h ->
                    GlucoseCard.draw(cv, w, h, data, states[0].second, now, CardStyle(graph = null, rings = rings, last = LastPlace.NEAR_VALUE, lastElse = fallback), pal, d, alerts = a)
                },
            ), 380f, 100f, cols = 2, pal)
        }
    }

    /** 2026-10-18 round: treatment marks with the time since each and their mute lines, on every chart size. */
    @Test fun marks() {
        val data = Synthetic.endAt(Synthetic.days(now, 15), now, 150)
        val a = AlertSettings()
        fun tr(k: String, a: Double, minAgo: Int) = Treatment(id = "$k$minAgo", t = now - minAgo * 60L, k = k, a = a, u = 0, o = "phone")
        val trs = listOf(
            tr("rapid", 6.0, 8 * 60 + 10), tr("carbs", 60.0, 8 * 60), tr("long", 18.0, 6 * 60),
            tr("rapid", 4.5, 95), tr("carbs", 45.0, 95), tr("carbs", 15.0, 40), tr("rapid", 2.0, 20), tr("note", 0.0, 150),
        )
        for (dark in listOf(true, false)) {
            val pal = GraphPalette.of(dark)
            val g = GraphStyle(hour24 = true)
            val v = listOf(
                Triple("4×2 combined, 12 h", 300f to 150f, CardStyle(graph = g)),
                Triple("4×2 combined, 3 h", 300f to 150f, CardStyle(graph = g.copy(windowS = 3 * 3600L))),
                Triple("4×1 combined (side by side), 6 h", 300f to 70f, CardStyle(graph = g.copy(windowS = 6 * 3600L))),
                Triple("notification (no value, 12 h)", 340f to 110f, CardStyle(graph = g, value = false)),
                Triple("5×3 combined, 24 h", 380f to 230f, CardStyle(graph = g.copy(windowS = 24 * 3600L))),
                Triple("app, 12 h", 360f to 210f, CardStyle(graph = g, value = false)),
            )
            sheet("marks-${if (dark) "dark" else "light"}", v.map { (label, sz, st) ->
                Tile(label, "", sz.first, sz.second) { cv, w, h -> GlucoseCard.draw(cv, w, h, data, trs, now, st, pal, d, alerts = a) }
            }, 380f, 230f, cols = 2, pal)
        }
    }

    /**
     * Treatments close together (the label layout, docs/16-graph.md "Treatment labels"): the same states as the Mac's
     * `render treatments` `mac-marks-close` sheet.
     */
    @Test fun marksClose() {
        val data = Synthetic.endAt(Synthetic.days(now, 15), now, 150)
        val a = AlertSettings()
        fun tr(k: String, a: Double, minAgo: Int) = Treatment(id = "$k$minAgo", t = now - minAgo * 60L, k = k, a = a, u = 0, o = "phone")
        for (dark in listOf(true, false)) {
            val pal = GraphPalette.of(dark)
            val g = GraphStyle(hour24 = true)
            val day = listOf(tr("carbs", 10.0, 1364), tr("rapid", 5.0, 859), tr("carbs", 40.0, 854), tr("rapid", 7.0, 509),
                tr("carbs", 70.0, 504), tr("carbs", 15.0, 304), tr("carbs", 10.0, 284), tr("rapid", 8.0, 104),
                tr("carbs", 80.0, 99), tr("rapid", 1.5, 24), tr("long", 18.0, 9))
            val v = listOf(
                Triple("4×2, 12 h: two 5g snacks 11 h ago", 300f to 150f, CardStyle(graph = g) to
                    listOf(tr("carbs", 5.0, 680), tr("carbs", 5.0, 665), tr("carbs", 15.0, 40), tr("rapid", 2.0, 20))),
                Triple("4×2, 3 h: meal, snack, correction", 300f to 150f, CardStyle(graph = g.copy(windowS = 3 * 3600L)) to
                    listOf(tr("rapid", 4.5, 95), tr("carbs", 45.0, 95), tr("carbs", 15.0, 40), tr("rapid", 2.0, 20))),
                Triple("notification, 12 h: insulin + carbs just now", 340f to 110f, CardStyle(graph = g, value = false) to
                    listOf(tr("rapid", 6.0, 490), tr("carbs", 60.0, 480), tr("carbs", 30.0, 5), tr("rapid", 3.0, 2))),
                Triple("2×2 graph widget, 12 h", 150f to 150f, CardStyle(graph = g, value = false, rings = emptyList()) to
                    listOf(tr("rapid", 6.0, 490), tr("carbs", 60.0, 480), tr("rapid", 4.5, 95), tr("carbs", 45.0, 95),
                        tr("carbs", 15.0, 40), tr("rapid", 2.0, 20))),
                Triple("5×3, 24 h: a full day", 380f to 230f, CardStyle(graph = g.copy(windowS = 24 * 3600L)) to day),
                Triple("app, 12 h: corrections close together", 360f to 210f, CardStyle(graph = g, value = false) to
                    listOf(tr("rapid", 1.0, 190), tr("rapid", 1.5, 170), tr("carbs", 10.0, 160), tr("rapid", 4.5, 95),
                        tr("carbs", 45.0, 95), tr("carbs", 15.0, 40), tr("rapid", 2.0, 20))),
            )
            sheet("marks-close-${if (dark) "dark" else "light"}", v.map { (label, sz, s) ->
                Tile(label, "", sz.first, sz.second) { cv, w, h -> GlucoseCard.draw(cv, w, h, data, s.second, now, s.first, pal, d, alerts = a) }
            }, 380f, 230f, cols = 2, pal)
        }
    }

    // ---- sheet plumbing -------------------------------------------------------------------------------------------

    private class Tile(val title: String, val sub: String, val w: Float? = null, val h: Float? = null,
                       val draw: (Canvas, Float, Float) -> Unit)

    private fun sheet(name: String, tiles: List<Tile>, cellW: Float, cellH: Float, cols: Int, pal: GraphPalette) {
        val pad = 14f; val cap = 34f
        val rows = (tiles.size + cols - 1) / cols
        val sw = ((cellW + 2 * pad) * cols + pad) * d
        val sh = ((cellH + 2 * pad + cap) * rows + pad) * d
        val bmp = Bitmap.createBitmap(sw.roundToInt(), sh.roundToInt(), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(if (pal.dark) 0xFF0E0E10.toInt() else 0xFFE9E9EE.toInt())
        val t1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.ink; textSize = 13 * d; typeface = Typeface.create(Typeface.DEFAULT, 600, false) }
        val t2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.inkSoft; textSize = 10.5f * d }
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.bg }
        tiles.forEachIndexed { i, t ->
            val cx = (pad + (i % cols) * (cellW + 2 * pad)) * d
            val cy = (pad + (i / cols) * (cellH + 2 * pad + cap)) * d
            cv.drawText(t.title, cx + 4 * d, cy + 14 * d, t1)
            if (t.sub.isNotEmpty()) cv.drawText(t.sub, cx + 4 * d, cy + 28 * d, t2)
            val w = (t.w ?: cellW); val h = (t.h ?: cellH)
            val top = cy + cap * d
            cv.drawRoundRect(RectF(cx, top, cx + (w + 2 * pad) * d, top + (h + 2 * pad) * d), 22 * d, 22 * d, card)
            cv.save()
            cv.translate(cx + pad * d, top + pad * d)
            cv.clipRect(0f, 0f, w * d, h * d)
            t.draw(cv, w * d, h * d)
            cv.restore()
        }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("gallery: ${File(out, "$name.png").absolutePath}")
    }
}

/**
 * A believable two weeks of 1-minute Libre 3 readings: a flat night with a dawn rise, three meals with a bolus each
 * (peak ~45–70 min, size varying by day), a late-afternoon walk that sometimes dips low, sensor noise. Seeded, so
 * every run draws the same picture.
 */
object Synthetic {
    fun days(now: Long, n: Int): List<Reading> {
        val rnd = java.util.Random(16)
        val start = now - n * 86400L
        val tz = TimeZone.getDefault()
        data class Meal(val t: Long, val size: Double, val peakMin: Double)
        val meals = ArrayList<Meal>()
        val dips = ArrayList<Pair<Long, Double>>()
        var day = start - Math.floorMod(start + tz.getOffset(start * 1000) / 1000, 86400L)
        while (day < now + 86400) {
            fun at(h: Double) = day + (h * 3600).toLong() - tz.getOffset(day * 1000) / 1000 + 0
            meals += Meal(at(8.0 + rnd.nextGaussian() * 0.4), 45 + rnd.nextDouble() * 50, 45 + rnd.nextDouble() * 20)
            meals += Meal(at(13.8 + rnd.nextGaussian() * 0.5), 55 + rnd.nextDouble() * 75, 55 + rnd.nextDouble() * 25)
            meals += Meal(at(20.6 + rnd.nextGaussian() * 0.4), 50 + rnd.nextDouble() * 115, 60 + rnd.nextDouble() * 25)
            if (rnd.nextDouble() < 0.6) dips += at(17.0 + rnd.nextGaussian() * 0.6) to (25 + rnd.nextDouble() * 45)
            day += 86400
        }
        var walk = 0.0
        val out = ArrayList<Reading>()
        var t = start
        while (t <= now) {
            val local = (t + tz.getOffset(t * 1000) / 1000)
            val hour = Math.floorMod(local, 86400L) / 3600.0
            var v = 104.0 + 18 * exp(-((hour - 6.5) * (hour - 6.5)) / 2.0)  // dawn
            for (m in meals) {
                val k = (t - m.t) / 60.0
                if (k > 0 && k < 900) v += m.size * (k / m.peakMin) * exp(1 - k / m.peakMin) - 8 * exp(-((k - 160) * (k - 160)) / 1800)
            }
            for ((dt, depth) in dips) { val k = (t - dt) / 60.0; if (k > -60 && k < 120) v -= depth * exp(-(k * k) / 1600) }
            walk = walk * 0.995 + rnd.nextGaussian() * 0.6
            v += walk + 3 * sin(t / 1300.0)
            out += Reading(t, max(40, v.roundToInt()), null)
            t += 60
        }
        // rates for the arrow: last 5 min slope
        return out.mapIndexed { i, r -> if (i >= 5) r.copy(rate = (r.sgv - out[i - 5].sgv) / 5.0) else r }
    }

    /** [data] bent over its last 40 minutes so the latest reading is [mgdl] (with a matching trend). */
    fun endAt(data: List<Reading>, now: Long, mgdl: Int): List<Reading> {
        val last = data.last().sgv
        return data.map { r ->
            val f = 1 - (now - r.t) / 2400.0
            if (f <= 0) r else r.copy(sgv = max(40, (r.sgv + (mgdl - last) * f).roundToInt()))
        }.let { l -> l.mapIndexed { i, r -> if (i >= 5) r.copy(rate = (r.sgv - l[i - 5].sgv) / 5.0) else r } }
    }

    fun treatments(now: Long): List<Treatment> = listOf(
        Treatment(id = "a", t = now - 8 * 3600 - 600, k = "rapid", a = 6.0, u = 0, o = "phone"),
        Treatment(id = "b", t = now - 8 * 3600, k = "carbs", a = 60.0, u = 0, o = "phone"),
        Treatment(id = "c", t = now - 90 * 60, k = "rapid", a = 4.5, u = 0, o = "phone"),
        Treatment(id = "d", t = now - 85 * 60, k = "carbs", a = 45.0, u = 0, o = "phone"),
    )
}
