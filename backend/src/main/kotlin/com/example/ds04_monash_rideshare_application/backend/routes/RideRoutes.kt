package com.example.ds04_monash_rideshare_application.backend.routes

import com.example.ds04_monash_rideshare_application.backend.readTransaction
import com.example.ds04_monash_rideshare_application.backend.schedulingTransaction
import com.example.ds04_monash_rideshare_application.backend.schedulingTransactionFor
import com.example.ds04_monash_rideshare_application.backend.ConflictException
import com.example.ds04_monash_rideshare_application.backend.CreateRideRequest
import com.example.ds04_monash_rideshare_application.backend.CreateStopInput
import com.example.ds04_monash_rideshare_application.backend.ForbiddenException
import com.example.ds04_monash_rideshare_application.backend.MatchSearchResponse
import com.example.ds04_monash_rideshare_application.backend.NotFoundException
import com.example.ds04_monash_rideshare_application.backend.Ratings
import com.example.ds04_monash_rideshare_application.backend.SchedulingConfig
import com.example.ds04_monash_rideshare_application.backend.ServerClock
import com.example.ds04_monash_rideshare_application.backend.CODE_RIDE_NOT_STARTED
import com.example.ds04_monash_rideshare_application.backend.MESSAGE_RIDE_NOT_STARTED
import com.example.ds04_monash_rideshare_application.backend.revalidateSchedulesAfterCompletion
import com.example.ds04_monash_rideshare_application.backend.requireApprovedRoute
import com.example.ds04_monash_rideshare_application.backend.RideDto
import com.example.ds04_monash_rideshare_application.backend.RideRequestStatus
import com.example.ds04_monash_rideshare_application.backend.RideRequests
import com.example.ds04_monash_rideshare_application.backend.RideStatus
import com.example.ds04_monash_rideshare_application.backend.RideStops
import com.example.ds04_monash_rideshare_application.backend.Rides
import com.example.ds04_monash_rideshare_application.backend.UpdateRideRequest
import com.example.ds04_monash_rideshare_application.backend.Users
import com.example.ds04_monash_rideshare_application.backend.ValidationException
import com.example.ds04_monash_rideshare_application.backend.requireBookableDeparture
import com.example.ds04_monash_rideshare_application.backend.ApprovedRoute
import com.example.ds04_monash_rideshare_application.backend.CODE_TRAVEL_TIME_UNAVAILABLE
import com.example.ds04_monash_rideshare_application.backend.ServiceUnavailableException
import com.example.ds04_monash_rideshare_application.backend.TravelTimeProvider
import com.example.ds04_monash_rideshare_application.backend.drivingJourney
import com.example.ds04_monash_rideshare_application.backend.scheduleConflictFor
import com.example.ds04_monash_rideshare_application.backend.scheduleInfeasibleError
import com.example.ds04_monash_rideshare_application.backend.formatTripTime
import com.example.ds04_monash_rideshare_application.backend.tripWindow
import com.example.ds04_monash_rideshare_application.backend.Vehicles
import com.example.ds04_monash_rideshare_application.backend.requireDriverVehicle
import com.example.ds04_monash_rideshare_application.backend.matching.MatchingEngine
import com.example.ds04_monash_rideshare_application.backend.matching.RideCandidate
import com.example.ds04_monash_rideshare_application.backend.matching.SearchCriteria
import com.example.ds04_monash_rideshare_application.backend.requireUserId
import com.example.ds04_monash_rideshare_application.backend.toRideDto
import com.example.ds04_monash_rideshare_application.backend.toRideStopDto
import com.example.ds04_monash_rideshare_application.backend.toUserSummaryDto
import com.example.ds04_monash_rideshare_application.backend.toVehicleDto
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

fun Route.rideRoutes(travelTimes: TravelTimeProvider) {
    // GET /rides?date=&originLat=&originLng=&destinationLat=&destinationLng=&availableOnly=
    // Plain filtered browse - no ranking. Use /rides/match for a ranked passenger search.
    get("/rides") {
        val callerId = call.requireUserId()
        val dateParam = call.request.queryParameters["date"]
        val availableOnly = call.request.queryParameters["availableOnly"]?.toBoolean() ?: true
        val originLat = call.request.queryParameters["originLat"]?.toDoubleOrNull()
        val originLng = call.request.queryParameters["originLng"]?.toDoubleOrNull()
        val destinationLat = call.request.queryParameters["destinationLat"]?.toDoubleOrNull()
        val destinationLng = call.request.queryParameters["destinationLng"]?.toDoubleOrNull()
        val radiusMeters = call.request.queryParameters["radiusMeters"]?.toDoubleOrNull() ?: 5_000.0

        val (rides, alreadyRequestedRideIds) = readTransaction {
            // CONFIRMED rides remain discoverable while seats are available: approval of one
            // passenger should not hide the remaining seats from other passengers.
            val rows = Rides.selectAll().where { Rides.status inList listOf(RideStatus.OPEN, RideStatus.CONFIRMED) }.toList()
            enrichRideDtos(rows) to callerActiveRequestedRideIds(callerId)
        }
        val filtered = rides.filter { ride ->
            // A ride you drive isn't a ride you can join - exclude it from browse/search rather
            // than showing it and relying on the passenger-request endpoint's rejection.
            if (ride.driverId == callerId) return@filter false
            // Already requested (pending or accepted) - joining shouldn't leave the ride sitting
            // in your own browse list, even though it correctly still shows for everyone else.
            if (ride.rideId in alreadyRequestedRideIds) return@filter false
            if (availableOnly && ride.seatsAvailable <= 0) return@filter false
            if (dateParam != null && !isSameCalendarDay(ride.departureTime, dateParam)) return@filter false
            if (originLat != null && originLng != null) {
                val gap = haversine(originLat, originLng, ride.originLat, ride.originLng)
                if (gap > radiusMeters) return@filter false
            }
            if (destinationLat != null && destinationLng != null) {
                val gap = haversine(destinationLat, destinationLng, ride.destinationLat, ride.destinationLng)
                if (gap > radiusMeters) return@filter false
            }
            true
        }
        call.respond(filtered)
    }

    // GET /rides/match - ranked search using the weighted matching pipeline (FR-04).
    get("/rides/match") {
        val callerId = call.requireUserId()
        val originLat = call.request.queryParameters["originLat"]?.toDoubleOrNull()
            ?: throw ValidationException("originLat is required")
        val originLng = call.request.queryParameters["originLng"]?.toDoubleOrNull()
            ?: throw ValidationException("originLng is required")
        val destinationLat = call.request.queryParameters["destinationLat"]?.toDoubleOrNull()
            ?: throw ValidationException("destinationLat is required")
        val destinationLng = call.request.queryParameters["destinationLng"]?.toDoubleOrNull()
            ?: throw ValidationException("destinationLng is required")
        val departureTime = call.request.queryParameters["departureTime"]?.toLongOrNull()
            ?: throw ValidationException("departureTime is required")
        val seatsNeeded = call.request.queryParameters["seatsNeeded"]?.toIntOrNull() ?: 1

        val criteria = SearchCriteria(originLat, originLng, destinationLat, destinationLng, departureTime, seatsNeeded)

        val (candidates, ridesByRideId) = readTransaction {
            val alreadyRequestedRideIds = callerActiveRequestedRideIds(callerId)
            val rows = Rides.selectAll().where { Rides.status inList listOf(RideStatus.OPEN, RideStatus.CONFIRMED) }
                .filter { it[Rides.driverId] != callerId && it[Rides.rideId] !in alreadyRequestedRideIds }
            val candidates = rows.map {
                RideCandidate(
                    rideId = it[Rides.rideId],
                    originLat = it[Rides.originLat],
                    originLng = it[Rides.originLng],
                    destinationLat = it[Rides.destinationLat],
                    destinationLng = it[Rides.destinationLng],
                    departureTime = it[Rides.departureTime],
                    seatsAvailable = it[Rides.seatsAvailable]
                )
            }
            candidates to enrichRideDtos(rows).associateBy { it.rideId }
        }

        var results = MatchingEngine.rank(criteria, candidates)
        var usedFallback = false
        if (results.isEmpty()) {
            results = MatchingEngine.fallback(criteria, candidates)
            usedFallback = true
        }

        val ranked = results.mapNotNull { result ->
            ridesByRideId[result.ride.rideId]?.let { ride -> ride.copy(matchScore = result.score) }
        }
        call.respond(MatchSearchResponse(usedFallback, ranked))
    }

    post("/rides") {
        val driverId = call.requireUserId()
        val body = call.receive<CreateRideRequest>()
        if (body.seatsTotal <= 0) throw ValidationException("seatsTotal must be positive")
        requireBookableDeparture(body.departureTime)
        // Same endpoint rule as a ride request: approved points only, a campus at one end, two different places.
        val route = requireApprovedRoute(body.originLat, body.originLng, body.destinationLat, body.destinationLng)

        val rideId = "ride-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val groupId = if (body.repeatDaysOfWeek.isNotEmpty()) "recur-${UUID.randomUUID()}" else null

        // Reads the driver's schedule, then adds rides to it: one step under the driver's lock.
        schedulingTransaction(listOf(driverId)) {
            val vehicleId = requireDriverVehicle(driverId, body.seatsTotal)[Vehicles.vehicleId]
            // How long this ride takes for SCHEDULING comes from the backend's travel-time matrix, decided once here and
            // stored. body.estimatedDurationSeconds is MapKit's figure for the route preview: it is kept for display and
            // never used to decide whether anything fits. No matrix entry means the ride is refused (recoverably);
            // nothing falls back to MapKit, distance or a fixed speed.
            val plannedSeconds = travelTimes.travelSeconds(route.origin.id, route.destination.id)
                ?: throw ServiceUnavailableException(
                    "We couldn't work out the travel time for this trip right now, so it can't be offered yet. Please try again later.",
                    CODE_TRAVEL_TIME_UNAVAILABLE
                )
            // A person can't be in two places at once: the new ride has to fit the driver's whole timeline (rides they
            // drive and journeys they have asked for), with room before and after (ScheduleFeasibility.kt).
            fun conflictAt(departure: Long) = scheduleConflictFor(driverId, drivingJourney(departure, plannedSeconds, route), travelTimes)
            conflictAt(body.departureTime)?.let { throw scheduleInfeasibleError(it) }
            insertRide(rideId, driverId, vehicleId, body, route, plannedSeconds, groupId, now)
            insertStops(rideId, body.stops)

            // "Repeat this route every Mon/Wed" - generate concrete sibling rides for the next
            // repeatWeeks weeks. No scheduler in this trial, so this is a bounded, one-time
            // generation rather than an ongoing rule (see docs/RTM.md).
            if (groupId != null) {
                val zone = SchedulingConfig.SERVICE_ZONE
                val startInstant = Instant.ofEpochMilli(body.departureTime).atZone(zone)
                val startDate = startInstant.toLocalDate()
                val timeOfDay = startInstant.toLocalTime()
                val weeks = body.repeatWeeks.coerceIn(1, 12)
                val endDate = startDate.plusWeeks(weeks.toLong())

                var date = startDate.plusDays(1)
                while (date.isBefore(endDate)) {
                    if (date.dayOfWeek.value in body.repeatDaysOfWeek) {
                        val siblingDeparture = date.atTime(timeOfDay).atZone(zone).toInstant().toEpochMilli()
                        // Days you already have a trip are skipped rather than failing the lot.
                        if (siblingDeparture > System.currentTimeMillis() && conflictAt(siblingDeparture) == null) {
                            val siblingId = "ride-${UUID.randomUUID()}"
                            insertRide(siblingId, driverId, vehicleId, body, route, plannedSeconds, groupId, now, departureOverride = siblingDeparture)
                            insertStops(siblingId, body.stops)
                        }
                    }
                    date = date.plusDays(1)
                }
            }
        }
        val dto = readTransaction {
            enrichRideDtos(Rides.selectAll().where { Rides.rideId eq rideId }.toList()).first()
        }
        call.respond(dto)
    }

    get("/rides/{id}") {
        call.requireUserId()
        val rideId = call.parameters["id"] ?: throw NotFoundException()
        val dto = readTransaction {
            enrichRideDtos(Rides.selectAll().where { Rides.rideId eq rideId }.toList()).firstOrNull()
        } ?: throw NotFoundException("Ride not found")
        call.respond(dto)
    }

    put("/rides/{id}") {
        val userId = call.requireUserId()
        val rideId = call.parameters["id"] ?: throw NotFoundException()
        val body = call.receive<UpdateRideRequest>()

        // Cancelling or completing changes the requests of everyone on the ride, so the driver, those
        // passengers (users, ascending) and then the ride are locked before anything is read for a
        // decision (Concurrency.kt). Who the passengers are is found first, then confirmed under the lock.
        schedulingTransactionFor(
            usersOf = { people: Set<String> -> people },
            ridesOf = { listOf(rideId) },
            discover = { rideParticipantIds(rideId) }
        ) {
            val ride = Rides.selectAll().where { Rides.rideId eq rideId }.firstOrNull()
                ?: throw NotFoundException("Ride not found")
            if (ride[Rides.driverId] != userId) throw ForbiddenException("You don't own this ride")

            val currentStatus = ride[Rides.status]
            val targetStatus = body.status ?: currentStatus
            if (targetStatus !in setOf(RideStatus.OPEN, RideStatus.CONFIRMED, RideStatus.CANCELLED, RideStatus.COMPLETED)) {
                throw ValidationException("Unknown ride status")
            }
            if (currentStatus in setOf(RideStatus.CANCELLED, RideStatus.COMPLETED, RideStatus.EXPIRED)) {
                // Preserve HTTP 200 for terminal retries, but ignore all supplied edits.
                if (body.status == currentStatus) return@schedulingTransactionFor
                throw ConflictException("A $currentStatus ride cannot be changed")
            }
            val allowed = when (currentStatus) {
                RideStatus.OPEN -> setOf(RideStatus.OPEN, RideStatus.CONFIRMED, RideStatus.CANCELLED, RideStatus.COMPLETED)
                RideStatus.CONFIRMED -> setOf(RideStatus.CONFIRMED, RideStatus.CANCELLED, RideStatus.COMPLETED)
                else -> emptySet()
            }
            if (targetStatus !in allowed) {
                throw ConflictException("Can't move a ride from $currentStatus to $targetStatus")
            }

            // An EXPLICIT completion. The terminal-retry branch above already returned for a ride that is COMPLETED, so
            // this runs once per ride and Rides.completedAt - the server's clock, never a client's value - is written
            // exactly once and in this same transaction as the status (and so is never written for a completion that
            // rolls back). A ride cannot be completed before it has started, or a client could invent a history that
            // never happened; the check uses the later of the stored departure and any departure sent in this same call,
            // so the call cannot move the ride into the past in order to finish it.
            val completing = body.status == RideStatus.COMPLETED
            val completedNow = ServerClock.now()
            if (completing && completedNow < maxOf(ride[Rides.departureTime], body.departureTime ?: Long.MIN_VALUE)) {
                throw ConflictException(MESSAGE_RIDE_NOT_STARTED, CODE_RIDE_NOT_STARTED)
            }
            // Everyone who carried a journey on this ride: the ACCEPTED seats (completed below) and - for a row from before the
            // ride owned completion - a seat already marked COMPLETED early. Both have now ridden to the end, so both are
            // revalidated. Cancelled, rejected and expired seats never took the ride and are not.
            val acceptedPassengerIds = if (!completing) emptyList() else RideRequests.selectAll()
                .where { (RideRequests.rideId eq rideId) and (RideRequests.status inList listOf(RideRequestStatus.ACCEPTED, RideRequestStatus.COMPLETED)) }
                .map { it[RideRequests.passengerId] }

            Rides.update({ Rides.rideId eq rideId }) { stmt ->
                body.departureTime?.let { newDeparture ->
                    stmt[departureTime] = newDeparture
                    // The planned arrival moves with the departure (same planned duration).
                    ride[Rides.plannedDurationSeconds]?.let { stmt[plannedArrivalTime] = newDeparture + it * 1000 }
                }
                body.seatsTotal?.let { newTotal ->
                    requireDriverVehicle(userId, newTotal)
                    if (newTotal < ride[Rides.seatsTotal] - ride[Rides.seatsAvailable]) {
                        throw ValidationException("Cannot remove seats already booked by passengers")
                    }
                    val delta = newTotal - ride[Rides.seatsTotal]
                    stmt[seatsTotal] = newTotal
                    stmt[seatsAvailable] = (ride[Rides.seatsAvailable] + delta).coerceAtLeast(0)
                }
                body.notes?.let { stmt[notes] = it }
                body.status?.let { stmt[status] = it }
                if (completing) stmt[completedAt] = completedNow
                // "I'm coming" (item: driver home / ride detail) - just a status flag in this
                // trial, no live position behind it. See Tables.kt's comment on this column.
                body.driverEnRoute?.let { stmt[driverEnRoute] = it }
            }
            if (body.status == RideStatus.CANCELLED) {
                // Cancel active bookings atomically with this concrete ride. Its remaining
                // capacity is no longer bookable, so do not restore individual request seats.
                val cancelledAt = System.currentTimeMillis()
                RideRequests.update({
                    (RideRequests.rideId eq rideId) and
                        (RideRequests.status inList listOf(RideRequestStatus.PENDING, RideRequestStatus.ACCEPTED))
                }) {
                    it[status] = RideRequestStatus.CANCELLED
                    it[respondedAt] = cancelledAt
                }
            } else if (body.status == RideStatus.COMPLETED) {
                // Accepted passengers finish their trip; outstanding asks can no longer be
                // fulfilled. Preserve terminal request history and the ride's seat counts.
                RideRequests.update({
                    (RideRequests.rideId eq rideId) and (RideRequests.status eq RideRequestStatus.ACCEPTED)
                }) {
                    it[status] = RideRequestStatus.COMPLETED
                    it[respondedAt] = completedNow
                }
                RideRequests.update({
                    (RideRequests.rideId eq rideId) and (RideRequests.status eq RideRequestStatus.PENDING)
                }) {
                    it[status] = RideRequestStatus.CANCELLED
                    it[respondedAt] = completedNow
                }
                // The ride really ended at completedNow: every person on it is now somewhere, as of that moment. Their
                // other journeys are re-judged against that (CompletionRevalidation.kt), still inside this transaction.
                revalidateSchedulesAfterCompletion(rideId, acceptedPassengerIds + userId, travelTimes, completedNow)
            }
        }
        val dto = readTransaction {
            enrichRideDtos(Rides.selectAll().where { Rides.rideId eq rideId }.toList()).first()
        }
        call.respond(dto)
    }

    // A ride can only be hard-deleted while it's still OPEN with nobody accepted onto it -
    // otherwise a driver could silently wipe out a passenger's confirmed trip. Use PUT with
    // status=CANCELLED instead once passengers are attached.
    delete("/rides/{id}") {
        val userId = call.requireUserId()
        val rideId = call.parameters["id"] ?: throw NotFoundException()

        // Deleting also removes the ride's waiting requests, so lock the same people as cancelling does.
        schedulingTransactionFor(
            usersOf = { people: Set<String> -> people },
            ridesOf = { listOf(rideId) },
            discover = { rideParticipantIds(rideId) }
        ) {
            val ride = Rides.selectAll().where { Rides.rideId eq rideId }.firstOrNull()
                ?: throw NotFoundException("Ride not found")
            if (ride[Rides.driverId] != userId) throw ForbiddenException("You don't own this ride")

            val hasAcceptedRequests = RideRequests.selectAll()
                .where { (RideRequests.rideId eq rideId) and (RideRequests.status eq RideRequestStatus.ACCEPTED) }
                .count() > 0
            if (hasAcceptedRequests) {
                throw ConflictException("This ride has accepted passengers - cancel it instead of deleting")
            }

            RideStops.deleteWhere { RideStops.rideId eq rideId }
            RideRequests.deleteWhere { RideRequests.rideId eq rideId }
            Rides.deleteWhere { Rides.rideId eq rideId }
        }
        call.respond(mapOf("deleted" to true))
    }
}

// The driver and everyone with a request on this ride: the people whose schedules a change to the
// ride (cancel, complete, delete) can touch. Read-only.
private fun Transaction.rideParticipantIds(rideId: String): Set<String> {
    val ride = Rides.selectAll().where { Rides.rideId eq rideId }.firstOrNull()
        ?: throw NotFoundException("Ride not found")
    return setOf(ride[Rides.driverId]) +
        RideRequests.selectAll().where { RideRequests.rideId eq rideId }.map { it[RideRequests.passengerId] }
}

// Ride IDs the caller already has a live (non-rejected/cancelled) request against - used to keep
// a ride you've already joined (or are waiting on) out of your own browse/search results, while
// it correctly keeps showing for everyone else. Must be called from inside an already-open
// transaction.
internal fun callerActiveRequestedRideIds(callerId: String): Set<String> =
    RideRequests.selectAll()
        .where {
            (RideRequests.passengerId eq callerId) and
                (RideRequests.status inList listOf(RideRequestStatus.PENDING, RideRequestStatus.ACCEPTED))
        }
        .mapNotNull { it[RideRequests.rideId] }
        .toSet()

// Batch-loads driver/vehicle/stops/driver-rating for a set of ride rows so listing endpoints
// don't do an N+1 query per ride. Must be called from inside an already-open transaction.
internal fun enrichRideDtos(rows: List<ResultRow>): List<RideDto> {
    if (rows.isEmpty()) return emptyList()
    val driverIds = rows.map { it[Rides.driverId] }.toSet()
    val vehicleIds = rows.mapNotNull { it[Rides.vehicleId] }.toSet()
    val rideIds = rows.map { it[Rides.rideId] }.toSet()

    val ratingsByDriver = Ratings.selectAll().where { Ratings.ratedUserId inList driverIds }
        .groupBy { it[Ratings.ratedUserId] }
        .mapValues { (_, ratingRows) -> ratingRows.map { it[Ratings.stars] }.average() to ratingRows.size }

    val driversById = Users.selectAll().where { Users.userId inList driverIds }
        .associate { row ->
            val (average, count) = ratingsByDriver[row[Users.userId]] ?: (null to 0)
            row[Users.userId] to row.toUserSummaryDto(averageRating = average, ratingCount = count)
        }
    val vehiclesById = if (vehicleIds.isEmpty()) emptyMap() else {
        Vehicles.selectAll().where { Vehicles.vehicleId inList vehicleIds }
            .associate { it[Vehicles.vehicleId] to it.toVehicleDto() }
    }
    val stopsByRideId = RideStops.selectAll().where { RideStops.rideId inList rideIds }
        .map { it[RideStops.rideId] to it.toRideStopDto() }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, stops) -> stops.sortedBy { it.sequence } }

    return rows.map { row ->
        row.toRideDto(
            driver = driversById[row[Rides.driverId]],
            vehicle = row[Rides.vehicleId]?.let { vehiclesById[it] },
            stops = stopsByRideId[row[Rides.rideId]].orEmpty()
        )
    }
}

private fun insertRide(
    rideId: String,
    driverId: String,
    vehicleId: String,
    body: CreateRideRequest,
    route: ApprovedRoute,
    plannedDurationSeconds: Long,
    groupId: String?,
    createdAt: Long,
    departureOverride: Long? = null
) {
    val departure = departureOverride ?: body.departureTime
    Rides.insert {
        it[Rides.rideId] = rideId
        it[Rides.driverId] = driverId
        it[Rides.vehicleId] = vehicleId
        // The approved points' own coordinates, exactly as an open request stores them.
        it[originLat] = route.origin.lat
        it[originLng] = route.origin.lng
        it[destinationLat] = route.destination.lat
        it[destinationLng] = route.destination.lng
        it[distanceMeters] = body.distanceMeters
        // DISPLAY duration, from the driver's app (MapKit). Not used for scheduling.
        it[estimatedDurationSeconds] = body.estimatedDurationSeconds
        // BACKEND planning interval - what scheduling uses.
        it[originPointId] = route.origin.id
        it[destinationPointId] = route.destination.id
        it[Rides.plannedDurationSeconds] = plannedDurationSeconds
        it[plannedArrivalTime] = departure + plannedDurationSeconds * 1000
        it[departureTime] = departure
        it[seatsTotal] = body.seatsTotal
        it[seatsAvailable] = body.seatsTotal
        it[notes] = body.notes
        it[status] = RideStatus.OPEN
        it[recurringGroupId] = groupId
        it[Rides.createdAt] = createdAt
    }
}

private fun insertStops(rideId: String, stops: List<CreateStopInput>) {
    stops.forEachIndexed { index, stop ->
        RideStops.insert {
            it[stopId] = "stop-${UUID.randomUUID()}"
            it[RideStops.rideId] = rideId
            it[sequence] = index
            it[lat] = stop.lat
            it[lng] = stop.lng
            it[label] = stop.label
        }
    }
}

internal fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val earthRadius = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
    val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    return earthRadius * c
}

private fun isSameCalendarDay(epochMillis: Long, isoDate: String): Boolean {
    return try {
        val target = java.time.LocalDate.parse(isoDate)
        val actual = java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        target == actual
    } catch (e: Exception) {
        true
    }
}
