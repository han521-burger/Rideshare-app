package com.example.ds04_monash_rideshare_application.backend.routes

import com.example.ds04_monash_rideshare_application.backend.ConflictException
import com.example.ds04_monash_rideshare_application.backend.CreateRideRequest
import com.example.ds04_monash_rideshare_application.backend.ForbiddenException
import com.example.ds04_monash_rideshare_application.backend.MatchSearchResponse
import com.example.ds04_monash_rideshare_application.backend.NotFoundException
import com.example.ds04_monash_rideshare_application.backend.RideRequestStatus
import com.example.ds04_monash_rideshare_application.backend.RideRequests
import com.example.ds04_monash_rideshare_application.backend.RideStatus
import com.example.ds04_monash_rideshare_application.backend.Rides
import com.example.ds04_monash_rideshare_application.backend.UpdateRideRequest
import com.example.ds04_monash_rideshare_application.backend.ValidationException
import com.example.ds04_monash_rideshare_application.backend.Vehicles
import com.example.ds04_monash_rideshare_application.backend.matching.MatchingEngine
import com.example.ds04_monash_rideshare_application.backend.matching.RideCandidate
import com.example.ds04_monash_rideshare_application.backend.matching.SearchCriteria
import com.example.ds04_monash_rideshare_application.backend.requireUserId
import com.example.ds04_monash_rideshare_application.backend.toRideDto
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

fun Route.rideRoutes() {
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

        val rides = transaction {
            Rides.selectAll()
                .where { Rides.status eq RideStatus.OPEN }
                .map { it.toRideDto() }
        }.filter { ride ->
            // A ride you drive isn't a ride you can join - exclude it from browse/search rather
            // than showing it and relying on the passenger-request endpoint's rejection.
            if (ride.driverId == callerId) return@filter false
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
        call.respond(rides)
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

        val (candidates, ridesByRideId) = transaction {
            val rows = Rides.selectAll().where { Rides.status eq RideStatus.OPEN }
                .filter { it[Rides.driverId] != callerId }
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
            candidates to rows.associate { it[Rides.rideId] to it.toRideDto() }
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
        if (body.departureTime <= System.currentTimeMillis()) {
            throw ValidationException("departureTime must be in the future")
        }
        if (body.vehicleId != null) {
            val ownsVehicle = transaction {
                Vehicles.selectAll()
                    .where { (Vehicles.vehicleId eq body.vehicleId) and (Vehicles.ownerId eq driverId) }
                    .count() > 0
            }
            if (!ownsVehicle) throw ForbiddenException("You don't own that vehicle")
        }

        val rideId = "ride-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        transaction {
            Rides.insert {
                it[Rides.rideId] = rideId
                it[Rides.driverId] = driverId
                it[Rides.vehicleId] = body.vehicleId
                it[originLat] = body.originLat
                it[originLng] = body.originLng
                it[destinationLat] = body.destinationLat
                it[destinationLng] = body.destinationLng
                it[distanceMeters] = body.distanceMeters
                it[estimatedDurationSeconds] = body.estimatedDurationSeconds
                it[departureTime] = body.departureTime
                it[seatsTotal] = body.seatsTotal
                it[seatsAvailable] = body.seatsTotal
                it[notes] = body.notes
                it[status] = RideStatus.OPEN
                it[createdAt] = now
            }
        }
        val dto = transaction { Rides.selectAll().where { Rides.rideId eq rideId }.map { it.toRideDto() }.first() }
        call.respond(dto)
    }

    get("/rides/{id}") {
        call.requireUserId()
        val rideId = call.parameters["id"] ?: throw NotFoundException()
        val dto = transaction {
            Rides.selectAll().where { Rides.rideId eq rideId }.map { it.toRideDto() }.firstOrNull()
        } ?: throw NotFoundException("Ride not found")
        call.respond(dto)
    }

    put("/rides/{id}") {
        val userId = call.requireUserId()
        val rideId = call.parameters["id"] ?: throw NotFoundException()
        val body = call.receive<UpdateRideRequest>()

        transaction {
            val ride = Rides.selectAll().where { Rides.rideId eq rideId }.firstOrNull()
                ?: throw NotFoundException("Ride not found")
            if (ride[Rides.driverId] != userId) throw ForbiddenException("You don't own this ride")

            Rides.update({ Rides.rideId eq rideId }) { stmt ->
                body.departureTime?.let { stmt[departureTime] = it }
                body.seatsTotal?.let { newTotal ->
                    val delta = newTotal - ride[Rides.seatsTotal]
                    stmt[seatsTotal] = newTotal
                    stmt[seatsAvailable] = (ride[Rides.seatsAvailable] + delta).coerceAtLeast(0)
                }
                body.notes?.let { stmt[notes] = it }
                body.status?.let { stmt[status] = it }
            }
        }
        val dto = transaction { Rides.selectAll().where { Rides.rideId eq rideId }.map { it.toRideDto() }.first() }
        call.respond(dto)
    }

    // A ride can only be hard-deleted while it's still OPEN with nobody accepted onto it -
    // otherwise a driver could silently wipe out a passenger's confirmed trip. Use PUT with
    // status=CANCELLED instead once passengers are attached.
    delete("/rides/{id}") {
        val userId = call.requireUserId()
        val rideId = call.parameters["id"] ?: throw NotFoundException()

        transaction {
            val ride = Rides.selectAll().where { Rides.rideId eq rideId }.firstOrNull()
                ?: throw NotFoundException("Ride not found")
            if (ride[Rides.driverId] != userId) throw ForbiddenException("You don't own this ride")

            val hasAcceptedRequests = RideRequests.selectAll()
                .where { (RideRequests.rideId eq rideId) and (RideRequests.status eq RideRequestStatus.ACCEPTED) }
                .count() > 0
            if (hasAcceptedRequests) {
                throw ConflictException("This ride has accepted passengers - cancel it instead of deleting")
            }

            RideRequests.deleteWhere { RideRequests.rideId eq rideId }
            Rides.deleteWhere { Rides.rideId eq rideId }
        }
        call.respond(mapOf("deleted" to true))
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
