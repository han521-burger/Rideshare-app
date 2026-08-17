package com.example.ds04_monash_rideshare_application.Repository.Remote

// Mirrors backend/.../Dtos.kt field-for-field. Kept as a separate wire-format layer (rather than
// reusing the Room @Entity classes directly) so the local cache schema and the backend's JSON
// shape are free to evolve independently - RideApiServiceImpl is the only place that maps
// between them.

data class UserDto(
    val userId: String,
    val externalSubjectId: String,
    val emailAccount: String,
    val displayName: String,
    val preferredName: String,
    val phoneNumber: String?,
    val photoRef: String?,
    val accountStatus: String,
    val passengerEnabled: Boolean,
    val createdAt: Long
)

data class UpdateProfileRequestBody(
    val displayName: String,
    val preferredName: String,
    val phoneNumber: String? = null,
    val photoRef: String? = null,
    val passengerEnabled: Boolean = true
)

data class MatchScoreDto(
    val total: Double,
    val routeScore: Double,
    val timeScore: Double,
    val pickupScore: Double,
    val detourScore: Double
)

data class RideDto(
    val rideId: String,
    val driverId: String,
    val vehicleId: String?,
    val originLat: Double,
    val originLng: Double,
    val destinationLat: Double,
    val destinationLng: Double,
    val distanceMeters: Double,
    val estimatedDurationSeconds: Long,
    val departureTime: Long,
    val seatsTotal: Int,
    val seatsAvailable: Int,
    val notes: String?,
    val status: String,
    val createdAt: Long,
    val matchScore: MatchScoreDto?
)

data class CreateRideRequestBody(
    val vehicleId: String?,
    val originLat: Double,
    val originLng: Double,
    val destinationLat: Double,
    val destinationLng: Double,
    val distanceMeters: Double,
    val estimatedDurationSeconds: Long,
    val departureTime: Long,
    val seatsTotal: Int,
    val notes: String?
)

data class UpdateRideRequestBody(
    val departureTime: Long? = null,
    val seatsTotal: Int? = null,
    val notes: String? = null,
    val status: String? = null
)

data class MatchSearchResponse(
    val usedFallback: Boolean,
    val rides: List<RideDto>
)

data class RideRequestDto(
    val requestId: String,
    val rideId: String,
    val passengerId: String,
    val seatsRequested: Int,
    val status: String,
    val createdAt: Long,
    val respondedAt: Long?
)

data class CreateSeatRequestBody(val seatsRequested: Int)

data class UpdateRequestStatusBody(val status: String)

data class MessageDto(
    val messageId: String,
    val rideId: String,
    val senderId: String,
    val body: String,
    val sentAt: Long
)

data class SendMessageRequestBody(val body: String)

data class DashboardResponseDto(
    val totalRidesCompleted: Int,
    val totalCo2AvoidedKg: Double,
    val monthlyTripSummary: List<MonthlyTripSummaryDto>
)

data class MonthlyTripSummaryDto(
    val monthLabel: String,
    val tripsCompleted: Int,
    val co2AvoidedKg: Double
)
