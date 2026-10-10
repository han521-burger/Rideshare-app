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
    // Server time the driver completed the ride; absent for older rides and ones the system completed on its own.
    val completedAt: Long? = null,
    val driverEnRoute: Boolean = false,
    val recurringGroupId: String? = null,
    val stops: List<RideStopDto> = emptyList(),
    val driver: UserSummaryDto? = null,
    val vehicle: VehicleDto? = null,
    val matchScore: MatchScoreDto?
)

// GET /schedule-warnings. Nullable where the backend may omit a field: Gson ignores Kotlin defaults.
data class ScheduleWarningDetailsDto(
    val previousRideId: String?,
    val previousCompletedAt: Long,
    val previousPlannedArrivalTime: Long? = null,
    val fromPointId: String? = null,
    val fromPointName: String? = null,
    val toPointId: String? = null,
    val toPointName: String? = null,
    val repositioningSeconds: Long,
    val transferBufferSeconds: Long,
    val earliestFeasibleDeparture: Long,
    val nextDepartureTime: Long
)

data class ScheduleWarningDto(
    val warningId: String,
    val type: String,
    val subjectKind: String,
    val subjectId: String,
    val createdAt: Long,
    val details: ScheduleWarningDetailsDto?
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
    val fuelType: String,
    val vehicleType: String,
    val colour: String
)

data class CreateVehicleRequestBody(
    val year: Int,
    val make: String,
    val model: String,
    val variant: String? = null,
    val registrationMasked: String? = null,
    val seatCapacity: Int,
    val fuelType: String,
    val vehicleType: String,
    val colour: String
)

data class UpdateVehicleRequestBody(
    val year: Int,
    val make: String,
    val model: String,
    val variant: String? = null,
    val registrationMasked: String? = null,
    val seatCapacity: Int,
    val fuelType: String,
    val vehicleType: String,
    val colour: String
)

data class CreateStopInputBody(val lat: Double, val lng: Double, val label: String? = null)

data class CreateRideRequestBody(
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
    val matchScore: MatchScoreDto? = null,
    // CONFLICTED requests only (the passenger's own view): why the request can no longer happen.
    val conflict: ScheduleConflict? = null
)

data class OccurrenceResultDto(
    val departureTime: Long,
    val outcome: String,
    val rideId: String? = null,
    val requestId: String? = null,
    val code: String? = null,
    val message: String? = null,
    val existingRequestId: String? = null,
    val conflict: ScheduleConflict? = null
)

// The answer to POST /requests and POST /rides/{id}/requests: always this, for one request or many.
data class CreateRequestsResultDto(
    val requested: Int,
    val created: Int,
    val rejected: Int,
    // Nullable on purpose: Gson ignores Kotlin defaults, so a field a backend leaves out arrives as null.
    val requests: List<RideRequestDto>? = null,
    val occurrences: List<OccurrenceResultDto>? = null
)

data class CreateSeatRequestBody(
    val seatsRequested: Int,
    val note: String? = null,
    val applyToRecurringGroup: Boolean = false,
    // One id per logical submission, re-sent unchanged on a retry so the backend never creates twice.
    val clientRequestId: String? = null,
    // One id per intended journey (per ride search): requests for several rides from the SAME search share it and
    // are alternatives. Null for a request not made from a search.
    val journeyIntentId: String? = null
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
    val repeatWeeks: Int? = null,
    // See CreateSeatRequestBody.clientRequestId.
    val clientRequestId: String? = null
)

data class UpdateRequestStatusBody(
    val status: String,
    val targetRideId: String? = null,
    val newRideSeatsTotal: Int? = null,
    val cancelMyConflictingRequests: Boolean = false
)

data class MessageDto(
    val messageId: String,
    val rideId: String,
    val senderId: String,
    val body: String,
    val sentAt: Long,
    val senderName: String? = null
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

data class RequestFunnelCountsDto(
    val pending: Int = 0,
    val accepted: Int = 0,
    val rejected: Int = 0,
    val cancelled: Int = 0,
    val completed: Int = 0,
    val expired: Int = 0,
    val conflicted: Int = 0
)

data class HotzoneEntryDto(val locationName: String, val count: Int)

data class RouteFrequencyDto(val originLabel: String, val destinationLabel: String, val count: Int)

data class AnalyticsResponseDto(
    val passengerFunnel: RequestFunnelCountsDto,
    val driverFunnel: RequestFunnelCountsDto,
    val averageDriverResponseMinutes: Double? = null,
    val hotzones: List<HotzoneEntryDto> = emptyList(),
    val tripsByDayOfWeek: List<Int> = List(7) { 0 },
    val mostUsedRoute: RouteFrequencyDto? = null,
    val averageSeatFillRatePercent: Double? = null
)

data class RegisterRequestBody(val displayName: String, val email: String, val password: String)

data class LoginRequestBody(val email: String, val password: String)

data class AuthResponseDto(val token: String, val user: UserDto)

data class ChangePasswordRequestBody(val currentPassword: String, val newPassword: String)

data class DeleteAccountRequestBody(val password: String)
