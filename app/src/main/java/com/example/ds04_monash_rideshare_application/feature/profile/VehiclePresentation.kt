package com.example.ds04_monash_rideshare_application.feature.profile

import com.example.ds04_monash_rideshare_application.Repository.Remote.CreateVehicleDraft
import com.example.ds04_monash_rideshare_application.Repository.Remote.VehicleSummary
import java.util.Locale

internal val fuelTypes = listOf("PETROL", "DIESEL", "HYBRID", "PHEV", "ELECTRIC")
internal val vehicleTypes = listOf("SEDAN", "HATCHBACK", "SUV", "WAGON", "UTE", "VAN", "COUPE", "OTHER")
internal fun vehicleLabel(value: String): String = when (value.uppercase(Locale.ROOT)) {
    "PHEV" -> "Plug-in Hybrid"
    "SUV" -> "SUV"
    else -> value.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
}
internal fun vehicleSummary(vehicle: VehicleSummary): String =
    "${vehicle.make} ${vehicle.model}\n${vehicle.colour} ${vehicleLabel(vehicle.vehicleType)}\n" +
        "Plate: ${vehicle.registrationMasked ?: "Not recorded"}\n${vehicle.seatCapacity} total seats · ${vehicleLabel(vehicle.fuelType)}"

internal fun validateVehicle(draft: CreateVehicleDraft): String? = when {
    draft.make.trim().length !in 1..64 -> "Enter a car brand (up to 64 characters)."
    draft.model.trim().length !in 1..64 -> "Enter a car model (up to 64 characters)."
    draft.registrationMasked.orEmpty().trim().length !in 1..16 -> "Enter a registration plate (up to 16 characters)."
    draft.colour.trim().length !in 1..32 -> "Enter a colour (up to 32 characters)."
    draft.seatCapacity !in 2..12 -> "Choose 2–12 total seats, including the driver."
    draft.vehicleType !in vehicleTypes -> "Choose a vehicle type."
    draft.fuelType !in fuelTypes -> "Choose a fuel type."
    else -> null
}
