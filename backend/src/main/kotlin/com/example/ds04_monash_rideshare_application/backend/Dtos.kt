package com.example.ds04_monash_rideshare_application.backend

import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    val userId: String,
    val externalSubjectId: String,
    val emailAccount: String,
    val displayName: String,
    val preferredName: String,
    val phoneNumber: String? = null,
    val photoRef: String? = null,
    val accountStatus: String,
    val passengerEnabled: Boolean,
    val createdAt: Long
)

@Serializable
data class UpdateProfileRequest(
    val displayName: String,
    val preferredName: String,
    val phoneNumber: String? = null,
    val photoRef: String? = null,
    val passengerEnabled: Boolean = true
)

@Serializable
data class VehicleDto(
    val vehicleId: String,
    val ownerId: String,
    val year: Int,
    val make: String,
    val model: String,
    val variant: String? = null,
    val registrationMasked: String? = null,
    val seatCapacity: Int,
    val fuelType: String
)

@Serializable
data class RideDto(
    val rideId: String,
    val driverId: String,
    val vehicleId: String? = null,
    val originLat: Double,
    val originLng: Double,
    val destinationLat: Double,
    val destinationLng: Double,
    val distanceMeters: Double,
    val estimatedDurationSeconds: Long,
    val departureTime: Long,
    val seatsTotal: Int,
    val seatsAvailable: Int,
    val notes: String? = null,
    val status: String,
    val createdAt: Long,
    // Advisory match score, only populated by the search/matching endpoint.
    val matchScore: MatchScoreDto? = null
)

@Serializable
data class MatchSearchResponse(
    val usedFallback: Boolean,
    val rides: List<RideDto>
)

@Serializable
data class CreateRideRequest(
    val vehicleId: String? = null,
    val originLat: Double,
    val originLng: Double,
    val destinationLat: Double,
    val destinationLng: Double,
    val distanceMeters: Double,
    val estimatedDurationSeconds: Long,
    val departureTime: Long,
    val seatsTotal: Int,
    val notes: String? = null
)

@Serializable
data class UpdateRideRequest(
    val departureTime: Long? = null,
    val seatsTotal: Int? = null,
    val notes: String? = null,
    val status: String? = null
)

@Serializable
data class MatchScoreDto(
    val total: Double,
    val routeScore: Double,
    val timeScore: Double,
    val pickupScore: Double,
    val detourScore: Double
)

@Serializable
data class RideRequestDto(
    val requestId: String,
    val rideId: String,
    val passengerId: String,
    val seatsRequested: Int,
    val status: String,
    val createdAt: Long,
    val respondedAt: Long? = null
)

@Serializable
data class CreateRideRequestBody(
    val seatsRequested: Int = 1
)

@Serializable
data class UpdateRideRequestStatusBody(
    val status: String
)

@Serializable
data class MessageDto(
    val messageId: String,
    val rideId: String,
    val senderId: String,
    val body: String,
    val sentAt: Long
)

@Serializable
data class SendMessageRequest(
    val body: String
)

@Serializable
data class DashboardDto(
    val totalRidesCompleted: Int,
    val totalCo2AvoidedKg: Double,
    val monthlyTripSummary: List<MonthlyTripSummaryDto>
)

@Serializable
data class MonthlyTripSummaryDto(
    val monthLabel: String,
    val tripsCompleted: Int,
    val co2AvoidedKg: Double
)

@Serializable
data class ErrorResponse(val error: String)
