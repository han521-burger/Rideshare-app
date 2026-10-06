package com.example.ds04_monash_rideshare_application.backend

import com.example.ds04_monash_rideshare_application.backend.routes.haversine
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Random

// SYNTHETIC / DEMONSTRATION data only. This file just PLANS a believable completed-ride history;
// DemoHistorySeeder.kt turns the plan into ordinary rows, and the real dashboard/analytics code
// computes every figure from those rows. Nothing here (or in the app) knows an impact number.

enum class DemoRole { DRIVER, PASSENGER }

// One planned completed trip. For DRIVER the target account drives and `seatsFilled` synthetic
// passengers ride; for PASSENGER a synthetic driver drives, the target takes `targetSeats`, and
// `seatsFilled - targetSeats` synthetic passengers fill the rest.
data class DemoTrip(
    val index: Int,
    val role: DemoRole,
    val origin: NamedPoint,
    val destination: NamedPoint,
    val departureTime: Long,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val seatsTotal: Int,
    val seatsFilled: Int,
    val targetSeats: Int,
    val counterpartSlot: Int,
    val requestLeadMillis: Long,
    val decisionDelayMillis: Long
) {
    val toMonash: Boolean get() = MONASH_CAMPUSES.any { it.name == destination.name }
}

object DemoHistoryGenerator {
    const val SEED = 20260601L
    // Completed trips per week, newest week first: 32 trips across 10 weeks.
    val TRIPS_PER_WEEK = listOf(4, 3, 3, 4, 3, 3, 4, 3, 2, 3)
    const val SYNTHETIC_DRIVERS = 3
    const val SYNTHETIC_PASSENGERS = 4

    private const val DRIVER_SHARE_PERCENT = 70
    // Road distance is longer than the straight line; a flat factor keeps the plan deterministic
    // (the live app asks a routing service, which a seeder can't call).
    private const val ROAD_CIRCUITY = 1.3
    private const val AVERAGE_SPEED_METERS_PER_SECOND = 32_000.0 / 3600.0

    private class Template(val campus: String, val hub: String, val weight: Int)

    // The story: a Clayton commuter hub, a Caulfield pair, a CBD trio, and the odd Peninsula trip.
    // Only names from the approved datasets (MonashCampuses.kt / NamedPoints.kt) - resolved below,
    // so a renamed or removed hub fails loudly instead of silently seeding a made-up place.
    private val TEMPLATES = listOf(
        Template("Monash Clayton", "Huntingdale Station", 6),
        Template("Monash Clayton", "Clayton Station", 3),
        Template("Monash Clayton", "Glen Waverley Station", 3),
        Template("Monash Clayton", "Oakleigh Station", 2),
        Template("Monash Caulfield", "Caulfield Station", 3),
        Template("Monash Caulfield", "Carnegie Station", 3),
        Template("Monash Melbourne City – 750 Collins St", "Southern Cross Station", 3),
        Template("Monash Melbourne City – 750 Collins St", "Melbourne Central", 2),
        Template("Monash Parkville", "Royal Park Station", 1),
        Template("Monash Peninsula", "Frankston Station", 2),
        Template("Monash Peninsula", "Leawarra Station", 1)
    )

    // Stable per account (String.hashCode is fixed by the JVM spec), so re-seeding the same account
    // reproduces its history while different accounts get different trips.
    fun seedFor(userId: String): Long = SEED xor userId.hashCode().toLong()

    private fun campusPoint(name: String): NamedPoint =
        MONASH_CAMPUSES.single { it.name == name }.let { NamedPoint(it.name, it.lat, it.lng) }

    private fun hubPoint(name: String): NamedPoint = NAMED_FIXED_POINTS.single { it.name == name }

    // `maxPassengerSeats` is the target's vehicle capacity minus the driver's own seat. `seed` picks
    // the story: the same seed always gives the same plan; the seeder passes one per account
    // (seedFor) so two demo accounts don't end up with identical histories.
    fun generate(anchorMillis: Long, zone: ZoneId, maxPassengerSeats: Int, seed: Long = SEED): List<DemoTrip> {
        require(maxPassengerSeats >= 1) { "A vehicle must offer at least one passenger seat" }
        val random = Random(seed)
        val today = java.time.Instant.ofEpochMilli(anchorMillis).atZone(zone).toLocalDate()
        val totalWeight = TEMPLATES.sumOf { it.weight }
        val drafts = mutableListOf<DemoTrip>()

        TRIPS_PER_WEEK.forEachIndexed { week, count ->
            // Weekday commute slots only; every day is strictly before today so the trip is over.
            val slots = (1..7).map { today.minusDays((week * 7 + it).toLong()) }
                .filter { it.dayOfWeek.value <= 5 }
                .flatMap { day -> listOf(day to true, day to false) }
                .toMutableList()
            repeat(count) {
                val (day, morning) = slots.removeAt(random.nextInt(slots.size))
                drafts += plan(random, day, morning, zone, totalWeight, maxPassengerSeats)
            }
        }
        return drafts.sortedBy { it.departureTime }.mapIndexed { index, trip -> trip.copy(index = index + 1) }
    }

    private fun plan(random: Random, day: LocalDate, morning: Boolean, zone: ZoneId, totalWeight: Int, maxPassengerSeats: Int): DemoTrip {
        var pick = random.nextInt(totalWeight)
        val template = TEMPLATES.first { pick -= it.weight; pick < 0 }
        val campus = campusPoint(template.campus)
        val hub = hubPoint(template.hub)
        // Mornings head to campus, afternoons head home.
        val (origin, destination) = if (morning) hub to campus else campus to hub

        val time = if (morning) LocalTime.of(8, 0).plusMinutes(15L * random.nextInt(7))
        else LocalTime.of(16, 0).plusMinutes(15L * random.nextInt(9))
        val departure = day.atTime(time).atZone(zone).toInstant().toEpochMilli()

        val distance = Math.round(haversine(origin.lat, origin.lng, destination.lat, destination.lng) * ROAD_CIRCUITY / 10.0) * 10.0
        val duration = maxOf(120L, Math.round(distance / AVERAGE_SPEED_METERS_PER_SECOND))

        val driving = random.nextInt(100) < DRIVER_SHARE_PERCENT
        val seatsTotal: Int
        val seatsFilled: Int
        val targetSeats: Int
        val counterpart: Int
        if (driving) {
            seatsTotal = minOf(maxPassengerSeats, 2 + random.nextInt(2))
            seatsFilled = 1 + random.nextInt(seatsTotal)
            targetSeats = 0
            counterpart = random.nextInt(DemoHistoryGenerator.SYNTHETIC_PASSENGERS)
        } else {
            seatsTotal = 2 + random.nextInt(2)
            targetSeats = 1
            seatsFilled = targetSeats + random.nextInt(seatsTotal)
            counterpart = random.nextInt(SYNTHETIC_DRIVERS)
        }
        val lead = 3L * 3_600_000L + 30L * 60_000L * random.nextInt(90)
        val decision = (4L + random.nextInt(80)) * 60_000L
        return DemoTrip(0, if (driving) DemoRole.DRIVER else DemoRole.PASSENGER, origin, destination, departure,
            distance, duration, seatsTotal, seatsFilled, targetSeats, counterpart, lead, decision)
    }
}
