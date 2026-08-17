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
    val emergencyContactName: String? = null,
    val emergencyContactPhone: String? = null,
    val createdAt: Long
)

// Minimal public info about a ride's other party - never the full UserDto (no need to leak
// account status, passengerEnabled, etc. to someone just viewing a ride card).
@Serializable
data class UserSummaryDto(
    val userId: String,
    val displayName: String,
    val photoRef: String? = null,
    val phoneNumber: String? = null
)

@Serializable
data class UpdateProfileRequest(
    val displayName: String,
    val preferredName: String,
    val phoneNumber: String? = null,
    val photoRef: String? = null,
    val passengerEnabled: Boolean = true,
    val emergencyContactName: String? = null,
    val emergencyContactPhone: String? = null
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
data class RideStopDto(
    val stopId: String,
    val sequence: Int,
    val lat: Double,
    val lng: Double,
    val label: String? = null
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
    val driverEnRoute: Boolean = false,
    val recurringGroupId: String? = null,
    val stops: List<RideStopDto> = emptyList(),
    // Populated wherever it's cheap to join (GET /rides, /rides/match, /rides/{id}, /users/me/*)
    // so the client can render a driver/vehicle card without a second round trip.
    val driver: UserSummaryDto? = null,
    val vehicle: VehicleDto? = null,
    // Advisory match score, only populated by the search/matching endpoint.
    val matchScore: MatchScoreDto? = null
)

@Serializable
data class MatchSearchResponse(
    val usedFallback: Boolean,
    val rides: List<RideDto>
)

@Serializable
data class CreateStopInput(
    val lat: Double,
    val lng: Double,
    val label: String? = null
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
    val notes: String? = null,
    val stops: List<CreateStopInput> = emptyList(),
    // "Repeat this route every Mon/Wed 8am": ISO weekday numbers (1=Mon..7=Sun) this ride should
    // also be posted on, for the next repeatWeeks weeks (bounded - there's no scheduler in this
    // trial, so recurring rides are concrete rows generated up front, not created on the fly).
    val repeatDaysOfWeek: List<Int> = emptyList(),
    val repeatWeeks: Int = 4
)

@Serializable
data class UpdateRideRequest(
    val departureTime: Long? = null,
    val seatsTotal: Int? = null,
    val notes: String? = null,
    val status: String? = null,
    val driverEnRoute: Boolean? = null
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
    val note: String? = null,
    val createdAt: Long,
    val respondedAt: Long? = null,
    // Populated by GET /requests and /requests/mine so callers don't have to look the ride up
    // separately just to show a date/place instead of a bare ride ID.
    val ride: RideDto? = null,
    val passenger: UserSummaryDto? = null
)

@Serializable
data class CreateRideRequestBody(
    val seatsRequested: Int = 1,
    val note: String? = null,
    // "Repeat this ride" on the passenger side: if the target ride belongs to a recurring group
    // (RideDto.recurringGroupId), also request a seat on every other OPEN sibling in that group.
    val applyToRecurringGroup: Boolean = false
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
    val totalFuelSavedLitres: Double,
    val monthlyTripSummary: List<MonthlyTripSummaryDto>
)

@Serializable
data class MonthlyTripSummaryDto(
    val monthLabel: String,
    val tripsCompleted: Int,
    val co2AvoidedKg: Double
)

@Serializable
data class RatingDto(
    val ratingId: String,
    val rideId: String,
    val raterId: String,
    val ratedUserId: String,
    val stars: Int,
    val tags: List<String> = emptyList(),
    val comment: String? = null,
    val createdAt: Long
)

@Serializable
data class SubmitRatingRequest(
    val ratedUserId: String,
    val stars: Int,
    val tags: List<String> = emptyList(),
    val comment: String? = null
)

@Serializable
data class FavoriteDriverDto(
    val driverId: String,
    val displayName: String,
    val photoRef: String? = null,
    val addedAt: Long
)

@Serializable
data class LeaderboardEntryDto(
    val userId: String,
    val displayName: String,
    val totalCo2AvoidedKg: Double,
    val rank: Int
)

@Serializable
data class LeaderboardResponse(
    val top: List<LeaderboardEntryDto>,
    val me: LeaderboardEntryDto? = null
)

@Serializable
data class ErrorResponse(val error: String)
