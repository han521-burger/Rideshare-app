package com.example.ds04_monash_rideshare_application.core.demand

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Random
import kotlin.math.exp

// SYNTHETIC / PROTOTYPE DATA. The app has no organic demand history yet (cold start), so this
// generates a plausible, reproducible passenger-demand history for the demand-zones prototype.
// It is never presented as real student usage (DemandDataSource.disclosure labels it) and holds
// no people - only hub, time and weight.

// How a hub behaves through the day. Pattern, per the university/commuter story:
//  - CAMPUS: weekday mornings are the demand peak (people heading to campus);
//  - STATION / SHOPPING: afternoon and evening demand (people leaving campus for transport);
//  - weekends: far lower university demand, shopping hubs hold up best.
enum class HubRole { CAMPUS, STATION, SHOPPING }

// `weight` is how busy this hub is relative to its cluster; `clusterFactor` how busy the whole
// area is. Clayton is the biggest campus, Peninsula the quietest.
data class HubDemandProfile(val hubName: String, val role: HubRole, val clusterFactor: Double, val weight: Double)

object SyntheticDemand {
    const val SEED = 20260906L
    const val DAYS = 75
    val END_DATE: LocalDate = LocalDate.of(2026, 9, 30)
    // Tuned so the 75 days hold roughly 2,000 events (SyntheticDemandGeneratorTest keeps it in range).
    private const val SCALE = 0.40

    private const val CLAYTON = 1.0
    private const val CAULFIELD = 0.6
    private const val CITY = 0.6
    private const val PARKVILLE = 0.4
    private const val PENINSULA = 0.3

    // Every approved hub exactly once (checked by tests against the approved dataset).
    val HUBS: List<HubDemandProfile> = listOf(
        HubDemandProfile("Monash Clayton", HubRole.CAMPUS, CLAYTON, 1.0),
        HubDemandProfile("Huntingdale Station", HubRole.STATION, CLAYTON, 0.9),
        HubDemandProfile("Clayton Station", HubRole.STATION, CLAYTON, 0.8),
        HubDemandProfile("Westall Station", HubRole.STATION, CLAYTON, 0.6),
        HubDemandProfile("Glen Waverley Station", HubRole.STATION, CLAYTON, 0.8),
        HubDemandProfile("Brandon Park Shopping Centre", HubRole.SHOPPING, CLAYTON, 0.5),
        HubDemandProfile("Chadstone Shopping Centre", HubRole.SHOPPING, CLAYTON, 0.8),
        HubDemandProfile("Oakleigh Station", HubRole.STATION, CLAYTON, 0.6),
        HubDemandProfile("Springvale Station", HubRole.STATION, CLAYTON, 0.4),
        HubDemandProfile("Waverley Gardens Shopping Centre", HubRole.SHOPPING, CLAYTON, 0.3),

        HubDemandProfile("Monash Caulfield", HubRole.CAMPUS, CAULFIELD, 1.0),
        HubDemandProfile("Caulfield Station", HubRole.STATION, CAULFIELD, 0.9),
        HubDemandProfile("Carnegie Station", HubRole.STATION, CAULFIELD, 0.7),
        HubDemandProfile("Malvern Central", HubRole.SHOPPING, CAULFIELD, 0.6),

        HubDemandProfile("Monash Melbourne City – 750 Collins St", HubRole.CAMPUS, CITY, 0.9),
        HubDemandProfile("Southern Cross Station", HubRole.STATION, CITY, 1.0),
        HubDemandProfile("Melbourne Central", HubRole.STATION, CITY, 0.9),
        HubDemandProfile("The District Docklands", HubRole.SHOPPING, CITY, 0.5),
        HubDemandProfile("South Yarra Station", HubRole.STATION, CITY, 0.6),
        HubDemandProfile("IKEA Richmond", HubRole.SHOPPING, CITY, 0.3),
        HubDemandProfile("Footscray Station", HubRole.STATION, CITY, 0.4),

        HubDemandProfile("Monash Parkville", HubRole.CAMPUS, PARKVILLE, 1.0),
        HubDemandProfile("Parkville Station", HubRole.STATION, PARKVILLE, 0.7),
        HubDemandProfile("Royal Park Station", HubRole.STATION, PARKVILLE, 0.6),
        HubDemandProfile("Barkly Square", HubRole.SHOPPING, PARKVILLE, 0.4),

        HubDemandProfile("Monash Peninsula", HubRole.CAMPUS, PENINSULA, 1.0),
        HubDemandProfile("Frankston Station", HubRole.STATION, PENINSULA, 0.9),
        HubDemandProfile("Leawarra Station", HubRole.STATION, PENINSULA, 0.5),
        HubDemandProfile("Bayside Shopping Centre", HubRole.SHOPPING, PENINSULA, 0.6),
        HubDemandProfile("Karingal Hub", HubRole.SHOPPING, PENINSULA, 0.5)
    )

    // Relative demand at an hour of the day (0 = none), by role and weekday/weekend.
    fun hourProfile(role: HubRole, weekend: Boolean, hour: Int): Double = when (role) {
        HubRole.CAMPUS -> if (weekend) when (hour) { in 10..16 -> 0.15; in 8..9, in 17..18 -> 0.08; else -> 0.0 }
        else when (hour) {
            6 -> 0.2; 7 -> 0.6; 8, 9 -> 1.0; 10 -> 0.6; in 11..14 -> 0.45; 15, 16 -> 0.5
            17 -> 0.4; 18 -> 0.2; 19 -> 0.1; else -> 0.0
        }
        HubRole.STATION -> if (weekend) when (hour) { in 10..19 -> 0.25; in 8..9, in 20..21 -> 0.12; else -> 0.0 }
        else when (hour) {
            6 -> 0.1; in 7..9 -> 0.35; in 10..14 -> 0.2; 15 -> 0.6; 16, 17 -> 1.0; 18 -> 0.9
            19 -> 0.5; 20, 21 -> 0.3; 22 -> 0.1; else -> 0.0
        }
        HubRole.SHOPPING -> if (weekend) when (hour) { in 12..15 -> 0.6; in 10..11, in 16..18 -> 0.4; 19 -> 0.2; else -> 0.0 }
        else when (hour) {
            in 8..10 -> 0.1; in 11..14 -> 0.35; 15 -> 0.5; in 16..18 -> 0.7; 19, 20 -> 0.6; 21 -> 0.2; else -> 0.0
        }
    }

    // Mon-Fri differ a little (Tuesday/Wednesday are the busiest campus days, Friday the quietest).
    private fun dayOfWeekFactor(day: DayOfWeek): Double = when (day) {
        DayOfWeek.MONDAY -> 1.05; DayOfWeek.TUESDAY -> 1.1; DayOfWeek.WEDNESDAY -> 1.1
        DayOfWeek.THURSDAY -> 1.05; DayOfWeek.FRIDAY -> 0.8; else -> 1.0
    }

    // Same seed + same end date + same zone => the identical list, every time (java.util.Random's
    // algorithm is fixed by its specification). Oldest event first.
    fun generate(
        endDate: LocalDate = END_DATE,
        days: Int = DAYS,
        zone: ZoneId = DEMAND_ZONE,
        seed: Long = SEED
    ): List<DemandEvent> {
        val random = Random(seed)
        // A fixed multiplier per hub so two equally-rated hubs are not identical.
        val hubJitter = HUBS.map { exp(0.15 * random.nextGaussian()) }
        val events = ArrayList<DemandEvent>()
        for (offset in (days - 1) downTo 0) {
            val date = endDate.minusDays(offset.toLong())
            val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
            // A shared good/bad day (weather, exams) lifts or lowers every hub together.
            val dayFactor = dayOfWeekFactor(date.dayOfWeek) * exp(0.2 * random.nextGaussian())
            HUBS.forEachIndexed { index, hub ->
                for (hour in 5..22) {
                    val rate = SCALE * hub.clusterFactor * hub.weight * hourProfile(hub.role, weekend, hour) * dayFactor * hubJitter[index]
                    repeat(poisson(random, rate)) {
                        val moment = date.atTime(hour, random.nextInt(60), random.nextInt(60)).atZone(zone).toInstant().toEpochMilli()
                        // Most requests want one seat; some want two.
                        events += DemandEvent(hub.hubName, moment, if (random.nextInt(100) < 15) 2.0 else 1.0)
                    }
                }
            }
        }
        // Generated hub by hub within a day; hand back one timeline, oldest first (stable for equal times).
        return events.sortedBy { it.timestampMillis }
    }

    // Knuth's method; rates here are small (well under 10) so it is exact and cheap.
    private fun poisson(random: Random, rate: Double): Int {
        if (rate <= 0.0) return 0
        val limit = exp(-rate)
        var count = 0
        var product = random.nextDouble()
        while (product > limit) { count++; product *= random.nextDouble() }
        return count
    }
}

// The prototype's data source. Generated once, lazily, and cached for the process.
class SyntheticDemandDataSource(private val generate: () -> List<DemandEvent> = { SyntheticDemand.generate() }) : DemandDataSource {
    override val provenance = DemandProvenance.SYNTHETIC
    private val cached: List<DemandEvent> by lazy(generate)
    override fun events(): List<DemandEvent> = cached
}
