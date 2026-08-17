package com.example.ds04_monash_rideshare_application.Repository.Remote

// Mirrors backend/.../Dtos.kt field-for-field - the wire format. RideApiServiceImpl is the only
// place that maps these onto the domain models in RideModels.kt that the rest of the app uses.

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
    val emergencyContactName: String? = null,
    val emergencyContactPhone: String? = null,
    val createdAt: Long
)

data class UserSummaryDto(
    val userId: String,
    val displayName: String,
    val photoRef: String? = null,
    val phoneNumber: String? = null,
    val averageRating: Double? = null,
    val ratingCount: Int = 0
)

data class UpdateProfileRequestBody(
    val displayName: String,
    val preferredName: String,
    val phoneNumber: String? = null,
    val photoRef: String? = null,
    val passengerEnabled: Boolean = true,
    val emergencyContactName: String? = null,
    val emergencyContactPhone: String? = null
)

data class MatchScoreDto(
    val total: Double,
    val routeScore: Double,
    val timeScore: Double,
    val pickupScore: Double,
    val detourScore: Double
)

data class RideStopDto(
    val stopId: String,
    val sequence: Int,
    val lat: Double,
    val lng: Double,
    val label: String? = null
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
    val driverEnRoute: Boolean = false,
    val recurringGroupId: String? = null,
    val stops: List<RideStopDto> = emptyList(),
    val driver: UserSummaryDto? = null,
    val vehicle: VehicleDto? = null,
    val matchScore: MatchScoreDto?
)

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

data class CreateStopInputBody(val lat: Double, val lng: Double, val label: String? = null)

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
    val notes: String?,
    val stops: List<CreateStopInputBody> = emptyList(),
    val repeatDaysOfWeek: List<Int> = emptyList(),
    val repeatWeeks: Int = 4
)

data class UpdateRideRequestBody(
    val departureTime: Long? = null,
    val seatsTotal: Int? = null,
    val notes: String? = null,
    val status: String? = null,
    val driverEnRoute: Boolean? = null
)

data class MatchSearchResponse(
    val usedFallback: Boolean,
    val rides: List<RideDto>
)

data class RideRequestDto(
    val requestId: String,
    val rideId: String? = null,
    val passengerId: String,
    val seatsRequested: Int,
    val status: String,
    val note: String? = null,
    val createdAt: Long,
    val respondedAt: Long?,
    val originLat: Double? = null,
    val originLng: Double? = null,
    val destinationLat: Double? = null,
    val destinationLng: Double? = null,
    val isAsap: Boolean = false,
    val desiredDepartureTime: Long? = null,
    val repeatDaysOfWeek: List<Int> = emptyList(),
    val repeatWeeks: Int? = null,
    val ride: RideDto? = null,
    val passenger: UserSummaryDto? = null,
    val matchScore: MatchScoreDto? = null
)

data class CreateSeatRequestBody(
    val seatsRequested: Int,
    val note: String? = null,
    val applyToRecurringGroup: Boolean = false
)

data class CreateStandaloneRequestBody(
    val originLat: Double,
    val originLng: Double,
    val destinationLat: Double,
    val destinationLng: Double,
    val isAsap: Boolean = false,
    val desiredDepartureTime: Long? = null,
    val seatsRequested: Int = 1,
    val note: String? = null,
    val repeatDaysOfWeek: List<Int> = emptyList(),
    val repeatWeeks: Int? = null
)

data class UpdateRequestStatusBody(val status: String, val targetRideId: String? = null, val newRideSeatsTotal: Int? = null)

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
    val totalFuelSavedLitres: Double = 0.0,
    val monthlyTripSummary: List<MonthlyTripSummaryDto>
)

data class MonthlyTripSummaryDto(
    val monthLabel: String,
    val tripsCompleted: Int,
    val co2AvoidedKg: Double
)

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

data class SubmitRatingRequestBody(
    val ratedUserId: String,
    val stars: Int,
    val tags: List<String> = emptyList(),
    val comment: String? = null
)

data class FavoriteDriverDto(
    val driverId: String,
    val displayName: String,
    val photoRef: String? = null,
    val addedAt: Long
)

data class LeaderboardEntryDto(
    val userId: String,
    val displayName: String,
    val totalCo2AvoidedKg: Double,
    val rank: Int
)

data class LeaderboardResponseDto(
    val top: List<LeaderboardEntryDto>,
    val me: LeaderboardEntryDto? = null
)
