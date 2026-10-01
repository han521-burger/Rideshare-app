package com.example.ds04_monash_rideshare_application.backend

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import java.util.Locale

internal val vehicleTypes = setOf("SEDAN", "HATCHBACK", "SUV", "WAGON", "UTE", "VAN", "COUPE", "OTHER")
internal val vehicleFuelTypes = setOf("PETROL", "DIESEL", "HYBRID", "PHEV", "ELECTRIC")

internal fun CreateVehicleRequest.normalized(): CreateVehicleRequest {
    val clean = copy(make = make.trim(), model = model.trim(), colour = colour.trim(),
        registrationMasked = registrationMasked?.trim()?.uppercase(Locale.ROOT),
        fuelType = fuelType.trim().uppercase(Locale.ROOT), vehicleType = vehicleType.trim().uppercase(Locale.ROOT),
        variant = variant?.trim()?.takeIf { it.isNotEmpty() })
    if (clean.make.length !in 1..64) throw ValidationException("Brand must contain 1–64 characters")
    if (clean.model.length !in 1..64) throw ValidationException("Model must contain 1–64 characters")
    if (clean.colour.length !in 1..32) throw ValidationException("Colour must contain 1–32 characters")
    if (clean.registrationMasked.orEmpty().length !in 1..16) throw ValidationException("Registration plate must contain 1–16 characters")
    if (clean.seatCapacity !in 2..12) throw ValidationException("Total seating capacity must be between 2 and 12, including the driver")
    if (clean.fuelType !in vehicleFuelTypes) throw ValidationException("Choose a supported fuel type")
    if (clean.vehicleType !in vehicleTypes) throw ValidationException("Choose a supported vehicle type")
    if (clean.year != 0 && clean.year !in 1900..2100) throw ValidationException("Enter a valid vehicle year")
    if ((clean.variant?.length ?: 0) > 64) throw ValidationException("Variant must be at most 64 characters")
    return clean
}

// Called within the caller's transaction, for every path that creates a driver ride.
internal fun requireDriverVehicle(ownerId: String, passengerSeats: Int): ResultRow {
    val vehicle = Vehicles.selectAll().where { Vehicles.ownerId eq ownerId }.singleOrNull()
        ?: throw ValidationException("Register your vehicle before offering a ride")
    if (passengerSeats !in 1 until vehicle[Vehicles.seatCapacity]) {
        throw ValidationException("Offer between 1 and ${vehicle[Vehicles.seatCapacity] - 1} passenger seats; the driver needs a seat too")
    }
    return vehicle
}
