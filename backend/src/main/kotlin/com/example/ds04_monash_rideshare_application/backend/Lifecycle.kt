package com.example.ds04_monash_rideshare_application.backend

import com.example.ds04_monash_rideshare_application.backend.routes.haversine
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// How long an ASAP ask waits for a driver before it expires.
const val ASAP_REQUEST_LIFETIME_MILLIS = 30L * 60_000L
// A ride with passengers is auto-completed this long after its estimated arrival.
private const val COMPLETION_GRACE_MILLIS = 30L * 60_000L
// Two trips closer together than this (either side) count as overlapping for one person.
const val TRIP_CONFLICT_BUFFER_MILLIS = 30L * 60_000L
// Standalone requests never had a real route computed - same 40 km/h estimate as accept uses.
private const val ESTIMATED_SPEED_METERS_PER_SECOND = 40_000.0 / 3600.0

const val CONFLICT_OWN_PENDING_REQUEST = "OWN_PENDING_REQUEST"

// How far a driver's posted ride may leave from an open ask's requested time and still take it
// (same window as ride matching).
const val ATTACH_TIME_WINDOW_MILLIS = 45L * 60_000L

// There's no scheduler in this trial backend, so stale records are tidied lazily: this runs before
// every API call (Application.kt) and is cheap at trial scale.
// - PENDING requests whose departure has passed (ASAP: 30 min after asking) become EXPIRED.
// - Rides past departure with nobody accepted become EXPIRED.
// - Rides with accepted passengers become COMPLETED (with those requests) once the trip is over.
fun expireStaleRecords(now: Long = System.currentTimeMillis()) = transaction {
    val ridesById = Rides.selectAll().toList().associateBy { it[Rides.rideId] }

    RideRequests.selectAll().where { RideRequests.status eq RideRequestStatus.PENDING }.forEach { request ->
        val deadline = request[RideRequests.rideId]?.let { ridesById[it]?.get(Rides.departureTime) }
            ?: if (request[RideRequests.isAsap]) request[RideRequests.createdAt] + ASAP_REQUEST_LIFETIME_MILLIS
            else request[RideRequests.desiredDepartureTime] ?: (request[RideRequests.createdAt] + ASAP_REQUEST_LIFETIME_MILLIS)
        if (now > deadline) markRequest(request[RideRequests.requestId], RideRequestStatus.EXPIRED, now)
    }

    ridesById.values
        .filter { it[Rides.status] in setOf(RideStatus.OPEN, RideStatus.CONFIRMED) && it[Rides.departureTime] < now }
        .forEach { ride ->
            val rideId = ride[Rides.rideId]
            val accepted = RideRequests.selectAll().where {
                (RideRequests.rideId eq rideId) and (RideRequests.status eq RideRequestStatus.ACCEPTED)
            }.toList()
            if (accepted.isEmpty()) {
                Rides.update({ Rides.rideId eq rideId }) { it[status] = RideStatus.EXPIRED }
            } else if (now >= rideWindow(ride).end + COMPLETION_GRACE_MILLIS) {
                Rides.update({ Rides.rideId eq rideId }) { it[status] = RideStatus.COMPLETED }
                accepted.forEach { markRequest(it[RideRequests.requestId], RideRequestStatus.COMPLETED, now) }
            }
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

fun rideWindow(ride: ResultRow): TripWindow {
    val start = ride[Rides.departureTime]
    return TripWindow(start, start + ride[Rides.estimatedDurationSeconds] * 1000)
}

fun tripWindow(departureTime: Long, estimatedDurationSeconds: Long) =
    TripWindow(departureTime, departureTime + estimatedDurationSeconds * 1000)

// Must be called inside a transaction.
fun requestWindow(request: ResultRow): TripWindow? {
    request[RideRequests.rideId]?.let { rideId ->
        return Rides.selectAll().where { Rides.rideId eq rideId }.firstOrNull()?.let(::rideWindow)
    }
    val start = if (request[RideRequests.isAsap]) request[RideRequests.createdAt]
    else request[RideRequests.desiredDepartureTime] ?: return null
    return estimatedTripWindow(
        start,
        request[RideRequests.originLat] ?: return null,
        request[RideRequests.originLng] ?: return null,
        request[RideRequests.destinationLat] ?: return null,
        request[RideRequests.destinationLng] ?: return null
    )
}

fun estimatedTripWindow(start: Long, originLat: Double, originLng: Double, destinationLat: Double, destinationLng: Double): TripWindow {
    val seconds = (haversine(originLat, originLng, destinationLat, destinationLng) / ESTIMATED_SPEED_METERS_PER_SECOND).toLong()
    return TripWindow(start, start + seconds * 1000)
}

// What a user already has around a given time. "blocking" can't be overridden (you can't drive
// and ride, or drive two cars, at once); "pendingRequestIds" are the user's own still-waiting
// asks, which can be cancelled to make room. Must be called inside a transaction.
class TripConflicts(val blocking: String?, val pendingRequestIds: List<String>)

fun findTripConflicts(
    userId: String,
    window: TripWindow,
    ignoreRideId: String? = null,
    ignoreRequestId: String? = null
): TripConflicts {
    val drivingClash = Rides.selectAll()
        .where { Rides.driverId eq userId }
        .filter { it[Rides.status] in setOf(RideStatus.OPEN, RideStatus.CONFIRMED) && it[Rides.rideId] != ignoreRideId }
        .firstOrNull { rideWindow(it).overlaps(window) }
    if (drivingClash != null) {
        return TripConflicts("you're already driving a ride at ${formatTripTime(drivingClash[Rides.departureTime])}", emptyList())
    }

    val ownRequests = RideRequests.selectAll()
        .where { RideRequests.passengerId eq userId }
        .filter { it[RideRequests.requestId] != ignoreRequestId }
    val ridingClash = ownRequests
        .filter { it[RideRequests.status] == RideRequestStatus.ACCEPTED && it[RideRequests.rideId] != ignoreRideId }
        .firstOrNull { requestWindow(it)?.overlaps(window) == true }
    if (ridingClash != null) {
        val start = requestWindow(ridingClash)!!.start
        return TripConflicts("you're already booked as a passenger at ${formatTripTime(start)}", emptyList())
    }

    val pending = ownRequests
        .filter { it[RideRequests.status] == RideRequestStatus.PENDING }
        .filter { requestWindow(it)?.overlaps(window) == true }
        .map { it[RideRequests.requestId] }
    return TripConflicts(null, pending)
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
