package com.example.ds04_monash_rideshare_application.core.demand

import com.example.ds04_monash_rideshare_application.ui.APPROVED_POINTS
import com.example.ds04_monash_rideshare_application.ui.NamedLocation
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

// DemandEvent history -> events in the chosen period -> weighted count per hub -> intensity 0..1.
// Pure and generic: it only sees DemandEvent, so real events work exactly like synthetic ones.
object DemandAggregator {
    // The reference level is the 90th-percentile hub (nearest-rank, among hubs with any demand).
    const val REFERENCE_PERCENTILE = 0.90

    // One DemandPoint for EVERY approved hub (raw 0 when it has no events), busiest first.
    // Events at names that are not approved hubs are ignored. `sinceMillis` optionally drops
    // history older than a cut-off (real data may want "last 90 days").
    fun aggregate(
        events: List<DemandEvent>,
        period: DemandPeriod,
        hubs: List<NamedLocation> = APPROVED_POINTS,
        sinceMillis: Long? = null
    ): List<DemandPoint> {
        val approved = hubs.map { it.name }.toSet()
        val raw = HashMap<String, Double>()
        for (event in events) {
            if (event.hubName !in approved || !event.weight.isFinite() || event.weight <= 0.0) continue
            if (sinceMillis != null && event.timestampMillis < sinceMillis) continue
            if (DemandPeriod.of(event.timestampMillis) != period) continue
            raw.merge(event.hubName, event.weight, Double::plus)
        }
        val intensities = normalize(hubs.map { raw[it.name] ?: 0.0 })
        return hubs.mapIndexed { index, hub ->
            DemandPoint(hub.name, hub.point.latitude, hub.point.longitude, hub.isCampus, raw[hub.name] ?: 0.0, intensities[index], 0)
        }
            .sortedWith(compareByDescending<DemandPoint> { it.rawDemand }.thenBy { it.hubName })
            .mapIndexed { i, point -> point.copy(rank = i + 1) }
    }

    // intensity = min(1, sqrt(raw / reference))
    //   - sqrt compresses the top end so a few very busy hubs do not flatten the rest (demand
    //     counts are heavy-tailed), yet still orders hubs strictly by raw demand;
    //   - reference is the 90th-percentile non-zero hub, so the busiest ~10% saturate at 1.0
    //     instead of one extreme outlier setting the scale for everyone;
    //   - no demand -> 0.0; an empty or all-zero input is all zeros (never NaN/Infinity).
    fun normalize(raw: List<Double>): List<Double> {
        val positive = raw.filter { it.isFinite() && it > 0.0 }.sorted()
        if (positive.isEmpty()) return raw.map { 0.0 }
        val reference = positive[ceil(REFERENCE_PERCENTILE * positive.size).toInt().coerceIn(1, positive.size) - 1]
        return raw.map { value -> if (!value.isFinite() || value <= 0.0) 0.0 else min(1.0, sqrt(value / reference)) }
    }
}
