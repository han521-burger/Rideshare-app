package com.example.ds04_monash_rideshare_application.backend.routes

import com.example.ds04_monash_rideshare_application.backend.DashboardDto
import com.example.ds04_monash_rideshare_application.backend.Emissions
import com.example.ds04_monash_rideshare_application.backend.LeaderboardEntryDto
import com.example.ds04_monash_rideshare_application.backend.LeaderboardResponse
import com.example.ds04_monash_rideshare_application.backend.MonthlyTripSummaryDto
import com.example.ds04_monash_rideshare_application.backend.RideRequestStatus
import com.example.ds04_monash_rideshare_application.backend.RideRequests
import com.example.ds04_monash_rideshare_application.backend.RideStatus
import com.example.ds04_monash_rideshare_application.backend.Rides
import com.example.ds04_monash_rideshare_application.backend.Users
import com.example.ds04_monash_rideshare_application.backend.Vehicles
import com.example.ds04_monash_rideshare_application.backend.requireUserId
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private data class CompletedTrip(val distanceMeters: Double, val fuelType: String?, val seatsFilled: Int, val completedAt: Long)

fun Route.dashboardRoutes() {
    // Trip history: rides you drove that are COMPLETED, plus rides you rode as an
    // ACCEPTED/COMPLETED passenger where the ride itself is COMPLETED (item 7/8).
    get("/users/me/trip-history") {
        val userId = call.requireUserId()
        val rides = transaction {
            val driverRows = Rides.selectAll()
                .where { (Rides.driverId eq userId) and (Rides.status eq RideStatus.COMPLETED) }.toList()

            // rideId is nullable on RideRequests (a still-driverless standalone request), but a
            // row can only reach ACCEPTED/COMPLETED once a ride's attached - filterNotNull just
            // keeps that guarantee explicit for the type checker/inList below.
            val passengerRideIds = RideRequests.selectAll()
                .where {
                    (RideRequests.passengerId eq userId) and
                        ((RideRequests.status eq RideRequestStatus.ACCEPTED) or (RideRequests.status eq RideRequestStatus.COMPLETED))
                }.mapNotNull { it[RideRequests.rideId] }.toSet()

            val passengerRows = if (passengerRideIds.isEmpty()) emptyList() else {
                Rides.selectAll()
                    .where { (Rides.rideId inList passengerRideIds) and (Rides.status eq RideStatus.COMPLETED) }.toList()
            }
            enrichRideDtos((driverRows + passengerRows).distinctBy { it[Rides.rideId] })
                .sortedByDescending { it.departureTime }
        }
        call.respond(rides)
    }

    get("/users/me/dashboard") {
        val userId = call.requireUserId()
        val trips = transaction { completedTripsDrivenBy(userId) }

        val totalCo2AvoidedKg = trips.sumOf { Emissions.co2AvoidedKgForTrip(it.distanceMeters, it.fuelType, it.seatsFilled) }
        val totalFuelSavedLitres = trips.sumOf { Emissions.fuelSavedLitresForTrip(it.distanceMeters, it.fuelType, it.seatsFilled) }
        val monthly = trips
            .groupBy { monthLabel(it.completedAt) }
            .map { (label, tripsInMonth) ->
                MonthlyTripSummaryDto(
                    monthLabel = label,
                    tripsCompleted = tripsInMonth.size,
                    co2AvoidedKg = tripsInMonth.sumOf { Emissions.co2AvoidedKgForTrip(it.distanceMeters, it.fuelType, it.seatsFilled) }
                )
            }
            .sortedByDescending { it.monthLabel }

        call.respond(
            DashboardDto(
                totalRidesCompleted = trips.size,
                totalCo2AvoidedKg = totalCo2AvoidedKg,
                totalFuelSavedLitres = totalFuelSavedLitres,
                monthlyTripSummary = monthly
            )
        )
    }

    // Campus-wide CO2-avoided ranking. Computed on the fly (fine at trial scale, see
    // docs/RTM.md) rather than maintained as a running total - always consistent with the
    // dashboard's own numbers since it's the exact same calculation, just for everyone.
    get("/leaderboard") {
        val userId = call.requireUserId()
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 10

        val response = transaction {
            val allUserIds = Users.selectAll().map { it[Users.userId] }
            val ranked = allUserIds
                .map { id -> id to completedTripsDrivenBy(id).sumOf { Emissions.co2AvoidedKgForTrip(it.distanceMeters, it.fuelType, it.seatsFilled) } }
                .filter { (_, co2) -> co2 > 0.0 }
                .sortedByDescending { (_, co2) -> co2 }

            val namesById = Users.selectAll().associate { it[Users.userId] to it[Users.displayName] }
            val entries = ranked.mapIndexed { index, (id, co2) ->
                LeaderboardEntryDto(userId = id, displayName = namesById[id] ?: id, totalCo2AvoidedKg = co2, rank = index + 1)
            }
            LeaderboardResponse(top = entries.take(limit), me = entries.find { it.userId == userId })
        }
        call.respond(response)
    }
}

private fun completedTripsDrivenBy(userId: String): List<CompletedTrip> {
    val driverRides = Rides.selectAll()
        .where { (Rides.driverId eq userId) and (Rides.status eq RideStatus.COMPLETED) }
        .toList()

    return driverRides.map { ride ->
        val vehicleId = ride[Rides.vehicleId]
        val fuelType = vehicleId?.let { ownedVehicleId ->
            Vehicles.selectAll().where { Vehicles.vehicleId eq ownedVehicleId }.firstOrNull()?.get(Vehicles.fuelType)
        }
        val seatsFilled = ride[Rides.seatsTotal] - ride[Rides.seatsAvailable]
        CompletedTrip(
            distanceMeters = ride[Rides.distanceMeters],
            fuelType = fuelType,
            seatsFilled = seatsFilled.coerceAtLeast(0),
            completedAt = ride[Rides.departureTime]
        )
    }
}

private fun monthLabel(epochMillis: Long): String {
    val date = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    val monthName = date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    return "$monthName ${date.year}"
}
