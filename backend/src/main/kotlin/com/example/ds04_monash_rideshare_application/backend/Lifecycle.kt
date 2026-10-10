package com.example.ds04_monash_rideshare_application.backend

import com.example.ds04_monash_rideshare_application.backend.routes.haversine
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// How long an ASAP ask waits for a driver before it expires (value lives in SchedulingConfig).
const val ASAP_REQUEST_LIFETIME_MILLIS = SchedulingConfig.ASAP_REQUEST_LIFETIME_MILLIS
// A ride with passengers is auto-completed this long after its estimated arrival.
private const val COMPLETION_GRACE_MILLIS = 30L * 60_000L
// LEGACY time-only overlap margin: two trips closer together than this (either side) count as overlapping. It is NOT
// how anything is judged any more - creating OR accepting a request or ride uses location + travel time + a 10-minute
// buffer (ScheduleFeasibility.kt). It remains for exactly one thing: comparing a journey with a legacy record that
// cannot be placed on the map (an endpoint that is not an approved point), where time is all there is to go on.
const val TRIP_CONFLICT_BUFFER_MILLIS = 30L * 60_000L
// LEGACY: the straight-line speed behind estimatedTripWindow, for records that never had a planned duration.
private const val ESTIMATED_SPEED_METERS_PER_SECOND = 40_000.0 / 3600.0

const val CONFLICT_OWN_PENDING_REQUEST = "OWN_PENDING_REQUEST"
// The passenger already has a live (PENDING/ACCEPTED) request for this same journey. The error body
// carries existingRequestId so the app can take them to it.
const val CONFLICT_DUPLICATE_OPEN_REQUEST = "DUPLICATE_OPEN_REQUEST"
// A clientRequestId was reused for a different request than the one it first identified.
const val CONFLICT_IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED"
// No routing data exists for this pair of approved points, so the trip's own duration cannot be planned.
// Returned as HTTP 503: nothing is wrong with the request, and it succeeds once the data exists.
const val CODE_TRAVEL_TIME_UNAVAILABLE = "TRAVEL_TIME_UNAVAILABLE"

// How far a driver's posted ride may leave from an open ask's requested time and still take it
// (same window as ride matching).
const val ATTACH_TIME_WINDOW_MILLIS = 45L * 60_000L

// Tidies stale records (runs before API calls - see Application.kt). Two steps, so that ordinary calls
// never take the write lock just because this ran:
//  1. DETECT, read-only (a plain deferred read): work out what, if anything, is stale right now.
//     Nothing stale (the usual case) -> return without a write transaction.
//  2. APPLY, only when something is stale: through the scheduling lock path
//     (schedulingTransactionFor in Concurrency.kt), which locks every passenger and driver involved in
//     id order, re-detects under the lock (another call may have tidied some of it already) and then
//     writes. So expiry races with create / accept / cancel / complete the same way they race each other.
// The rules themselves are unchanged:
// - PENDING requests whose departure has passed (ASAP: 30 min after asking) become EXPIRED.
// - Rides past departure with nobody accepted become EXPIRED.
// - Rides with accepted passengers become COMPLETED (with those requests) once the trip is over.
fun expireStaleRecords(now: Long = System.currentTimeMillis()) {
    if (readTransaction { planExpiry(now) }.isEmpty) return
    schedulingTransactionFor(
        usersOf = { plan: ExpiryPlan -> plan.userIds },
        ridesOf = { plan -> plan.rideIds },
        discover = { planExpiry(now) },
        block = { plan -> applyExpiry(plan, now) }
    )
}

// What is stale at one moment, and whose records it touches (so they can be locked first).
class ExpiryPlan(
    val expireRequestIds: List<String>,
    val expireRideIds: List<String>,
    val completeRides: List<CompletedRide>,
    val userIds: Set<String>
) {
    val rideIds: Set<String> get() = (expireRideIds + completeRides.map { it.rideId }).toSet()
    val isEmpty: Boolean get() = expireRequestIds.isEmpty() && expireRideIds.isEmpty() && completeRides.isEmpty()
}

class CompletedRide(val rideId: String, val acceptedRequestIds: List<String>)

// Read-only: safe on a read transaction.
internal fun Transaction.planExpiry(now: Long): ExpiryPlan {
    val ridesById = Rides.selectAll().toList().associateBy { it[Rides.rideId] }
    val users = mutableSetOf<String>()

    val expireRequests = mutableListOf<String>()
    RideRequests.selectAll().where { RideRequests.status eq RideRequestStatus.PENDING }.forEach { request ->
        val deadline = requestDeadline(request, request[RideRequests.rideId]?.let { ridesById[it]?.get(Rides.departureTime) })
        if (now > deadline) {
            expireRequests += request[RideRequests.requestId]
            users += request[RideRequests.passengerId]
        }
    }

    val expireRides = mutableListOf<String>()
    val completeRides = mutableListOf<CompletedRide>()
    ridesById.values
        .filter { it[Rides.status] in setOf(RideStatus.OPEN, RideStatus.CONFIRMED) && it[Rides.departureTime] < now }
        .forEach { ride ->
            val rideId = ride[Rides.rideId]
            val accepted = RideRequests.selectAll().where {
                (RideRequests.rideId eq rideId) and (RideRequests.status eq RideRequestStatus.ACCEPTED)
            }.toList()
            if (accepted.isEmpty()) {
                expireRides += rideId
                users += ride[Rides.driverId]
            } else if (now >= rideWindow(ride).end + COMPLETION_GRACE_MILLIS) {
                completeRides += CompletedRide(rideId, accepted.map { it[RideRequests.requestId] })
                users += ride[Rides.driverId]
                accepted.forEach { users += it[RideRequests.passengerId] }
            }
        }
    return ExpiryPlan(expireRequests, expireRides, completeRides, users)
}

// Must run inside a scheduling transaction that holds the plan's locks.
internal fun Transaction.applyExpiry(plan: ExpiryPlan, now: Long) {
    plan.expireRequestIds.forEach { markRequest(it, RideRequestStatus.EXPIRED, now) }
    plan.expireRideIds.forEach { rideId ->
        Rides.update({ Rides.rideId eq rideId }) { it[status] = RideStatus.EXPIRED }
    }
    plan.completeRides.forEach { completed ->
        Rides.update({ Rides.rideId eq completed.rideId }) { it[status] = RideStatus.COMPLETED }
        completed.acceptedRequestIds.forEach { markRequest(it, RideRequestStatus.COMPLETED, now) }
    }
}

// When a PENDING request stops being something anyone can accept: the departure of the ride it is aimed at, else (an
// open ask) the time it asked for, or 30 minutes after an ASAP ask was made.
fun requestDeadline(request: ResultRow, attachedRideDeparture: Long?): Long = attachedRideDeparture
    ?: if (request[RideRequests.isAsap]) request[RideRequests.createdAt] + ASAP_REQUEST_LIFETIME_MILLIS
    else request[RideRequests.desiredDepartureTime] ?: (request[RideRequests.createdAt] + ASAP_REQUEST_LIFETIME_MILLIS)

// A PENDING request that another, stronger commitment has made physically impossible (RideRequestStatus.CONFLICTED):
// terminal, and it keeps the structured reason (ScheduleConflictDetails as JSON) so the passenger can be told why.
fun markConflicted(requestId: String, conflictDetailsJson: String, at: Long) {
    RideRequests.update({ (RideRequests.requestId eq requestId) and (RideRequests.status eq RideRequestStatus.PENDING) }) {
        it[status] = RideRequestStatus.CONFLICTED
        it[respondedAt] = at
        it[conflictDetails] = conflictDetailsJson
    }
}

private fun markRequest(requestId: String, newStatus: String, at: Long) {
    RideRequests.update({ RideRequests.requestId eq requestId }) {
        it[status] = newStatus
        it[respondedAt] = at
    }
}

data class TripWindow(val start: Long, val end: Long) {
    fun overlaps(other: TripWindow): Boolean =
        start < other.end + TRIP_CONFLICT_BUFFER_MILLIS && other.start < end + TRIP_CONFLICT_BUFFER_MILLIS
}

// A ride's end for COMPLETION purposes (expiry/auto-complete below) - not for deciding whether anything fits. Its
// interval is its backend PLANNED arrival (travel-time matrix). Only a ride from before planning existed falls back
// to its stored estimatedDurationSeconds - MapKit's display figure - and that use is counted (LegacyScheduling).
fun rideWindow(ride: ResultRow): TripWindow {
    val start = ride[Rides.departureTime]
    ride[Rides.plannedArrivalTime]?.let { return TripWindow(start, it) }
    LegacyScheduling.noteLegacyEstimate()
    return TripWindow(start, start + ride[Rides.estimatedDurationSeconds] * 1000)
}

fun tripWindow(departureTime: Long, estimatedDurationSeconds: Long) =
    TripWindow(departureTime, departureTime + estimatedDurationSeconds * 1000)

// LEGACY ONLY: straight-line distance at 40 km/h, for records that never had a planned duration. Nothing that
// creates or checks a NEW journey may call this (LegacyScheduling counts calls; a test guards it).
fun estimatedTripWindow(start: Long, originLat: Double, originLng: Double, destinationLat: Double, destinationLng: Double): TripWindow {
    LegacyScheduling.noteLegacyEstimate()
    val seconds = (haversine(originLat, originLng, destinationLat, destinationLng) / ESTIMATED_SPEED_METERS_PER_SECOND).toLong()
    return TripWindow(start, start + seconds * 1000)
}

// Cancels one PENDING or ACCEPTED request (must be called inside a transaction). An accepted seat
// goes back to its ride, and a CONFIRMED ride with nobody left accepted reopens. Cancelling a
// pending request never changes capacity.
fun cancelRequest(request: ResultRow, at: Long) {
    val wasAccepted = request[RideRequests.status] == RideRequestStatus.ACCEPTED
    val rideId = request[RideRequests.rideId]
    val ride = rideId?.let { rid -> Rides.selectAll().where { Rides.rideId eq rid }.firstOrNull() }
    if (wasAccepted && rideId != null && ride != null) {
        Rides.update({ Rides.rideId eq rideId }) {
            it[seatsAvailable] = ride[Rides.seatsAvailable] + request[RideRequests.seatsRequested]
        }
    }
    markRequest(request[RideRequests.requestId], RideRequestStatus.CANCELLED, at)
    // Counted after cancelling this request. Pending/history rows do not keep an empty ride confirmed.
    if (wasAccepted && rideId != null && ride?.get(Rides.status) == RideStatus.CONFIRMED) {
        val acceptedRemaining = RideRequests.selectAll().where {
            (RideRequests.rideId eq rideId) and (RideRequests.status eq RideRequestStatus.ACCEPTED)
        }.count()
        if (acceptedRemaining == 0L) {
            Rides.update({ (Rides.rideId eq rideId) and (Rides.status eq RideStatus.CONFIRMED) }) {
                it[status] = RideStatus.OPEN
            }
        }
    }
}

fun cancelRequests(requestIds: List<String>, at: Long) {
    requestIds.forEach { markRequest(it, RideRequestStatus.CANCELLED, at) }
}

fun formatTripTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH))
