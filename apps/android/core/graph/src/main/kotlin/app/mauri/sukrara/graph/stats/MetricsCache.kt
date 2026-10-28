// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph.stats

import app.mauri.sukrara.graph.RingSpec

/**
 * The slots' report numbers over [history] (oldest first) up to [nowS], each window computed once: a card model
 * built per reading (home) or per widget frame hands this to [app.mauri.sukrara.graph.CardModel.metricsOf], so a redraw
 * (a scrolled frame, another widget or size of the same frame) never counts a month again.
 */
class MetricsCache(
    private val history: List<GlucosePoint>,
    private val nowS: Long,
) : (RingSpec, Bands) -> GlucoseMetrics {
    private val computed = HashMap<Pair<Long, Bands>, GlucoseMetrics>()

    @Synchronized
    override fun invoke(
        spec: RingSpec,
        bands: Bands,
    ): GlucoseMetrics = computed.getOrPut(spec.windowS to bands) { GlucoseMetrics.of(history, nowS - spec.windowS, nowS, bands) }
}
