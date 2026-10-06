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
    val phoneNumber: String? = null,
    // Aggregated from Ratings where this user is the ratedUserId - null/0 if they have none yet.
    val averageRating: Double? = null,
    val ratingCount: Int = 0
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
    val fuelType: String,
    val vehicleType: String,
    val colour: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class CreateVehicleRequest(
    val year: Int = 0,
    val make: String,
    val model: String,
    val variant: String? = null,
    val registrationMasked: String? = null,
    val seatCapacity: Int,
    val fuelType: String,
    val vehicleType: String,
    val colour: String
)

// A driver may only ever have one vehicle (see requireSingleVehicle in VehicleRoutes.kt) - this
// updates it in place, keeping the same vehicleId (and so the same link from any of the driver's
// existing rides) rather than making them delete and re-register.
@Serializable
data class UpdateVehicleRequest(
    val year: Int = 0,
    val make: String,
    val model: String,
    val variant: String? = null,
    val registrationMasked: String? = null,
    val seatCapacity: Int,
    val fuelType: String,
    val vehicleType: String,
    val colour: String
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
    // Null until a driver is attached - see RideRequests.rideId in Tables.kt.
    val rideId: String? = null,
    val passengerId: String,
    val seatsRequested: Int,
    val status: String,
    val note: String? = null,
    val createdAt: Long,
    val respondedAt: Long? = null,
    // Only meaningful while rideId is null - the passenger's own desired route/time for a
    // standalone request (see POST /requests).
    val originLat: Double? = null,
    val originLng: Double? = null,
    val destinationLat: Double? = null,
    val destinationLng: Double? = null,
    val isAsap: Boolean = false,
    val desiredDepartureTime: Long? = null,
    val repeatDaysOfWeek: List<Int> = emptyList(),
    val repeatWeeks: Int? = null,
    // Populated by GET /requests and /requests/mine so callers don't have to look the ride up
    // separately just to show a date/place instead of a bare ride ID.
    val ride: RideDto? = null,
    val passenger: UserSummaryDto? = null,
    // Advisory match score against the caller's own rides - only populated by GET /requests for
    // a standalone (rideId == null) request, the same way RideDto.matchScore works for /rides/match.
    val matchScore: MatchScoreDto? = null
)

@Serializable
data class CreateRideRequestBody(
    val seatsRequested: Int = 1,
    val note: String? = null,
    // "Repeat this ride" on the passenger side: if the target ride belongs to a recurring group
    // (RideDto.recurringGroupId), also request a seat on every other OPEN sibling in that group.
    val applyToRecurringGroup: Boolean = false
)

// A passenger's standalone ask - route + time with no driver picked yet. A driver later accepts
// it by supplying targetRideId on PUT /requests/{id} (see UpdateRideRequestStatusBody).
@Serializable
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

@Serializable
data class UpdateRideRequestStatusBody(
    val status: String,
    // For accepting (status=ACCEPTED) a request that has no rideId yet, one of two ways to
    // resolve which ride it attaches to: targetRideId picks an existing open ride of the
    // driver's; newRideSeatsTotal instead creates a brand new ride from the request's own
    // route/time, so a driver doesn't need to have already posted a matching ride to accept.
    val targetRideId: String? = null,
    val newRideSeatsTotal: Int? = null,
    // The driver confirmed "drive this passenger instead" - cancel the driver's own overlapping
    // ride requests as part of the accept (see CONFLICT_OWN_PENDING_REQUEST in Lifecycle.kt).
    val cancelMyConflictingRequests: Boolean = false
)

@Serializable
data class MessageDto(
    val messageId: String,
    val rideId: String,
    val senderId: String,
    val body: String,
    val sentAt: Long,
    // The sender's display name, so the chat can show it instead of an account id.
    val senderName: String? = null
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

// Rule-based analytics computed on the fly from existing tables (docs/FEATURE_PROPOSALS.md item
// 5) - deliberately no ML/training, same spirit as MatchingEngine.kt. Everything here is scoped
// to the caller's own activity except hotzones, which is campus-wide (it's meant to help a driver
// decide where to offer a ride, not just reflect on one they already took).
@Serializable
data class RequestFunnelCounts(
    val pending: Int = 0,
    val accepted: Int = 0,
    val rejected: Int = 0,
    val cancelled: Int = 0,
    val completed: Int = 0,
    val expired: Int = 0
)

@Serializable
data class HotzoneEntryDto(val locationName: String, val count: Int)

@Serializable
data class RouteFrequencyDto(val originLabel: String, val destinationLabel: String, val count: Int)

@Serializable
data class AnalyticsDto(
    // Your own sent requests, by outcome.
    val passengerFunnel: RequestFunnelCounts,
    // Requests sent to rides you drive, by outcome.
    val driverFunnel: RequestFunnelCounts,
    // First explicit driver ACCEPT/REJECT latency. Unknown historical decisions are excluded;
    // null when no recorded first-decision samples exist. Lifecycle/undo timestamps are separate.
    val averageDriverResponseMinutes: Double? = null,
    // Origins and destinations from all rides created in the last 7 days, across users.
    val hotzones: List<HotzoneEntryDto> = emptyList(),
    // Completed trips (driving + riding) by day of week, index 0 = Monday .. 6 = Sunday.
    val tripsByDayOfWeek: List<Int> = List(7) { 0 },
    val mostUsedRoute: RouteFrequencyDto? = null,
    // Drivers only - average (seatsTotal - seatsAvailable) / seatsTotal across your completed
    // rides, null if you've never completed one as a driver.
    val averageSeatFillRatePercent: Double? = null
)

@Serializable
data class ErrorResponse(val error: String, val code: String? = null)

@Serializable
data class RegisterRequest(val displayName: String, val email: String, val password: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

// Returned by sign-up and sign-in: the session token the app sends as its bearer token, plus
// the signed-in user's own profile.
@Serializable
data class AuthResponse(val token: String, val user: UserDto)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

// Deleting an account asks for the password again, so a phone left signed in can't be used to do it.
@Serializable
data class DeleteAccountRequest(val password: String)
