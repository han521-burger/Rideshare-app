package com.example.ds04_monash_rideshare_application.core.demand

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

// Demand = passenger intent (a seat request, a standalone ride request, later a search) at an
// approved hub. Driver offers are SUPPLY and never become DemandEvents. Nothing in this package
// knows whether events are synthetic or real - see DemandDataSource.

// Monash trips are all local; bucket by Melbourne time whatever the device's zone is.
val DEMAND_ZONE: ZoneId = ZoneId.of("Australia/Melbourne")

// One passenger-demand event at one approved hub. The hub is identified by its approved name
// (the source's only stable identity today). `weight` lets a real source count e.g. seats requested.
data class DemandEvent(val hubName: String, val timestampMillis: Long, val weight: Double = 1.0)

enum class DayBlock(val label: String, val startHour: Int, val endHourExclusive: Int) {
    MORNING("morning", 5, 10),
    MIDDAY("midday", 10, 15),
    AFTERNOON("afternoon", 15, 19),
    // Wraps midnight: 19:00-04:59.
    EVENING("evening", 19, 5);

    companion object {
        fun ofHour(hour: Int): DayBlock = entries.first { block ->
            if (block.startHour < block.endHourExclusive) hour in block.startHour until block.endHourExclusive
            else hour >= block.startHour || hour < block.endHourExclusive
        }
    }
}

// The time bucket a demand view is about: weekday/weekend x a block of the day. 8 buckets in all.
data class DemandPeriod(val weekend: Boolean, val block: DayBlock) {
    val label: String get() = (if (weekend) "Weekend " else "Weekday ") + block.label

    companion object {
        fun of(timestampMillis: Long, zone: ZoneId = DEMAND_ZONE): DemandPeriod {
            val time = Instant.ofEpochMilli(timestampMillis).atZone(zone)
            val weekend = time.dayOfWeek == DayOfWeek.SATURDAY || time.dayOfWeek == DayOfWeek.SUNDAY
            return DemandPeriod(weekend, DayBlock.ofHour(time.hour))
        }
        val ALL: List<DemandPeriod> = listOf(false, true).flatMap { weekend -> DayBlock.entries.map { DemandPeriod(weekend, it) } }
    }
}

// What the map draws: one per approved hub, never raw events.
data class DemandPoint(
    val hubName: String,
    val latitude: Double,
    val longitude: Double,
    val isCampus: Boolean,
    val rawDemand: Double,
    // 0.0 (none/lowest) .. 1.0 (highest), see DemandAggregator.normalize.
    val normalizedIntensity: Double,
    // 1 = busiest. Ties share the order of the hub name, so it is stable.
    val rank: Int
)

// FORECAST = what a model expects (ForecastDemand.kt), not what was observed.
enum class DemandProvenance { SYNTHETIC, OBSERVED, FORECAST }

// The seam between the UI and wherever the history comes from. Today: SyntheticDemandDataSource
// (past demand) and ForecastDemandDataSource (predicted demand). Later: a source built from persisted
// passenger requests/searches (docs/DEMAND.md).
interface DemandDataSource {
    val provenance: DemandProvenance
    fun events(): List<DemandEvent>

    // Short label for the UI; null when the data is genuine.
    val disclosure: String?
        get() = if (provenance == DemandProvenance.SYNTHETIC) "Prototype demand · synthetic data" else null
}
