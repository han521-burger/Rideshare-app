package com.example.ds04_monash_rideshare_application.backend.routes

import com.example.ds04_monash_rideshare_application.backend.ConflictException
import com.example.ds04_monash_rideshare_application.backend.CreateRideRequestBody
import com.example.ds04_monash_rideshare_application.backend.CreateStandaloneRequestBody
import com.example.ds04_monash_rideshare_application.backend.ForbiddenException
import com.example.ds04_monash_rideshare_application.backend.NotFoundException
import com.example.ds04_monash_rideshare_application.backend.RideRequestStatus
import com.example.ds04_monash_rideshare_application.backend.requireMonashEndpoint
import com.example.ds04_monash_rideshare_application.backend.RideRequests
import com.example.ds04_monash_rideshare_application.backend.RideStatus
import com.example.ds04_monash_rideshare_application.backend.Rides
import com.example.ds04_monash_rideshare_application.backend.UpdateRideRequestStatusBody
import com.example.ds04_monash_rideshare_application.backend.Users
import com.example.ds04_monash_rideshare_application.backend.Vehicles
import com.example.ds04_monash_rideshare_application.backend.requireDriverVehicle
import com.example.ds04_monash_rideshare_application.backend.requireBookableDeparture
import com.example.ds04_monash_rideshare_application.backend.ValidationException
import com.example.ds04_monash_rideshare_application.backend.matching.MatchingEngine
import com.example.ds04_monash_rideshare_application.backend.matching.RideCandidate
import com.example.ds04_monash_rideshare_application.backend.matching.SearchCriteria
import com.example.ds04_monash_rideshare_application.backend.requireUserId
import com.example.ds04_monash_rideshare_application.backend.toRideRequestDto
import com.example.ds04_monash_rideshare_application.backend.toUserSummaryDto
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

fun Route.rideRequestRoutes() {
    // A passenger asking for a seat on a specific posted ride always creates a PENDING request.
    // Seats are deliberately not reserved here: the owning driver makes the final decision and
    // PUT /requests/{id} performs the atomic capacity check/decrement at acceptance time. If
    // applyToRecurringGroup is set, one pending request is created for each eligible sibling.
    post("/rides/{rideId}/requests") {
        val passengerId = call.requireUserId()
        val rideId = call.parameters["rideId"] ?: throw NotFoundException()
        val body = call.receive<CreateRideRequestBody>()
        if (body.seatsRequested <= 0) throw ValidationException("seatsRequested must be positive")

        val createdIds = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val joinableStatuses = setOf(RideStatus.OPEN, RideStatus.CONFIRMED)
        transaction {
            val ride = Rides.selectAll().where { Rides.rideId eq rideId }.firstOrNull()
                ?: throw NotFoundException("Ride not found")

            val targetRides = if (body.applyToRecurringGroup && ride[Rides.recurringGroupId] != null) {
                Rides.selectAll().where {
                    (Rides.recurringGroupId eq ride[Rides.recurringGroupId]) and
                        (Rides.status inList joinableStatuses)
                }.toList()
            } else {
                listOf(ride)
            }

            for (target in targetRides) {
                val targetRideId = target[Rides.rideId]
                if (target[Rides.driverId] == passengerId) continue // skip your own ride silently
                if (target[Rides.status] !in joinableStatuses) continue

                val alreadyRequested = RideRequests.selectAll()
                    .where {
                        (RideRequests.rideId eq targetRideId) and
                            (RideRequests.passengerId eq passengerId) and
                            (RideRequests.status inList listOf(RideRequestStatus.PENDING, RideRequestStatus.ACCEPTED))
                    }.count() > 0
                if (alreadyRequested) continue

                // This is an early friendly check only. Other pending requests do not reserve a
                // seat, so the authoritative capacity check still happens when the driver accepts.
                val freshRide = Rides.selectAll().where { Rides.rideId eq targetRideId }.first()
                if (freshRide[Rides.seatsAvailable] < body.seatsRequested) continue

                val requestId = "req-${UUID.randomUUID()}"
                RideRequests.insert {
                    it[RideRequests.requestId] = requestId
                    it[RideRequests.rideId] = targetRideId
                    it[RideRequests.passengerId] = passengerId
                    it[seatsRequested] = body.seatsRequested
                    it[status] = RideRequestStatus.PENDING
                    it[note] = body.note
                    it[createdAt] = now
                }
                createdIds += requestId
            }
            if (createdIds.isEmpty()) {
                throw ConflictException("Couldn't request this ride - it may be full, unavailable, already requested, or your own")
            }
        }
        val dtos = transaction {
            RideRequests.selectAll().where { RideRequests.requestId inList createdIds }.map { it.toRideRequestDto() }
        }
        // Always a list, even for the single-ride case (createdIds.size == 1 unless
        // applyToRecurringGroup fanned out to siblings) - a response whose static type varies at
        // runtime (single DTO vs list) isn't serializable, so this can't branch on body.applyToRecurringGroup.
        call.respond(dtos)
    }

    // Passenger posts a standalone request - a route + time with no driver picked yet. Sits as
    // PENDING with rideId == null until some driver accepts it (see PUT /requests/{id} below),
    // attaching one of their own rides at that point. If repeatDaysOfWeek is set, one additional
    // standalone PENDING request is generated per matching day for the next repeatWeeks weeks
    // (mirrors the driver "repeat this route" fan-out in RideRoutes.kt's POST /rides) - each day
    // is then its own request with its own status, so a driver can accept some days and leave
    // others for someone else, instead of one accept locking every day at once. ASAP has no
    // scheduling concept, so repeat days never apply when isAsap is true.
    post("/requests") {
        val passengerId = call.requireUserId()
        val body = call.receive<CreateStandaloneRequestBody>()
        if (body.seatsRequested <= 0) throw ValidationException("seatsRequested must be positive")
        if (!body.isAsap && body.desiredDepartureTime == null) {
            throw ValidationException("desiredDepartureTime is required unless isAsap is true")
        }
        if (!body.isAsap) body.desiredDepartureTime?.let(::requireBookableDeparture)
        requireMonashEndpoint(body.originLat, body.originLng, body.destinationLat, body.destinationLng)

        val requestId = "req-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        transaction {
            insertStandaloneRequest(requestId, passengerId, body, body.desiredDepartureTime, now)

            val startDeparture = body.desiredDepartureTime
            if (!body.isAsap && startDeparture != null && body.repeatDaysOfWeek.isNotEmpty()) {
                val zone = ZoneId.systemDefault()
                val startInstant = Instant.ofEpochMilli(startDeparture).atZone(zone)
                val startDate = startInstant.toLocalDate()
                val timeOfDay = startInstant.toLocalTime()
                val weeks = (body.repeatWeeks ?: 4).coerceIn(1, 12)
                val endDate = startDate.plusWeeks(weeks.toLong())

                var date = startDate.plusDays(1)
                while (date.isBefore(endDate)) {
                    if (date.dayOfWeek.value in body.repeatDaysOfWeek) {
                        val siblingDeparture = date.atTime(timeOfDay).atZone(zone).toInstant().toEpochMilli()
                        if (siblingDeparture > System.currentTimeMillis()) {
                            insertStandaloneRequest("req-${UUID.randomUUID()}", passengerId, body, siblingDeparture, now)
                        }
                    }
                    date = date.plusDays(1)
                }
            }
        }
        val dto = transaction {
            RideRequests.selectAll().where { RideRequests.requestId eq requestId }.map { it.toRideRequestDto() }.first()
        }
        call.respond(dto)
    }

    // Driver views incoming requests: ones already aimed at their own rides (any status, so they
    // can review history too), plus every campus-wide standalone request that's still PENDING -
    // each scored (MatchingEngine) against the driver's own OPEN rides so they can tell at a
    // glance whether it's worth a look. ?rideId= narrows to just one of the driver's rides and
    // drops the standalone list (it's only meaningful campus-wide).
    get("/requests") {
        val driverId = call.requireUserId()
        val rideIdFilter = call.request.queryParameters["rideId"]
        val requests = transaction {
            val ownRideRows = Rides.selectAll().where { Rides.driverId eq driverId }.toList()
            val ownRideDtos = enrichRideDtos(ownRideRows).associateBy { it.rideId }
            val ownOpenCandidates = ownRideRows
                .filter { it[Rides.status] == RideStatus.OPEN }
                .map { row ->
                    RideCandidate(
                        rideId = row[Rides.rideId],
                        originLat = row[Rides.originLat],
                        originLng = row[Rides.originLng],
                        destinationLat = row[Rides.destinationLat],
                        destinationLng = row[Rides.destinationLng],
                        departureTime = row[Rides.departureTime],
                        seatsAvailable = row[Rides.seatsAvailable]
                    )
                }

            val attachedRows = RideRequests.selectAll().toList().filter { row ->
                val rid = row[RideRequests.rideId]
                rid != null && ownRideDtos.containsKey(rid) && (rideIdFilter == null || rid == rideIdFilter)
            }
            val standaloneRows = if (rideIdFilter != null) emptyList() else {
                RideRequests.selectAll()
                    .where { RideRequests.status eq RideRequestStatus.PENDING }
                    // Your own standalone ask isn't something for you to accept as a driver - it
                    // already shows under "My requests" on your own passenger side. Without this,
                    // a request you post to yourself (any account can be both) shows up twice.
                    .filter { it[RideRequests.rideId] == null && it[RideRequests.passengerId] != driverId }
            }

            val allRows = attachedRows + standaloneRows
            val passengerIds = allRows.map { it[RideRequests.passengerId] }.toSet()
            val passengersById = if (passengerIds.isEmpty()) emptyMap() else {
                Users.selectAll().where { Users.userId inList passengerIds }
                    .associate { it[Users.userId] to it.toUserSummaryDto() }
            }

            allRows.map { row ->
                val rid = row[RideRequests.rideId]
                val dto = row.toRideRequestDto().copy(
                    ride = rid?.let { ownRideDtos[it] },
                    passenger = passengersById[row[RideRequests.passengerId]]
                )
                val requestOriginLat = row[RideRequests.originLat]
                val requestOriginLng = row[RideRequests.originLng]
                val requestDestLat = row[RideRequests.destinationLat]
                val requestDestLng = row[RideRequests.destinationLng]
                if (rid == null && requestOriginLat != null && requestOriginLng != null &&
                    requestDestLat != null && requestDestLng != null
                ) {
                    val criteria = SearchCriteria(
                        originLat = requestOriginLat,
                        originLng = requestOriginLng,
                        destinationLat = requestDestLat,
                        destinationLng = requestDestLng,
                        desiredDepartureTime = row[RideRequests.desiredDepartureTime] ?: System.currentTimeMillis(),
                        seatsNeeded = row[RideRequests.seatsRequested]
                    )
                    val best = MatchingEngine.rank(criteria, ownOpenCandidates).firstOrNull()
                    dto.copy(matchScore = best?.score)
                } else {
                    dto
                }
            }
        }
        call.respond(requests)
    }

    // Passenger views their own requests, standalone or attached.
    get("/requests/mine") {
        val passengerId = call.requireUserId()
        val requests = transaction {
            val rows = RideRequests.selectAll().where { RideRequests.passengerId eq passengerId }.toList()
            val rideIds = rows.mapNotNull { it[RideRequests.rideId] }.toSet()
            val ridesById = if (rideIds.isEmpty()) emptyMap() else {
                enrichRideDtos(Rides.selectAll().where { Rides.rideId inList rideIds }.toList()).associateBy { it.rideId }
            }
            rows.map { row ->
                row.toRideRequestDto().copy(ride = row[RideRequests.rideId]?.let { ridesById[it] })
            }
        }
        call.respond(requests)
    }

    // Drivers accept/reject requests attached to their own rides. A standalone request can be
    // accepted by attaching one of the driver's rides, or by creating a new ride from the
    // request's route/time. Passengers may cancel their own pending or accepted requests.
    put("/requests/{requestId}") {
        val userId = call.requireUserId()
        val requestId = call.parameters["requestId"] ?: throw NotFoundException()
        val body = call.receive<UpdateRideRequestStatusBody>()
        val newStatus = body.status.uppercase()

        transaction {
            val request = RideRequests.selectAll().where { RideRequests.requestId eq requestId }.firstOrNull()
                ?: throw NotFoundException("Request not found")

            val existingRideId = request[RideRequests.rideId]
            val existingRide = existingRideId?.let { rid -> Rides.selectAll().where { Rides.rideId eq rid }.firstOrNull() }
            val isPassenger = request[RideRequests.passengerId] == userId
            val isOwningDriver = existingRide != null && existingRide[Rides.driverId] == userId

            val currentStatus = request[RideRequests.status]
            val allowed = RideRequestStatus.transitions[currentStatus].orEmpty()
            if (newStatus !in allowed) {
                throw ConflictException("Can't move a request from $currentStatus to $newStatus")
            }

            when (newStatus) {
                RideRequestStatus.ACCEPTED -> {
                    val targetRideId = existingRideId ?: body.targetRideId ?: run {
                        // No existing ride to attach to and none picked - create one from the
                        // request's own route/time, so a driver can accept without having
                        // already posted a matching ride first.
                        val originLat = request[RideRequests.originLat]
                        val originLng = request[RideRequests.originLng]
                        val destinationLat = request[RideRequests.destinationLat]
                        val destinationLng = request[RideRequests.destinationLng]
                        if (originLat == null || originLng == null || destinationLat == null || destinationLng == null) {
                            throw ValidationException("targetRideId is required - this request has no route to create a ride from")
                        }
                        val seatsTotal = (body.newRideSeatsTotal ?: request[RideRequests.seatsRequested])
                            .coerceAtLeast(request[RideRequests.seatsRequested])
                        val vehicle = requireDriverVehicle(userId, seatsTotal)
                        val distanceMeters = haversine(originLat, originLng, destinationLat, destinationLng)
                        // Straight-line estimate at a typical urban driving speed (40 km/h) - a
                        // standalone request never had a real Yandex route computed for it (see
                        // POST /requests), same "figures are estimates" caveat as elsewhere.
                        val estimatedDurationSeconds = (distanceMeters / (40_000.0 / 3600.0)).toLong()
                        val newRideId = "ride-${UUID.randomUUID()}"
                        Rides.insert {
                            it[rideId] = newRideId
                            it[driverId] = userId
                            it[vehicleId] = vehicle[Vehicles.vehicleId]
                            it[Rides.originLat] = originLat
                            it[Rides.originLng] = originLng
                            it[Rides.destinationLat] = destinationLat
                            it[Rides.destinationLng] = destinationLng
                            it[Rides.distanceMeters] = distanceMeters
                            it[Rides.estimatedDurationSeconds] = estimatedDurationSeconds
                            it[departureTime] = request[RideRequests.desiredDepartureTime] ?: System.currentTimeMillis()
                            it[Rides.seatsTotal] = seatsTotal
                            it[seatsAvailable] = seatsTotal
                            it[notes] = null
                            it[status] = RideStatus.OPEN
                            it[recurringGroupId] = null
                            it[Rides.createdAt] = System.currentTimeMillis()
                        }
                        newRideId
                    }
                    val targetRide = Rides.selectAll().where { Rides.rideId eq targetRideId }.firstOrNull()
                        ?: throw NotFoundException("Target ride not found")
                    if (targetRide[Rides.driverId] != userId) throw ForbiddenException("You don't own that ride")
                    if (
                        existingRideId != null &&
                        body.targetRideId != null &&
                        existingRideId != body.targetRideId
                    ) {
                        throw ValidationException("This request is already tied to a different ride")
                    }
                    // CONFIRMED (not just OPEN) is fine here too - a ride with other confirmed
                    // passengers can still take more as long as it has room (see the "more seats
                    // left, still joinable" filter on POST /rides/{rideId}/requests and GET /rides).
                    if (targetRide[Rides.status] !in setOf(RideStatus.OPEN, RideStatus.CONFIRMED)) {
                        throw ConflictException("That ride isn't open")
                    }

                    // Re-read seatsAvailable inside this same transaction right before writing,
                    // holding a row lock (forUpdate) so two drivers racing to accept can't both
                    // pass this check for the same seat. SQLite has no row-level locking - this
                    // is a no-op there and the check-then-write is safe only because SQLite
                    // serialises writers at the connection/file level - but on a multi-writer DB
                    // (e.g. Postgres) forUpdate blocks the second transaction here until the
                    // first commits, so it re-reads the now-updated count instead of racing it.
                    val freshRide = Rides.selectAll().where { Rides.rideId eq targetRideId }.forUpdate().first()
                    if (freshRide[Rides.seatsAvailable] < request[RideRequests.seatsRequested]) {
                        throw ConflictException("Not enough seats left - someone else was just accepted")
                    }
                    Rides.update({ Rides.rideId eq targetRideId }) {
                        it[seatsAvailable] = freshRide[Rides.seatsAvailable] - request[RideRequests.seatsRequested]
                        it[status] = RideStatus.CONFIRMED
                    }
                    RideRequests.update({ RideRequests.requestId eq requestId }) {
                        it[rideId] = targetRideId
                        it[status] = newStatus
                        it[respondedAt] = System.currentTimeMillis()
                    }
                }
                RideRequestStatus.REJECTED -> {
                    if (!isOwningDriver) throw ForbiddenException("Only the ride's driver can reject a request")
                    RideRequests.update({ RideRequests.requestId eq requestId }) {
                        it[status] = newStatus
                        it[respondedAt] = System.currentTimeMillis()
                    }
                }
                RideRequestStatus.CANCELLED -> {
                    if (!isPassenger && !isOwningDriver) throw ForbiddenException("Not your request")
                    // An accepted seat is returned on cancellation so another request can be
                    // approved. Cancelling a still-pending request never changes capacity.
                    if (currentStatus == RideRequestStatus.ACCEPTED && existingRideId != null) {
                        val freshRide = Rides.selectAll().where { Rides.rideId eq existingRideId }.first()
                        Rides.update({ Rides.rideId eq existingRideId }) {
                            it[seatsAvailable] = freshRide[Rides.seatsAvailable] + request[RideRequests.seatsRequested]
                        }
                    }
                    RideRequests.update({ RideRequests.requestId eq requestId }) {
                        it[status] = newStatus
                        it[respondedAt] = System.currentTimeMillis()
                    }
                    // Count after cancelling this request, in the same transaction as seat
                    // restoration. Pending/history rows do not keep an empty ride confirmed.
                    if (currentStatus == RideRequestStatus.ACCEPTED && existingRideId != null &&
                        existingRide?.get(Rides.status) == RideStatus.CONFIRMED
                    ) {
                        val acceptedRemaining = RideRequests.selectAll().where {
                            (RideRequests.rideId eq existingRideId) and
                                (RideRequests.status eq RideRequestStatus.ACCEPTED)
                        }.count()
                        if (acceptedRemaining == 0L) {
                            Rides.update({ (Rides.rideId eq existingRideId) and (Rides.status eq RideStatus.CONFIRMED) }) {
                                it[status] = RideStatus.OPEN
                            }
                        }
                    }
                }
                RideRequestStatus.COMPLETED -> {
                    if (!isPassenger && !isOwningDriver) throw ForbiddenException("Not your request")
                    RideRequests.update({ RideRequests.requestId eq requestId }) {
                        it[status] = newStatus
                        it[respondedAt] = System.currentTimeMillis()
                    }
                }
                else -> throw ValidationException("Unsupported status transition")
            }
        }

        val dto = transaction {
            RideRequests.selectAll().where { RideRequests.requestId eq requestId }.map { it.toRideRequestDto() }.first()
        }
        call.respond(dto)
    }
}

private fun insertStandaloneRequest(
    requestId: String,
    passengerId: String,
    body: CreateStandaloneRequestBody,
    departureTime: Long?,
    createdAt: Long
) {
    RideRequests.insert {
        it[RideRequests.requestId] = requestId
        it[rideId] = null
        it[RideRequests.passengerId] = passengerId
        it[seatsRequested] = body.seatsRequested
        it[status] = RideRequestStatus.PENDING
        it[note] = body.note
        it[originLat] = body.originLat
        it[originLng] = body.originLng
        it[destinationLat] = body.destinationLat
        it[destinationLng] = body.destinationLng
        it[isAsap] = body.isAsap
        it[desiredDepartureTime] = departureTime
        it[RideRequests.createdAt] = createdAt
    }
}
