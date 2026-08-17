package com.example.ds04_monash_rideshare_application.backend.routes

import com.example.ds04_monash_rideshare_application.backend.DashboardDto
import com.example.ds04_monash_rideshare_application.backend.Emissions
import com.example.ds04_monash_rideshare_application.backend.MonthlyTripSummaryDto
import com.example.ds04_monash_rideshare_application.backend.RideRequestStatus
import com.example.ds04_monash_rideshare_application.backend.RideRequests
import com.example.ds04_monash_rideshare_application.backend.RideStatus
import com.example.ds04_monash_rideshare_application.backend.Rides
import com.example.ds04_monash_rideshare_application.backend.Vehicles
import com.example.ds04_monash_rideshare_application.backend.requireUserId
import com.example.ds04_monash_rideshare_application.backend.toRideDto
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
            val asDriver = Rides.selectAll()
                .where { (Rides.driverId eq userId) and (Rides.status eq RideStatus.COMPLETED) }
                .map { it.toRideDto() }

            val passengerRideIds = RideRequests.selectAll()
                .where {
                    (RideRequests.passengerId eq userId) and
                        ((RideRequests.status eq RideRequestStatus.ACCEPTED) or (RideRequests.status eq RideRequestStatus.COMPLETED))
                }.map { it[RideRequests.rideId] }.toSet()

            val asPassenger = if (passengerRideIds.isEmpty()) emptyList() else {
                Rides.selectAll()
                    .where { (Rides.rideId inList passengerRideIds) and (Rides.status eq RideStatus.COMPLETED) }
                    .map { it.toRideDto() }
            }
            (asDriver + asPassenger).distinctBy { it.rideId }.sortedByDescending { it.departureTime }
        }
        call.respond(rides)
    }

    get("/users/me/dashboard") {
        val userId = call.requireUserId()
        val trips = transaction {
            val driverRides = Rides.selectAll()
                .where { (Rides.driverId eq userId) and (Rides.status eq RideStatus.COMPLETED) }
                .toList()

            driverRides.map { ride ->
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

        val totalCo2AvoidedKg = trips.sumOf { Emissions.co2AvoidedKgForTrip(it.distanceMeters, it.fuelType, it.seatsFilled) }
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
                monthlyTripSummary = monthly
            )
        )
    }
}

private fun monthLabel(epochMillis: Long): String {
    val date = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    val monthName = date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    return "$monthName ${date.year}"
}
