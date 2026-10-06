package com.example.ds04_monash_rideshare_application.backend.domain

import com.example.ds04_monash_rideshare_application.backend.RideRequestStatus
import com.example.ds04_monash_rideshare_application.backend.RideRequests
import com.example.ds04_monash_rideshare_application.backend.Rides
import com.example.ds04_monash_rideshare_application.backend.Users
import com.example.ds04_monash_rideshare_application.backend.Vehicles
import com.example.ds04_monash_rideshare_application.backend.initDatabase
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files

// Shared setup for the domain tests that need a real (temporary SQLite) database.
internal fun tempDatabaseUrl(): String {
    val file = Files.createTempFile("rideshare-domain-", ".db").toFile().apply { deleteOnExit() }
    return "jdbc:sqlite:${file.absolutePath.replace('\\', '/')}"
}

internal fun openTempDatabase(): String = tempDatabaseUrl().also { initDatabase(it) }

internal fun addUser(id: String, email: String = "$id@student.monash.edu", status: String = "ACTIVE") = transaction {
    Users.insert {
        it[userId] = id
        it[externalSubjectId] = "$id-subject"
        it[emailAccount] = email
        it[displayName] = id
        it[accountStatus] = status
        it[createdAt] = 1L
    }
}

internal fun addVehicle(ownerId: String, seats: Int = 5, fuel: String = "PETROL", id: String = "$ownerId-car") = transaction {
    Vehicles.insert {
        it[vehicleId] = id
        it[Vehicles.ownerId] = ownerId
        it[year] = 2020
        it[make] = "Toyota"
        it[model] = "Corolla"
        it[seatCapacity] = seats
        it[fuelType] = fuel
        it[vehicleType] = "SEDAN"
        it[colour] = "Blue"
    }
}

internal fun addRide(
    id: String, driverId: String, vehicleId: String?, status: String,
    distanceMeters: Double, seatsTotal: Int, seatsAvailable: Int,
    departureTime: Long = System.currentTimeMillis() - 86_400_000L,
    originLat: Double = -37.9112, originLng: Double = 145.1029,
    destinationLat: Double = -37.9105, destinationLng: Double = 145.1362
) = transaction {
    Rides.insert {
        it[rideId] = id
        it[Rides.driverId] = driverId
        it[Rides.vehicleId] = vehicleId
        it[Rides.originLat] = originLat
        it[Rides.originLng] = originLng
        it[Rides.destinationLat] = destinationLat
        it[Rides.destinationLng] = destinationLng
        it[Rides.distanceMeters] = distanceMeters
        it[estimatedDurationSeconds] = 600
        it[Rides.departureTime] = departureTime
        it[Rides.seatsTotal] = seatsTotal
        it[Rides.seatsAvailable] = seatsAvailable
        it[Rides.status] = status
        it[createdAt] = departureTime - 3_600_000L
    }
}

internal fun addRequest(id: String, rideId: String?, passengerId: String, status: String = RideRequestStatus.PENDING, seats: Int = 1) = transaction {
    RideRequests.insert {
        it[requestId] = id
        it[RideRequests.rideId] = rideId
        it[RideRequests.passengerId] = passengerId
        it[seatsRequested] = seats
        it[RideRequests.status] = status
        it[createdAt] = 1L
    }
}
