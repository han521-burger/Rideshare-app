package com.example.ds04_monash_rideshare_application.core.demand

// What the UI consumes: aggregated, normalised demand for a time bucket. It owns no map code and
// never sees a synthetic generator - only a DemandDataSource - so swapping in real data changes
// nothing above this line. Results are cached per period (at most 8), so the screen recomputes
// only when the time bucket changes, never while the camera moves.
class DemandModel(private val source: DemandDataSource) {
    private val cache = HashMap<DemandPeriod, List<DemandPoint>>()

    val disclosure: String? get() = source.disclosure
    val isSynthetic: Boolean get() = source.provenance == DemandProvenance.SYNTHETIC

    @Synchronized
    fun pointsFor(period: DemandPeriod): List<DemandPoint> =
        cache.getOrPut(period) { DemandAggregator.aggregate(source.events(), period) }

    fun pointsAt(timestampMillis: Long): List<DemandPoint> = pointsFor(DemandPeriod.of(timestampMillis))
}

// The prototype default. Replace `source` with a backend-fed DemandDataSource later.
object DemandProvider {
    val model: DemandModel by lazy { DemandModel(SyntheticDemandDataSource()) }
}

// A short, computed description of a dataset for demos and notes - never typed in by hand.
data class DemandSummary(
    val totalEvents: Int,
    val totalWeight: Double,
    val topHubs: List<Pair<String, Double>>,
    val lowestHubs: List<Pair<String, Double>>,
    val peakPeriod: DemandPeriod?,
    val peakHour: Int?,
    val weekdayShare: Double,
    val weekendShare: Double
) {
    fun lines(): List<String> = listOf(
        "Total demand events: $totalEvents (weighted $totalWeight)",
        "Top 5 hubs: " + topHubs.joinToString { "${it.first} (${it.second})" },
        "Lowest 3 hubs: " + lowestHubs.joinToString { "${it.first} (${it.second})" },
        "Peak period: ${peakPeriod?.label ?: "none"}, peak hour: ${peakHour?.let { "%02d:00".format(it) } ?: "none"}",
        "Weekday / weekend split: %.1f%% / %.1f%%".format(weekdayShare * 100, weekendShare * 100)
    )

    companion object {
        fun of(events: List<DemandEvent>, hubNames: List<String>): DemandSummary {
            val byHub = hubNames.associateWith { 0.0 }.toMutableMap()
            val byPeriod = HashMap<DemandPeriod, Double>()
            val byHour = HashMap<Int, Double>()
            var weight = 0.0
            var weekend = 0.0
            for (event in events) {
                if (event.hubName !in byHub) continue
                val period = DemandPeriod.of(event.timestampMillis)
                byHub.merge(event.hubName, event.weight, Double::plus)
                byPeriod.merge(period, event.weight, Double::plus)
                byHour.merge(java.time.Instant.ofEpochMilli(event.timestampMillis).atZone(DEMAND_ZONE).hour, event.weight, Double::plus)
                weight += event.weight
                if (period.weekend) weekend += event.weight
            }
            val ranked = byHub.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            return DemandSummary(
                totalEvents = events.count { it.hubName in byHub },
                totalWeight = weight,
                topHubs = ranked.take(5).map { it.key to it.value },
                lowestHubs = ranked.takeLast(3).reversed().map { it.key to it.value },
                peakPeriod = byPeriod.entries.maxWithOrNull(compareBy<Map.Entry<DemandPeriod, Double>> { it.value }.thenBy { it.key.label })?.key,
                peakHour = byHour.entries.maxWithOrNull(compareBy<Map.Entry<Int, Double>> { it.value }.thenBy { -it.key })?.key,
                weekdayShare = if (weight > 0) (weight - weekend) / weight else 0.0,
                weekendShare = if (weight > 0) weekend / weight else 0.0
            )
        }
    }
}
