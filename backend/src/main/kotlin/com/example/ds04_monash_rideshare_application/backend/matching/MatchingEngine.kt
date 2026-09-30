package com.example.ds04_monash_rideshare_application.backend.matching

import com.example.ds04_monash_rideshare_application.backend.MatchScoreDto
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Rule-based matching pipeline (RTM FR-04 / weekly plan Week 5: "rule-based matching only, no
// ML"). The driver always makes the final call on a request - this only ranks candidate rides
// for a passenger's search, it never auto-books anything.
data class RideCandidate(
    val rideId: String,
    val originLat: Double,
    val originLng: Double,
    val destinationLat: Double,
    val destinationLng: Double,
    val departureTime: Long,
    val seatsAvailable: Int
)

data class SearchCriteria(
    val originLat: Double,
    val originLng: Double,
    val destinationLat: Double,
    val destinationLng: Double,
    val desiredDepartureTime: Long,
    val seatsNeeded: Int = 1
)

data class MatchResult(val ride: RideCandidate, val score: MatchScoreDto)

private const val MAX_PICKUP_DISTANCE_METERS = 3_000.0
private const val MAX_TIME_WINDOW_MILLIS = 45L * 60_000L // 45 minutes either side
private const val EARTH_RADIUS_METERS = 6_371_000.0

private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return EARTH_RADIUS_METERS * c
}

// Passes rides through hard gates first (date, seats, direction, time, pickup proximity), then
// scores survivors: route similarity 40%, time compatibility 30%, pickup proximity 20%, detour 10%.
object MatchingEngine {

    fun rank(criteria: SearchCriteria, candidates: List<RideCandidate>): List<MatchResult> {
        return eligibleCandidates(criteria, candidates)
            .filter { passesGates(criteria, it) }
            .map { MatchResult(it, score(criteria, it)) }
            .sortedByDescending { it.score.total }
    }

    // Simple fallback per the spec: if the full pipeline yields nothing, relax to just
    // station/pickup + time + direction (i.e. drop the detour/route-shape weighting).
    fun fallback(criteria: SearchCriteria, candidates: List<RideCandidate>): List<MatchResult> {
        return eligibleCandidates(criteria, candidates)
            .filter { sameDirection(criteria, it) && withinTimeWindow(criteria, it) }
            .map { ride ->
                val pickup = pickupScore(criteria, ride)
                MatchResult(
                    ride,
                    MatchScoreDto(total = pickup, routeScore = 0.0, timeScore = 0.0, pickupScore = pickup, detourScore = 0.0)
                )
            }
            .sortedByDescending { it.score.total }
    }

    // Capacity is mandatory even when fallback relaxes the ranking gates.
    private fun eligibleCandidates(criteria: SearchCriteria, candidates: List<RideCandidate>): List<RideCandidate> =
        candidates.filter { criteria.seatsNeeded > 0 && it.seatsAvailable >= criteria.seatsNeeded }

    private fun passesGates(criteria: SearchCriteria, ride: RideCandidate): Boolean {
        if (!sameDirection(criteria, ride)) return false
        if (!withinTimeWindow(criteria, ride)) return false
        val pickupDistance = haversineMeters(
            criteria.originLat, criteria.originLng, ride.originLat, ride.originLng
        )
        if (pickupDistance > MAX_PICKUP_DISTANCE_METERS) return false
        return true
    }

    private fun withinTimeWindow(criteria: SearchCriteria, ride: RideCandidate): Boolean {
        val sameDay = sameCalendarDay(criteria.desiredDepartureTime, ride.departureTime)
        val withinWindow = kotlin.math.abs(ride.departureTime - criteria.desiredDepartureTime) <= MAX_TIME_WINDOW_MILLIS
        return sameDay && withinWindow
    }

    private fun sameCalendarDay(a: Long, b: Long): Boolean {
        val dayMillis = 24L * 60 * 60 * 1000
        return a / dayMillis == b / dayMillis
    }

    // "Same direction" = the ride's origin->destination bearing roughly matches the passenger's
    // desired origin->destination bearing (within 45 degrees either way).
    private fun sameDirection(criteria: SearchCriteria, ride: RideCandidate): Boolean {
        val criteriaBearing = bearing(criteria.originLat, criteria.originLng, criteria.destinationLat, criteria.destinationLng)
        val rideBearing = bearing(ride.originLat, ride.originLng, ride.destinationLat, ride.destinationLng)
        var diff = kotlin.math.abs(criteriaBearing - rideBearing)
        if (diff > 180.0) diff = 360.0 - diff
        return diff <= 45.0
    }

    private fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lng2 - lng1)
        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun score(criteria: SearchCriteria, ride: RideCandidate): MatchScoreDto {
        val route = routeScore(criteria, ride)
        val time = timeScore(criteria, ride)
        val pickup = pickupScore(criteria, ride)
        val detour = detourScore(criteria, ride)
        val total = route * 0.40 + time * 0.30 + pickup * 0.20 + detour * 0.10
        return MatchScoreDto(total = total, routeScore = route, timeScore = time, pickupScore = pickup, detourScore = detour)
    }

    // How closely the ride's overall path lines up with the passenger's desired path (endpoint
    // proximity as a stand-in for full route-shape comparison, which needs a routing API call
    // per candidate and isn't worth the cost at trial scale).
    private fun routeScore(criteria: SearchCriteria, ride: RideCandidate): Double {
        val destGap = haversineMeters(criteria.destinationLat, criteria.destinationLng, ride.destinationLat, ride.destinationLng)
        return proximityScore(destGap, maxMeters = 5_000.0)
    }

    private fun timeScore(criteria: SearchCriteria, ride: RideCandidate): Double {
        val gapMinutes = kotlin.math.abs(ride.departureTime - criteria.desiredDepartureTime) / 60_000.0
        return proximityScore(gapMinutes, maxMeters = 45.0)
    }

    private fun pickupScore(criteria: SearchCriteria, ride: RideCandidate): Double {
        val gap = haversineMeters(criteria.originLat, criteria.originLng, ride.originLat, ride.originLng)
        return proximityScore(gap, maxMeters = MAX_PICKUP_DISTANCE_METERS)
    }

    // Detour cost: how much further the driver travels by picking the passenger up versus going
    // straight to their own destination. Smaller detour = higher score.
    private fun detourScore(criteria: SearchCriteria, ride: RideCandidate): Double {
        val direct = haversineMeters(ride.originLat, ride.originLng, ride.destinationLat, ride.destinationLng)
        val viaPassenger = haversineMeters(ride.originLat, ride.originLng, criteria.originLat, criteria.originLng) +
            haversineMeters(criteria.originLat, criteria.originLng, criteria.destinationLat, criteria.destinationLng) +
            haversineMeters(criteria.destinationLat, criteria.destinationLng, ride.destinationLat, ride.destinationLng)
        val extra = (viaPassenger - direct).coerceAtLeast(0.0)
        return proximityScore(extra, maxMeters = 5_000.0)
    }

    private fun proximityScore(gap: Double, maxMeters: Double): Double {
        val normalised = 1.0 - min(gap, maxMeters) / maxMeters
        return (normalised * 100).coerceIn(0.0, 100.0)
    }
}
