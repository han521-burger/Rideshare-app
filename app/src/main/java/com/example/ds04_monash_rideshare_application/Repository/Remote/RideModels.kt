package com.example.ds04_monash_rideshare_application.Repository.Remote

// Rich domain models the new flow (bottom-nav Home/Requests/Activity/Profile) works with
// directly - no Room cache layer underneath. Mirrors backend/.../Dtos.kt in shape, but these are
// the types screens/ViewModel actually hold; RideApiServiceImpl is the only place that maps the
// wire DTOs (BackendDtos.kt) onto these.

data class UserSummary(
    val userId: String,
    val displayName: String,
    val photoRef: String?,
    val phoneNumber: String?,
    val averageRating: Double?,
    val ratingCount: Int
)

data class VehicleSummary(
    val vehicleId: String,
    val year: Int,
    val make: String,
    val model: String,
    val variant: String?,
    val registrationMasked: String?,
    val fuelType: String,
    val seatCapacity: Int,
    val vehicleType: String,
    val colour: String
)

data class CreateVehicleDraft(
    val year: Int,
    val make: String,
    val model: String,
    val variant: String?,
    val registrationMasked: String?,
    val seatCapacity: Int,
    val fuelType: String,
    val vehicleType: String,
    val colour: String
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
    const val EXPIRED = "EXPIRED"
}

object RequestStatus {
    const val PENDING = "PENDING"
    const val ACCEPTED = "ACCEPTED"
    const val REJECTED = "REJECTED"
    const val CANCELLED = "CANCELLED"
    const val COMPLETED = "COMPLETED"
    // Set by the backend when nobody accepted before the departure time (Lifecycle.kt).
    const val EXPIRED = "EXPIRED"
    // Set by the backend (never by the app) when another trip the passenger has since had accepted makes this one
    // physically impossible. Final: it is never reactivated; the passenger can make a new request.
    const val CONFLICTED = "CONFLICTED"
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
    val matchScoreTotal: Double?,
    // When the driver actually finished the ride (the server's clock); null until then, for older rides, and for a ride the
    // system completed on its own - there is no observed time in those cases.
    val completedAt: Long? = null
)

data class RideRequestSummary(
    val requestId: String,
    // Null until a driver is attached - a standalone request (see StandaloneRequestDraft) starts
    // with no ride at all.
    val rideId: String?,
    val passengerId: String,
    val seatsRequested: Int,
    val status: String,
    val note: String?,
    val createdAt: Long,
    val respondedAt: Long?,
    // Only meaningful while rideId is null - the passenger's own desired route/time.
    val originLat: Double?,
    val originLng: Double?,
    val destinationLat: Double?,
    val destinationLng: Double?,
    val isAsap: Boolean,
    val desiredDepartureTime: Long?,
    val repeatDaysOfWeek: List<Int>,
    val repeatWeeks: Int?,
    val ride: RideSummary?,
    val passenger: UserSummary?,
    // Advisory match score against the viewer's own rides - only populated for a driver viewing
    // a standalone (rideId == null) request in their incoming list.
    val matchScoreTotal: Double?,
    // Only for a CONFLICTED request, and only the passenger's own: what made it impossible.
    val scheduleConflict: ScheduleConflict? = null
)

// A schedule WARNING (backend GET /schedule-warnings): a journey this person has already committed to - an ACCEPTED seat, or
// a ride they drive - that an earlier ride of theirs, finishing later than planned, may leave them too little time to
// reach. The journey itself is unchanged (still ACCEPTED / still scheduled); this is information, not a status. It is the
// signed-in person's own, never anyone else's. Times are epoch milliseconds, durations seconds.
data class ScheduleWarning(
    val warningId: String,
    val type: String,
    // WarningSubject.REQUEST (subjectId = request id) or WarningSubject.RIDE (subjectId = ride id).
    val subjectKind: String,
    val subjectId: String,
    val createdAt: Long,
    val previousRideId: String,
    val previousCompletedAt: Long,
    val previousPlannedArrivalTime: Long?,
    val fromPointName: String?,
    val toPointName: String?,
    val repositioningSeconds: Long,
    val transferBufferSeconds: Long,
    val earliestFeasibleDeparture: Long,
    val nextDepartureTime: Long
)

object WarningSubject {
    const val REQUEST = "REQUEST"
    const val RIDE = "RIDE"
}

// Everything a passenger fills in on the standalone Request-a-Ride flow - a route + time with no
// driver picked yet (POST /requests). Distinct from CreateRideDraft (a driver's Offer-a-Ride) and
// from the seats/note-only request made against an already-picked ride (POST /rides/{id}/requests,
// still driven by RideApiService.requestSeat).
data class StandaloneRequestDraft(
    val originLat: Double,
    val originLng: Double,
    val destinationLat: Double,
    val destinationLng: Double,
    val isAsap: Boolean,
    val desiredDepartureTime: Long?,
    val seatsRequested: Int,
    val note: String?,
    val repeatDaysOfWeek: List<Int> = emptyList(),
    val repeatWeeks: Int? = null,
    // Set by the ViewModel (not the form) so a retry of the same submission reuses the same id.
    val clientRequestId: String? = null
)

data class MessageSummary(
    val messageId: String,
    val rideId: String,
    val senderId: String,
    val body: String,
    val sentAt: Long,
    val senderName: String? = null
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

// What ONE date of a creation call came to (a repeating request, or a seat request across a recurring group, has one per
// date). created = a request was made; otherwise [code] says why not - never shown to the user as is.
data class OccurrenceOutcome(
    val departureTime: Long,
    val created: Boolean,
    val rideId: String?,
    val requestId: String?,
    val code: String?,
    val message: String?,
    val existingRequestId: String?,
    val scheduleConflict: ScheduleConflict?
)

// What a request-creation call came to. A one-off request is requested = created = 1; a repeating one has a line per date,
// including the ones that could not be made.
data class RequestSubmissionResult(
    val requested: Int,
    val created: Int,
    val rejected: Int,
    val requests: List<RideRequestSummary>,
    val occurrences: List<OccurrenceOutcome>
) {
    val isBatch: Boolean get() = requested > 1
}

data class RequestFunnelCounts(
    val pending: Int,
    val accepted: Int,
    val rejected: Int,
    val cancelled: Int,
    val completed: Int,
    val expired: Int = 0,
    val conflicted: Int = 0
) {
    val total: Int get() = pending + accepted + rejected + cancelled + completed + expired + conflicted
}

data class HotzoneEntry(val locationName: String, val count: Int)

data class RouteFrequency(val originLabel: String, val destinationLabel: String, val count: Int)

data class AnalyticsSummary(
    val passengerFunnel: RequestFunnelCounts,
    val driverFunnel: RequestFunnelCounts,
    val averageDriverResponseMinutes: Double?,
    val hotzones: List<HotzoneEntry>,
    val tripsByDayOfWeek: List<Int>,
    val mostUsedRoute: RouteFrequency?,
    val averageSeatFillRatePercent: Double?
)

// A successful sign-up/sign-in: the session token to send on every call, and who signed in.
data class AuthResult(val token: String, val user: UserAccountSummary)

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
