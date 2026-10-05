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

// The first failing rule only, in this order. The form attaches the message to the matching field.
internal enum class VehicleField { MAKE, MODEL, PLATE, COLOUR, SEATS, TYPE, FUEL }

internal data class VehicleValidation(val field: VehicleField, val message: String)

internal fun validateVehicleDetailed(draft: CreateVehicleDraft): VehicleValidation? = when {
    draft.make.trim().length !in 1..64 -> VehicleValidation(VehicleField.MAKE, "Enter a car brand (up to 64 characters).")
    draft.model.trim().length !in 1..64 -> VehicleValidation(VehicleField.MODEL, "Enter a car model (up to 64 characters).")
    draft.registrationMasked.orEmpty().trim().length !in 1..16 ->
        VehicleValidation(VehicleField.PLATE, "Enter a registration plate (up to 16 characters).")
    draft.colour.trim().length !in 1..32 -> VehicleValidation(VehicleField.COLOUR, "Enter a colour (up to 32 characters).")
    draft.seatCapacity !in MIN_SEATS..MAX_SEATS ->
        VehicleValidation(VehicleField.SEATS, "Choose 2–12 total seats, including the driver.")
    draft.vehicleType !in vehicleTypes -> VehicleValidation(VehicleField.TYPE, "Choose a vehicle type.")
    draft.fuelType !in fuelTypes -> VehicleValidation(VehicleField.FUEL, "Choose a fuel type.")
    else -> null
}

internal fun validateVehicle(draft: CreateVehicleDraft): String? = validateVehicleDetailed(draft)?.message

internal const val MIN_SEATS = 2
internal const val MAX_SEATS = 12
internal const val DEFAULT_SEATS = 5

internal fun passengerSeatsText(totalSeats: Int): String = "Up to ${totalSeats - 1} passenger seats can be offered."

// Structured, source-only facts for the Profile card and the read-only vehicle summary.
internal data class VehicleFacts(
    val title: String,
    val plate: String,
    val type: String,
    val colour: String,
    val seatsTotal: String,
    val seatsForPassengers: String,
    val fuel: String
)

internal fun vehicleFacts(vehicle: VehicleSummary): VehicleFacts = VehicleFacts(
    title = "${vehicle.make} ${vehicle.model}",
    plate = vehicle.registrationMasked ?: "Not recorded",
    type = vehicleLabel(vehicle.vehicleType),
    colour = vehicle.colour,
    seatsTotal = "${vehicle.seatCapacity} total",
    seatsForPassengers = "${vehicle.seatCapacity - 1} for passengers",
    fuel = vehicleLabel(vehicle.fuelType)
)

internal enum class VehicleIntro { NONE, NEUTRAL, DRIVER_GATE }

// Only a brand-new vehicle gets an introduction. It is the Driver-specific hero when the user
// arrived by choosing Driver without a vehicle, otherwise the neutral line.
internal fun vehicleIntro(hasVehicle: Boolean, driverSetup: Boolean): VehicleIntro = when {
    hasVehicle -> VehicleIntro.NONE
    driverSetup -> VehicleIntro.DRIVER_GATE
    else -> VehicleIntro.NEUTRAL
}

// The post-registration hand-off banner shows only while the Driver switch is actually running.
internal fun showDriverHandOff(driverSetup: Boolean, driverChecking: Boolean, hasVehicle: Boolean, editing: Boolean): Boolean =
    driverSetup && driverChecking && hasVehicle && !editing

internal fun vehicleScreenTitle(hasVehicle: Boolean): String = if (hasVehicle) "My vehicle" else "Register your vehicle"

internal fun vehiclePrimaryLabel(hasVehicle: Boolean): String = if (hasVehicle) "Save changes" else "Register vehicle"

internal fun vehicleBusyLabel(hasVehicle: Boolean): String = if (hasVehicle) "Saving…" else "Registering…"
