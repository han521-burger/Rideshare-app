package com.example.ds04_monash_rideshare_application.backend.routes

import com.example.ds04_monash_rideshare_application.backend.ConflictException
import com.example.ds04_monash_rideshare_application.backend.CreateRideRequestBody
import com.example.ds04_monash_rideshare_application.backend.ForbiddenException
import com.example.ds04_monash_rideshare_application.backend.NotFoundException
import com.example.ds04_monash_rideshare_application.backend.RideRequestStatus
import com.example.ds04_monash_rideshare_application.backend.RideRequests
import com.example.ds04_monash_rideshare_application.backend.RideStatus
import com.example.ds04_monash_rideshare_application.backend.Rides
import com.example.ds04_monash_rideshare_application.backend.UpdateRideRequestStatusBody
import com.example.ds04_monash_rideshare_application.backend.Users
import com.example.ds04_monash_rideshare_application.backend.ValidationException
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
import java.util.UUID

fun Route.rideRequestRoutes() {
    // Passenger requests a seat. If applyToRecurringGroup is set and the ride belongs to a
    // "repeat this route" group (see RideRoutes.kt's POST /rides), also requests a seat on every
    // other OPEN sibling in that group in the same call.
    post("/rides/{rideId}/requests") {
        val passengerId = call.requireUserId()
        val rideId = call.parameters["rideId"] ?: throw NotFoundException()
        val body = call.receive<CreateRideRequestBody>()
        if (body.seatsRequested <= 0) throw ValidationException("seatsRequested must be positive")

        val createdIds = mutableListOf<String>()
        val now = System.currentTimeMillis()
        transaction {
            val ride = Rides.selectAll().where { Rides.rideId eq rideId }.firstOrNull()
                ?: throw NotFoundException("Ride not found")

            val targetRides = if (body.applyToRecurringGroup && ride[Rides.recurringGroupId] != null) {
                Rides.selectAll().where {
                    (Rides.recurringGroupId eq ride[Rides.recurringGroupId]) and (Rides.status eq RideStatus.OPEN)
                }.toList()
            } else {
                listOf(ride)
            }

            for (target in targetRides) {
                val targetRideId = target[Rides.rideId]
                if (target[Rides.driverId] == passengerId) continue // skip your own ride silently
                if (target[Rides.status] != RideStatus.OPEN) continue
                if (target[Rides.seatsAvailable] < body.seatsRequested) continue
                val alreadyRequested = RideRequests.selectAll()
                    .where {
                        (RideRequests.rideId eq targetRideId) and
                            (RideRequests.passengerId eq passengerId) and
                            (RideRequests.status eq RideRequestStatus.PENDING)
                    }.count() > 0
                if (alreadyRequested) continue

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
                throw ConflictException("Couldn't request a seat - this ride isn't open, is your own, or you already have a pending request")
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

    // Driver views all requests across their rides, or just one ride's requests via ?rideId=.
    get("/requests") {
        val driverId = call.requireUserId()
        val rideIdFilter = call.request.queryParameters["rideId"]
        val requests = transaction {
            val ownRides = Rides.selectAll().where { Rides.driverId eq driverId }.toList()
            val ownRideDtos = enrichRideDtos(ownRides).associateBy { it.rideId }
            val passengerIds = mutableSetOf<String>()
            val rows = RideRequests.selectAll().toList().filter { row ->
                val rideId = row[RideRequests.rideId]
                ownRideDtos.containsKey(rideId) && (rideIdFilter == null || rideId == rideIdFilter)
            }
            rows.forEach { passengerIds += it[RideRequests.passengerId] }
            val passengersById = if (passengerIds.isEmpty()) emptyMap() else {
                Users.selectAll().where { Users.userId inList passengerIds }
                    .associate { it[Users.userId] to it.toUserSummaryDto() }
            }
            rows.map { row ->
                row.toRideRequestDto().copy(
                    ride = ownRideDtos[row[RideRequests.rideId]],
                    passenger = passengersById[row[RideRequests.passengerId]]
                )
            }
        }
        call.respond(requests)
    }

    // Passenger views their own requests.
    get("/requests/mine") {
        val passengerId = call.requireUserId()
        val requests = transaction {
            val rows = RideRequests.selectAll().where { RideRequests.passengerId eq passengerId }.toList()
            val rideIds = rows.map { it[RideRequests.rideId] }.toSet()
            val ridesById = if (rideIds.isEmpty()) emptyMap() else {
                enrichRideDtos(Rides.selectAll().where { Rides.rideId inList rideIds }.toList()).associateBy { it.rideId }
            }
            rows.map { row ->
                row.toRideRequestDto().copy(ride = ridesById[row[RideRequests.rideId]])
            }
        }
        call.respond(requests)
    }

    // Driver accepts/rejects, either party cancels. Acceptance atomically decrements
    // seatsAvailable in the same transaction as the status change, and re-checks availability
    // right before doing so, to prevent two overlapping accepts from overbooking a ride.
    put("/requests/{requestId}") {
        val userId = call.requireUserId()
        val requestId = call.parameters["requestId"] ?: throw NotFoundException()
        val body = call.receive<UpdateRideRequestStatusBody>()
        val newStatus = body.status.uppercase()

        transaction {
            val request = RideRequests.selectAll().where { RideRequests.requestId eq requestId }.firstOrNull()
                ?: throw NotFoundException("Request not found")
            val ride = Rides.selectAll().where { Rides.rideId eq request[RideRequests.rideId] }.first()

            val isDriver = ride[Rides.driverId] == userId
            val isPassenger = request[RideRequests.passengerId] == userId
            if (!isDriver && !isPassenger) throw ForbiddenException("Not your request")

            val currentStatus = request[RideRequests.status]
            val allowed = RideRequestStatus.transitions[currentStatus].orEmpty()
            if (newStatus !in allowed) {
                throw ConflictException("Can't move a request from $currentStatus to $newStatus")
            }
            if (newStatus == RideRequestStatus.ACCEPTED && !isDriver) {
                throw ForbiddenException("Only the driver can accept a request")
            }
            if (newStatus == RideRequestStatus.REJECTED && !isDriver) {
                throw ForbiddenException("Only the driver can reject a request")
            }

            if (newStatus == RideRequestStatus.ACCEPTED) {
                // Re-read seatsAvailable inside this same transaction right before writing -
                // SQLite serialises writers at the connection/file level, so this check-then-write
                // is safe here. On a multi-writer DB (e.g. Postgres) this select would need
                // SELECT ... FOR UPDATE to hold the same guarantee.
                val freshRide = Rides.selectAll().where { Rides.rideId eq ride[Rides.rideId] }.first()
                if (freshRide[Rides.seatsAvailable] < request[RideRequests.seatsRequested]) {
                    throw ConflictException("Not enough seats left - someone else was just accepted")
                }
                Rides.update({ Rides.rideId eq ride[Rides.rideId] }) {
                    it[seatsAvailable] = freshRide[Rides.seatsAvailable] - request[RideRequests.seatsRequested]
                    it[status] = RideStatus.CONFIRMED
                }
            }

            RideRequests.update({ RideRequests.requestId eq requestId }) {
                it[status] = newStatus
                it[respondedAt] = System.currentTimeMillis()
            }
        }

        val dto = transaction {
            RideRequests.selectAll().where { RideRequests.requestId eq requestId }.map { it.toRideRequestDto() }.first()
        }
        call.respond(dto)
    }
}
