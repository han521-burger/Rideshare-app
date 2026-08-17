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
import com.example.ds04_monash_rideshare_application.backend.ValidationException
import com.example.ds04_monash_rideshare_application.backend.requireUserId
import com.example.ds04_monash_rideshare_application.backend.toRideRequestDto
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
    // Passenger requests a seat.
    post("/rides/{rideId}/requests") {
        val passengerId = call.requireUserId()
        val rideId = call.parameters["rideId"] ?: throw NotFoundException()
        val body = call.receive<CreateRideRequestBody>()
        if (body.seatsRequested <= 0) throw ValidationException("seatsRequested must be positive")

        val requestId = "req-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        transaction {
            val ride = Rides.selectAll().where { Rides.rideId eq rideId }.firstOrNull()
                ?: throw NotFoundException("Ride not found")
            if (ride[Rides.driverId] == passengerId) {
                throw ValidationException("You can't request a seat on your own ride")
            }
            if (ride[Rides.status] != RideStatus.OPEN) throw ConflictException("This ride isn't open for requests")
            if (ride[Rides.seatsAvailable] < body.seatsRequested) {
                throw ConflictException("Not enough seats available")
            }
            val alreadyRequested = RideRequests.selectAll()
                .where {
                    (RideRequests.rideId eq rideId) and
                        (RideRequests.passengerId eq passengerId) and
                        (RideRequests.status eq RideRequestStatus.PENDING)
                }.count() > 0
            if (alreadyRequested) throw ConflictException("You already have a pending request for this ride")

            RideRequests.insert {
                it[RideRequests.requestId] = requestId
                it[RideRequests.rideId] = rideId
                it[RideRequests.passengerId] = passengerId
                it[seatsRequested] = body.seatsRequested
                it[status] = RideRequestStatus.PENDING
                it[createdAt] = now
            }
        }
        val dto = transaction {
            RideRequests.selectAll().where { RideRequests.requestId eq requestId }.map { it.toRideRequestDto() }.first()
        }
        call.respond(dto)
    }

    // Driver views all requests across their rides, or just one ride's requests via ?rideId=.
    get("/requests") {
        val driverId = call.requireUserId()
        val rideIdFilter = call.request.queryParameters["rideId"]
        val requests = transaction {
            val ownRides = Rides.selectAll().where { Rides.driverId eq driverId }.associateBy { it[Rides.rideId] }
            RideRequests.selectAll()
                .mapNotNull { row ->
                    val rideId = row[RideRequests.rideId]
                    val ride = ownRides[rideId] ?: return@mapNotNull null
                    if (rideIdFilter != null && rideId != rideIdFilter) return@mapNotNull null
                    row.toRideRequestDto().copy(ride = ride.toRideDto())
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
                Rides.selectAll().where { Rides.rideId inList rideIds }.associateBy { it[Rides.rideId] }
            }
            rows.map { row ->
                row.toRideRequestDto().copy(ride = ridesById[row[RideRequests.rideId]]?.toRideDto())
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
