package com.example.ds04_monash_rideshare_application.backend

import com.example.ds04_monash_rideshare_application.backend.routes.haversine
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.UUID

// A driver accepting a request, decided by the SAME physical-feasibility model that decides whether a request may
// be made (ScheduleFeasibility.kt). It must run inside the scheduling transaction that holds the passenger's, the
// acting driver's and the rides' locks (Concurrency.kt), so everything below is read and written as one step.
//
//   1. Reload the request under the lock; refuse it if it is no longer actionable (terminal status, expired).
//   2. Decide what it will ride: the ride it is already attached to, one of the driver's posted rides (which must
//      actually serve the passenger's route - see requireRideServesRequest), or a new ride made from the request.
//   3. The accepted journey must fit the passenger's COMMITTED journeys. An accepted commitment they already have
//      always wins; if the new one does not fit around it, the accept is refused (SCHEDULE_INFEASIBLE).
//   4. A NEW ride must also fit the driver's own committed journeys. (Joining a ride that already exists adds nothing
//      to the driver's schedule - the ride is already on it - so the driver is not checked again.) The driver's own
//      pending requests that the new ride would make impossible need their confirmation first (OWN_PENDING_REQUEST).
//   5. Write the acceptance (seat, status).
//   6. The passenger's other PENDING requests for the SAME intended journey (same journeyIntentId) were only options
//      for it: they become CANCELLED, stamped with the accept time so Undo can restore exactly them.
//   7. The passenger's remaining PENDING journeys are re-checked against the new timeline, in departure order; any that
//      no longer fit become CONFLICTED (terminal, with the reason) - not CANCELLED, and not restored by Undo.
const val MESSAGE_PASSENGER_UNAVAILABLE = "This passenger is no longer available for this ride."

// What a driver is told when the request they pressed Accept on is no longer there to accept. Machine-readable codes so the
// app can refresh instead of only showing text, and deliberately free of detail: they never say WHICH driver won, and never
// why a request was conflicted (that describes the passenger's other trips).
//  * REQUEST_ALREADY_ACCEPTED - someone (another driver, or you a moment ago) already holds it. Two drivers pressing
//    Accept on the same open request: whichever transaction commits first wins, the other lands here.
//  * REQUEST_UNAVAILABLE - it ended some other way: cancelled (including a lost alternative), declined, completed or
//    CONFLICTED. (Expired keeps its own message.)
const val CODE_REQUEST_ALREADY_ACCEPTED = "REQUEST_ALREADY_ACCEPTED"
const val CODE_REQUEST_UNAVAILABLE = "REQUEST_UNAVAILABLE"
const val MESSAGE_ALREADY_ACCEPTED_BY_ANOTHER = "This request has already been accepted by another driver."
const val MESSAGE_ALREADY_ACCEPTED_BY_YOU = "You have already accepted this request."
const val MESSAGE_REQUEST_UNAVAILABLE = "This request is no longer available."

// The refusal for a driver who tries to ACCEPT a request that is no longer PENDING, or null if it still is.
fun acceptanceRefusal(currentStatus: String, isOwningDriver: Boolean): ConflictException? = when (currentStatus) {
    RideRequestStatus.PENDING -> null
    RideRequestStatus.ACCEPTED ->
        ConflictException(if (isOwningDriver) MESSAGE_ALREADY_ACCEPTED_BY_YOU else MESSAGE_ALREADY_ACCEPTED_BY_ANOTHER, CODE_REQUEST_ALREADY_ACCEPTED)
    else -> ConflictException(MESSAGE_REQUEST_UNAVAILABLE, CODE_REQUEST_UNAVAILABLE)
}

private val detailsJson = Json { encodeDefaults = true }

fun Transaction.acceptRequest(request: ResultRow, userId: String, body: UpdateRideRequestStatusBody, travelTimes: TravelTimeProvider) {
    val requestId = request[RideRequests.requestId]
    val passengerId = request[RideRequests.passengerId]
    val existingRideId = request[RideRequests.rideId]
    val existingRide = existingRideId?.let { rid -> Rides.selectAll().where { Rides.rideId eq rid }.firstOrNull() }
    val acceptedAt = System.currentTimeMillis()

    if (passengerId == userId) throw ForbiddenException("You can't accept your own request")
    if (existingRide != null && existingRide[Rides.driverId] != userId) throw ForbiddenException("You don't own that ride")
    if (existingRideId != null && body.targetRideId != null && existingRideId != body.targetRideId) {
        throw ValidationException("This request is already tied to a different ride")
    }
    // The lazy expiry runs before each call, but its deadline can pass between that and this lock: a request whose
    // time has gone is never accepted.
    if (acceptedAt > requestDeadline(request, existingRide?.get(Rides.departureTime))) {
        throw ConflictException("This request has expired - its time has already passed")
    }

    val intent = request[RideRequests.journeyIntentId]

    // ---- 2. what will it ride? -----------------------------------------------------------------------------
    val targetRide: ResultRow? = existingRide ?: body.targetRideId?.let { id ->
        Rides.selectAll().where { Rides.rideId eq id }.firstOrNull() ?: throw NotFoundException("Target ride not found")
    }
    if (targetRide != null) {
        if (targetRide[Rides.driverId] != userId) throw ForbiddenException("You don't own that ride")
        // CONFIRMED (not just OPEN) is fine too - a ride with other confirmed passengers can still take more as long as
        // it has room (see the "more seats left, still joinable" filter on POST /rides/{rideId}/requests and GET /rides).
        if (targetRide[Rides.status] !in setOf(RideStatus.OPEN, RideStatus.CONFIRMED)) throw ConflictException("That ride isn't open")
        if (existingRide == null) {
            // An open ask joins a ride that leaves around the time the passenger asked for - not last week's or next
            // week's - AND that really travels the passenger's route.
            val wanted = if (request[RideRequests.isAsap]) acceptedAt else request[RideRequests.desiredDepartureTime] ?: acceptedAt
            if (kotlin.math.abs(targetRide[Rides.departureTime] - wanted) > ATTACH_TIME_WINDOW_MILLIS) {
                throw ValidationException(
                    "That ride leaves ${formatTripTime(targetRide[Rides.departureTime])}, but they asked for " +
                        "${formatTripTime(wanted)}. Pick a ride near their time, or create a new ride."
                )
            }
            requireRideServesRequest(request, targetRide)
        }
    }
    val plan: NewRidePlan? = if (targetRide == null) planNewRide(request, userId, body, travelTimes, acceptedAt) else null

    // ---- 3. the passenger's committed journeys win --------------------------------------------------------------
    val passengerJourney = targetRide?.let { acceptedRideJourney(request, it, travelTimes) } ?: plan!!.passengerJourney(requestId)
    if (committedConflictFor(passengerId, passengerJourney, travelTimes) != null) {
        // Deliberately no details: they would describe the passenger's OTHER trips to the driver.
        throw ConflictException(MESSAGE_PASSENGER_UNAVAILABLE, CODE_SCHEDULE_INFEASIBLE)
    }

    // ---- 4. a new ride must fit the driver's own schedule -------------------------------------------------------
    if (plan != null) {
        val driverJourney = plan.driverJourney()
        val driverJourneys = liveJourneysOf(userId, travelTimes)
        findScheduleConflict(buildTimelineNodes(driverJourneys.committed()), driverJourney, travelTimes)
            ?.let { throw scheduleInfeasibleError(it) }
        val displaced = pendingJourneysToInvalidate(driverJourneys + driverJourney, travelTimes)
        if (displaced.isNotEmpty()) {
            if (!body.cancelMyConflictingRequests) {
                throw ConflictException(
                    "You have your own ride request around ${formatTripTime(plan.departure)}. " +
                        "Drive this passenger instead? Your request will be cancelled.",
                    CONFLICT_OWN_PENDING_REQUEST
                )
            }
            // The driver chose this, so it is a deliberate cancellation (restorable by Undo), not a system conflict.
            cancelRequests(displaced.map { it.journey.id }, acceptedAt)
        }
    }

    // ---- 5. write the acceptance ---------------------------------------------------------------------------
    val targetRideId = targetRide?.get(Rides.rideId) ?: insertPlannedRide(plan!!, userId, acceptedAt)
    // Re-read seatsAvailable inside this same transaction right before writing, holding a row lock (forUpdate), so two
    // drivers racing to accept can't both pass this check for the same seat. SQLite has no row-level locking - this is
    // a no-op there and the check-then-write is safe only because SQLite serialises writers at the connection/file
    // level - but on a multi-writer DB (e.g. Postgres) forUpdate blocks the second transaction here until the first
    // commits, so it re-reads the now-updated count instead of racing it.
    val freshRide = Rides.selectAll().where { Rides.rideId eq targetRideId }.forUpdate().first()
    if (freshRide[Rides.seatsAvailable] < request[RideRequests.seatsRequested]) {
        throw ConflictException("Not enough seats left - someone else was just accepted")
    }
    Rides.update({ Rides.rideId eq targetRideId }) {
        it[seatsAvailable] = freshRide[Rides.seatsAvailable] - request[RideRequests.seatsRequested]
        it[status] = RideStatus.CONFIRMED
    }
    // Compare-and-set: only a request that is STILL pending becomes accepted. The scheduling lock already makes a second
    // driver reload the request and see it taken; this makes the transition itself refuse to happen twice, so it holds even
    // if a lock were ever missed. (It also rolls back the seat and any ride created above.)
    val taken = RideRequests.update({ (RideRequests.requestId eq requestId) and (RideRequests.status eq RideRequestStatus.PENDING) }) {
        it[rideId] = targetRideId
        it[status] = RideRequestStatus.ACCEPTED
        it[respondedAt] = acceptedAt
        if (request[RideRequests.driverDecisionAt] == null) it[driverDecisionAt] = acceptedAt
    }
    if (taken != 1) throw ConflictException(MESSAGE_ALREADY_ACCEPTED_BY_ANOTHER, CODE_REQUEST_ALREADY_ACCEPTED)

    // ---- 6. the other options for the same intended journey -----------------------------------------------------
    if (intent != null) {
        val alternatives = RideRequests.selectAll().where {
            (RideRequests.passengerId eq passengerId) and (RideRequests.journeyIntentId eq intent) and
                (RideRequests.status eq RideRequestStatus.PENDING) and (RideRequests.requestId neq requestId)
        }.map { it[RideRequests.requestId] }
        cancelRequests(alternatives, acceptedAt)
    }

    // ---- 7. everything else the passenger was waiting on, re-checked against the new timeline --------------------
    pendingJourneysToInvalidate(liveJourneysOf(passengerId, travelTimes), travelTimes).forEach { invalidated ->
        markConflicted(invalidated.journey.id, detailsJson.encodeToString(ScheduleConflictDetails.serializer(), invalidated.conflict), acceptedAt)
    }
}

// ---- route compatibility (open request -> an EXISTING ride) ---------------------------------------------------------
//
// What the architecture supports: a ride serves ONE route, its origin to its destination, as approved points. Its
// intermediate stops (RideStops) are display-only free-form coordinates - not approved points, and the ride's duration
// is always the direct origin->destination one - so they cannot be the passenger's pickup or drop-off and there is no
// partial-route (sub-segment) matching. The rule is therefore exact: the passenger's canonical pickup is the ride's
// canonical origin, and their canonical drop-off is the ride's canonical destination. A reversed route fails (pickup and
// drop-off are swapped). A request or ride that cannot be reduced to approved points (legacy data from a retired hub)
// cannot be verified and is refused - such a request can still be accepted by creating a NEW ride from it.
fun requireRideServesRequest(request: ResultRow, ride: ResultRow) {
    val wantsOrigin = request[RideRequests.originPointId]
        ?: request[RideRequests.originLat]?.let { lat -> request[RideRequests.originLng]?.let { lng -> resolveApprovedPoint(lat, lng)?.id } }
    val wantsDestination = request[RideRequests.destinationPointId]
        ?: request[RideRequests.destinationLat]?.let { lat -> request[RideRequests.destinationLng]?.let { lng -> resolveApprovedPoint(lat, lng)?.id } }
    val servesOrigin = ride[Rides.originPointId] ?: resolveApprovedPoint(ride[Rides.originLat], ride[Rides.originLng])?.id
    val servesDestination = ride[Rides.destinationPointId] ?: resolveApprovedPoint(ride[Rides.destinationLat], ride[Rides.destinationLng])?.id
    if (wantsOrigin == null || wantsDestination == null || servesOrigin == null || servesDestination == null) {
        throw ValidationException("This ride's route can't be matched to the passenger's request. Create a new ride for them instead.")
    }
    fun name(id: String) = approvedPointById(id)?.name ?: id
    if (wantsOrigin != servesOrigin || wantsDestination != servesDestination) {
        throw ValidationException(
            "That ride goes from ${name(servesOrigin)} to ${name(servesDestination)}, but the passenger wants " +
                "${name(wantsOrigin)} to ${name(wantsDestination)}. Pick a ride on their route, or create a new ride."
        )
    }
}

// ---- a ride made from an open request -------------------------------------------------------------------------------

private class NewRidePlan(
    val request: ResultRow,
    val vehicleId: String,
    val seatsTotal: Int,
    val distanceMeters: Double,
    val origin: ApprovedPoint?,
    val destination: ApprovedPoint?,
    val plannedDuration: Long?,
    val departure: Long,
    val estimatedDurationSeconds: Long
) {
    private val placed get() = origin != null && destination != null && plannedDuration != null
    private val arrival get() = departure + (plannedDuration ?: estimatedDurationSeconds) * 1000

    // The passenger's journey on this ride, and the driver's own - the same interval, one as passenger, one as driver.
    fun passengerJourney(requestId: String) = journey(requestId, JourneyKind.OPEN_REQUEST, JourneyState.ACCEPTED)
    fun driverJourney() = journey("(new ride)", JourneyKind.RIDE, JourneyState.DRIVING)

    private fun journey(id: String, kind: JourneyKind, state: JourneyState) =
        if (placed) Journey(id, kind, state, origin!!.id, destination!!.id, departure, arrival)
        else Journey(id, kind, state, null, null, departure, arrival, unverified = true)
}

private fun Transaction.planNewRide(
    request: ResultRow, userId: String, body: UpdateRideRequestStatusBody, travelTimes: TravelTimeProvider, acceptedAt: Long
): NewRidePlan {
    val originLat = request[RideRequests.originLat]
    val originLng = request[RideRequests.originLng]
    val destinationLat = request[RideRequests.destinationLat]
    val destinationLng = request[RideRequests.destinationLng]
    if (originLat == null || originLng == null || destinationLat == null || destinationLng == null) {
        throw ValidationException("targetRideId is required - this request has no route to create a ride from")
    }
    val seatsTotal = (body.newRideSeatsTotal ?: request[RideRequests.seatsRequested]).coerceAtLeast(request[RideRequests.seatsRequested])
    val vehicle = requireDriverVehicle(userId, seatsTotal)
    // The ride keeps the planning decision the request was made with (approved points and the travel-time-matrix
    // duration, stored at creation) rather than recalculating it. A LEGACY request has none stored: its endpoints are
    // resolved now and the matrix consulted, and only if that is impossible does the ride fall back to the old
    // straight-line estimate at 40 km/h.
    val origin = request[RideRequests.originPointId]?.let(::approvedPointById) ?: resolveApprovedPoint(originLat, originLng)
    val destination = request[RideRequests.destinationPointId]?.let(::approvedPointById) ?: resolveApprovedPoint(destinationLat, destinationLng)
    val planned = request[RideRequests.plannedDurationSeconds]
        ?: if (origin != null && destination != null) travelTimes.travelSeconds(origin.id, destination.id)?.takeIf { it > 0 } else null
    val distanceMeters = haversine(originLat, originLng, destinationLat, destinationLng)
    return NewRidePlan(
        request = request,
        vehicleId = vehicle[Vehicles.vehicleId],
        seatsTotal = seatsTotal,
        distanceMeters = distanceMeters,
        origin = origin,
        destination = destination,
        plannedDuration = planned,
        departure = request[RideRequests.desiredDepartureTime] ?: acceptedAt,
        // There is no MapKit figure for a ride the server makes, so the display duration is the planned one.
        estimatedDurationSeconds = planned ?: run {
            LegacyScheduling.noteLegacyEstimate()
            (distanceMeters / (40_000.0 / 3600.0)).toLong()
        }
    )
}

private fun Transaction.insertPlannedRide(plan: NewRidePlan, driverId: String, acceptedAt: Long): String {
    val newRideId = "ride-${UUID.randomUUID()}"
    val request = plan.request
    Rides.insert {
        it[rideId] = newRideId
        it[Rides.driverId] = driverId
        it[vehicleId] = plan.vehicleId
        it[originLat] = request[RideRequests.originLat]!!
        it[originLng] = request[RideRequests.originLng]!!
        it[destinationLat] = request[RideRequests.destinationLat]!!
        it[destinationLng] = request[RideRequests.destinationLng]!!
        it[distanceMeters] = plan.distanceMeters
        it[estimatedDurationSeconds] = plan.estimatedDurationSeconds
        it[originPointId] = plan.origin?.id
        it[destinationPointId] = plan.destination?.id
        it[plannedDurationSeconds] = plan.plannedDuration
        it[plannedArrivalTime] = plan.plannedDuration?.let { seconds -> plan.departure + seconds * 1000 }
        it[departureTime] = plan.departure
        it[seatsTotal] = plan.seatsTotal
        it[seatsAvailable] = plan.seatsTotal
        it[notes] = null
        it[status] = RideStatus.OPEN
        it[recurringGroupId] = null
        // Same as respondedAt on the request - undo uses it to spot a ride made only for this accept.
        it[createdAt] = acceptedAt
    }
    return newRideId
}
