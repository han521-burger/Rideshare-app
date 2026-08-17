package com.example.ds04_monash_rideshare_application.Repository.Remote

// Rich domain models the new flow (bottom-nav Home/Requests/Activity/Profile) works with
// directly - no Room cache layer underneath. Mirrors backend/.../Dtos.kt in shape, but these are
// the types screens/ViewModel actually hold; RideApiServiceImpl is the only place that maps the
// wire DTOs (BackendDtos.kt) onto these.

data class UserSummary(
    val userId: String,
    val displayName: String,
    val photoRef: String?,
    val phoneNumber: String?
)

data class VehicleSummary(
    val vehicleId: String,
    val make: String,
    val model: String,
    val variant: String?,
    val registrationMasked: String?,
    val fuelType: String,
    val seatCapacity: Int
)

data class RideStop(
    val stopId: String,
    val sequence: Int,
    val lat: Double,
    val lng: Double,
    val label: String?
)

object RideStatus {
    const val OPEN = "OPEN"
    const val CONFIRMED = "CONFIRMED"
    const val COMPLETED = "COMPLETED"
    const val CANCELLED = "CANCELLED"
}

object RequestStatus {
    const val PENDING = "PENDING"
    const val ACCEPTED = "ACCEPTED"
    const val REJECTED = "REJECTED"
    const val CANCELLED = "CANCELLED"
    const val COMPLETED = "COMPLETED"
}

data class RideSummary(
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
    val driverEnRoute: Boolean,
    val recurringGroupId: String?,
    val stops: List<RideStop>,
    val driver: UserSummary?,
    val vehicle: VehicleSummary?,
    val matchScoreTotal: Double?
)

data class RideRequestSummary(
    val requestId: String,
    val rideId: String,
    val passengerId: String,
    val seatsRequested: Int,
    val status: String,
    val note: String?,
    val createdAt: Long,
    val respondedAt: Long?,
    val ride: RideSummary?,
    val passenger: UserSummary?
)

data class MessageSummary(
    val messageId: String,
    val rideId: String,
    val senderId: String,
    val body: String,
    val sentAt: Long
)

data class RatingSummary(
    val ratingId: String,
    val rideId: String,
    val raterId: String,
    val ratedUserId: String,
    val stars: Int,
    val tags: List<String>,
    val comment: String?,
    val createdAt: Long
)

data class FavoriteDriverSummary(
    val driverId: String,
    val displayName: String,
    val photoRef: String?,
    val addedAt: Long
)

data class LeaderboardEntry(
    val userId: String,
    val displayName: String,
    val totalCo2AvoidedKg: Double,
    val rank: Int
)

data class LeaderboardResult(
    val top: List<LeaderboardEntry>,
    val me: LeaderboardEntry?
)

data class MonthlyTripSummary(
    val monthLabel: String,
    val tripsCompleted: Int,
    val co2AvoidedKg: Double
)

data class DashboardSummary(
    val totalRidesCompleted: Int,
    val totalCo2AvoidedKg: Double,
    val totalFuelSavedLitres: Double,
    val monthlyTripSummary: List<MonthlyTripSummary>
)

data class UserAccountSummary(
    val userId: String,
    val displayName: String,
    val preferredName: String,
    val emailAccount: String,
    val phoneNumber: String?,
    val photoRef: String?,
    val passengerEnabled: Boolean,
    val emergencyContactName: String?,
    val emergencyContactPhone: String?
)

// Everything a driver fills in on the Offer a Ride flow. driverId/rideId/status/createdAt are
// all assigned by the backend, not the client.
data class CreateRideDraft(
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
    val stops: List<StopInput> = emptyList(),
    val repeatDaysOfWeek: List<Int> = emptyList(),
    val repeatWeeks: Int = 4
)

data class StopInput(val lat: Double, val lng: Double, val label: String?)

data class RideSearchCriteria(
    val originLat: Double,
    val originLng: Double,
    val destinationLat: Double,
    val destinationLng: Double,
    val departureTime: Long,
    val seatsNeeded: Int = 1
)
