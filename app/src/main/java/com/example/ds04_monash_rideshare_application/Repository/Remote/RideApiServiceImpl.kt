package com.example.ds04_monash_rideshare_application.Repository.Remote

import com.example.ds04_monash_rideshare_application.Auth.AuthSession
import retrofit2.Call
import retrofit2.Callback as RetrofitCallback
import retrofit2.Response

// Real backend-backed implementation of RideApiService (see docs/BACKEND.md). Every method maps
// the backend's wire DTOs onto the domain models in RideModels.kt, and funnels every failure
// through NetworkErrorMapper so the UI only ever sees a friendly message.
class RideApiServiceImpl(private val api: BackendApi = RetrofitClient.backendApi) : RideApiService {

    private fun <T, R> Call<T>.dispatch(callback: RideApiService.Callback<R>, mapper: (T) -> R) {
        enqueue(object : RetrofitCallback<T> {
            override fun onResponse(call: Call<T>, response: Response<T>) {
                if (!response.isSuccessful) {
                    callback.onFailure(NetworkErrorMapper.fromErrorBody(response.code(), response.errorBody()))
                    return
                }
                val body = response.body()
                if (body == null) {
                    callback.onFailure(BackendException("Empty response from server"))
                    return
                }
                try {
                    callback.onSuccess(mapper(body))
                } catch (e: Exception) {
                    callback.onFailure(NetworkErrorMapper.fromThrowable(e))
                }
            }

            override fun onFailure(call: Call<T>, t: Throwable) {
                callback.onFailure(NetworkErrorMapper.fromThrowable(t))
            }
        })
    }

    override fun fetchMyProfile(callback: RideApiService.Callback<UserAccountSummary>) {
        api.getMyProfile().dispatch(callback, ::toUserAccountSummary)
    }

    override fun updateProfile(
        displayName: String,
        preferredName: String,
        passengerEnabled: Boolean,
        emergencyContactName: String?,
        emergencyContactPhone: String?,
        callback: RideApiService.Callback<UserAccountSummary>
    ) {
        val body = UpdateProfileRequestBody(
            displayName, preferredName,
            passengerEnabled = passengerEnabled,
            emergencyContactName = emergencyContactName,
            emergencyContactPhone = emergencyContactPhone
        )
        api.updateMyProfile(body).dispatch(callback, ::toUserAccountSummary)
    }

    override fun fetchOpenRides(callback: RideApiService.Callback<List<RideSummary>>) {
        api.getRides(true).dispatch(callback) { it.map(::toRideSummary) }
    }

    override fun searchRides(criteria: RideSearchCriteria, callback: RideApiService.Callback<List<RideSummary>>) {
        api.matchRides(
            originLat = criteria.originLat,
            originLng = criteria.originLng,
            destinationLat = criteria.destinationLat,
            destinationLng = criteria.destinationLng,
            departureTime = criteria.departureTime,
            seatsNeeded = criteria.seatsNeeded
        ).dispatch(callback) { response -> response.rides.map(::toRideSummary) }
    }

    override fun fetchRide(rideId: String, callback: RideApiService.Callback<RideSummary>) {
        api.getRide(rideId).dispatch(callback, ::toRideSummary)
    }

    override fun createRide(draft: CreateRideDraft, callback: RideApiService.Callback<RideSummary>) {
        val body = CreateRideRequestBody(
            originLat = draft.originLat,
            originLng = draft.originLng,
            destinationLat = draft.destinationLat,
            destinationLng = draft.destinationLng,
            distanceMeters = draft.distanceMeters,
            estimatedDurationSeconds = draft.estimatedDurationSeconds,
            departureTime = draft.departureTime,
            seatsTotal = draft.seatsTotal,
            notes = draft.notes,
            stops = draft.stops.map { CreateStopInputBody(it.lat, it.lng, it.label) },
            repeatDaysOfWeek = draft.repeatDaysOfWeek,
            repeatWeeks = draft.repeatWeeks
        )
        api.createRide(body).dispatch(callback, ::toRideSummary)
    }

    override fun cancelRide(rideId: String, callback: RideApiService.Callback<RideSummary>) {
        api.updateRide(rideId, UpdateRideRequestBody(status = "CANCELLED")).dispatch(callback, ::toRideSummary)
    }

    override fun completeRide(rideId: String, callback: RideApiService.Callback<RideSummary>) {
        api.updateRide(rideId, UpdateRideRequestBody(status = RideStatus.COMPLETED)).dispatch(callback, ::toRideSummary)
    }

    override fun setDriverEnRoute(rideId: String, enRoute: Boolean, callback: RideApiService.Callback<RideSummary>) {
        api.updateRide(rideId, UpdateRideRequestBody(driverEnRoute = enRoute)).dispatch(callback, ::toRideSummary)
    }

    override fun fetchMyDriverRides(callback: RideApiService.Callback<List<RideSummary>>) {
        val userId = AuthSession.currentUserId
        if (userId == null) {
            callback.onFailure(BackendException("Please sign in again."))
            return
        }
        api.getRidesForUser(userId).dispatch(callback) { it.map(::toRideSummary) }
    }

    override fun fetchTripHistory(callback: RideApiService.Callback<List<RideSummary>>) {
        api.getTripHistory().dispatch(callback) { it.map(::toRideSummary) }
    }

    override fun requestSeat(
        rideId: String,
        seats: Int,
        note: String?,
        applyToRecurringGroup: Boolean,
        callback: RideApiService.Callback<List<RideRequestSummary>>
    ) {
        api.requestSeat(rideId, CreateSeatRequestBody(seats, note, applyToRecurringGroup))
            .dispatch(callback) { it.map(::toRideRequestSummary) }
    }

    override fun createStandaloneRequest(draft: StandaloneRequestDraft, callback: RideApiService.Callback<RideRequestSummary>) {
        val body = CreateStandaloneRequestBody(
            originLat = draft.originLat, originLng = draft.originLng,
            destinationLat = draft.destinationLat, destinationLng = draft.destinationLng,
            isAsap = draft.isAsap, desiredDepartureTime = draft.desiredDepartureTime,
            seatsRequested = draft.seatsRequested, note = draft.note,
            repeatDaysOfWeek = draft.repeatDaysOfWeek, repeatWeeks = draft.repeatWeeks
        )
        api.createStandaloneRequest(body).dispatch(callback, ::toRideRequestSummary)
    }

    override fun fetchIncomingRequests(callback: RideApiService.Callback<List<RideRequestSummary>>) {
        api.getRequestsForMyRides().dispatch(callback) { it.map(::toRideRequestSummary) }
    }

    override fun fetchMyRequests(callback: RideApiService.Callback<List<RideRequestSummary>>) {
        api.getMyRequests().dispatch(callback) { it.map(::toRideRequestSummary) }
    }

    override fun respondToRequest(
        requestId: String,
        newStatus: String,
        targetRideId: String?,
        newRideSeatsTotal: Int?,
        cancelMyConflictingRequests: Boolean,
        callback: RideApiService.Callback<RideRequestSummary>
    ) {
        api.updateRequestStatus(
            requestId,
            UpdateRequestStatusBody(newStatus, targetRideId, newRideSeatsTotal, cancelMyConflictingRequests)
        ).dispatch(callback, ::toRideRequestSummary)
    }

    override fun undoAccept(requestId: String, callback: RideApiService.Callback<RideRequestSummary>) {
        api.undoAccept(requestId).dispatch(callback, ::toRideRequestSummary)
    }

    override fun fetchMessages(rideId: String, callback: RideApiService.Callback<List<MessageSummary>>) {
        api.getMessages(rideId).dispatch(callback) { it.map(::toMessageSummary) }
    }

    override fun sendMessage(rideId: String, body: String, callback: RideApiService.Callback<MessageSummary>) {
        api.sendMessage(rideId, SendMessageRequestBody(body)).dispatch(callback, ::toMessageSummary)
    }

    override fun fetchDashboard(callback: RideApiService.Callback<DashboardSummary>) {
        api.getDashboard().dispatch(callback, ::toDashboardSummary)
    }

    override fun fetchAnalytics(callback: RideApiService.Callback<AnalyticsSummary>) {
        api.getAnalytics().dispatch(callback, ::toAnalyticsSummary)
    }

    override fun fetchLeaderboard(limit: Int, callback: RideApiService.Callback<LeaderboardResult>) {
        api.getLeaderboard(limit).dispatch(callback) { dto ->
            LeaderboardResult(dto.top.map(::toLeaderboardEntry), dto.me?.let(::toLeaderboardEntry))
        }
    }

    override fun submitRating(rideId: String, ratedUserId: String, stars: Int, tags: List<String>, comment: String?, callback: RideApiService.Callback<RatingSummary>) {
        api.submitRating(rideId, SubmitRatingRequestBody(ratedUserId, stars, tags, comment)).dispatch(callback, ::toRatingSummary)
    }

    override fun fetchRatings(rideId: String, callback: RideApiService.Callback<List<RatingSummary>>) {
        api.getRatings(rideId).dispatch(callback) { it.map(::toRatingSummary) }
    }

    override fun fetchFavorites(callback: RideApiService.Callback<List<FavoriteDriverSummary>>) {
        api.getFavorites().dispatch(callback) { it.map(::toFavoriteDriverSummary) }
    }

    override fun addFavorite(driverId: String, callback: RideApiService.Callback<Boolean>) {
        api.addFavorite(driverId).dispatch(callback) { it["favorited"] ?: true }
    }

    override fun removeFavorite(driverId: String, callback: RideApiService.Callback<Boolean>) {
        api.removeFavorite(driverId).dispatch(callback) { it["favorited"] ?: false }
    }

    override fun fetchMyVehicles(callback: RideApiService.Callback<List<VehicleSummary>>) {
        api.getMyVehicles().dispatch(callback) { it.map(::toVehicleSummary) }
    }

    override fun createVehicle(draft: CreateVehicleDraft, callback: RideApiService.Callback<VehicleSummary>) {
        val body = CreateVehicleRequestBody(
            year = draft.year,
            make = draft.make,
            model = draft.model,
            variant = draft.variant,
            registrationMasked = draft.registrationMasked,
            seatCapacity = draft.seatCapacity,
            fuelType = draft.fuelType,
            vehicleType = draft.vehicleType,
            colour = draft.colour
        )
        api.createVehicle(body).dispatch(callback, ::toVehicleSummary)
    }

    override fun updateVehicle(vehicleId: String, draft: CreateVehicleDraft, callback: RideApiService.Callback<VehicleSummary>) {
        val body = UpdateVehicleRequestBody(
            year = draft.year,
            make = draft.make,
            model = draft.model,
            variant = draft.variant,
            registrationMasked = draft.registrationMasked,
            seatCapacity = draft.seatCapacity,
            fuelType = draft.fuelType,
            vehicleType = draft.vehicleType,
            colour = draft.colour
        )
        api.updateVehicle(vehicleId, body).dispatch(callback, ::toVehicleSummary)
    }

    override fun deleteVehicle(vehicleId: String, callback: RideApiService.Callback<Boolean>) {
        api.deleteVehicle(vehicleId).dispatch(callback) { it["deleted"] ?: false }
    }

    override fun signUp(displayName: String, email: String, password: String, callback: RideApiService.Callback<AuthResult>) {
        api.register(RegisterRequestBody(displayName, email, password)).dispatch(callback, ::toAuthResult)
    }

    override fun signIn(email: String, password: String, callback: RideApiService.Callback<AuthResult>) {
        api.login(LoginRequestBody(email, password)).dispatch(callback, ::toAuthResult)
    }

    override fun signOut(token: String, callback: RideApiService.Callback<Boolean>) {
        api.logout("Bearer $token").dispatch(callback) { true }
    }

    override fun changePassword(currentPassword: String, newPassword: String, callback: RideApiService.Callback<Boolean>) {
        api.changePassword(ChangePasswordRequestBody(currentPassword, newPassword)).dispatch(callback) { true }
    }

    override fun deleteAccount(password: String, callback: RideApiService.Callback<Boolean>) {
        api.deleteAccount(DeleteAccountRequestBody(password)).dispatch(callback) { true }
    }

    private fun toAuthResult(dto: AuthResponseDto) = AuthResult(dto.token, toUserAccountSummary(dto.user))

    private fun toUserAccountSummary(dto: UserDto) = UserAccountSummary(
        userId = dto.userId,
        displayName = dto.displayName,
        preferredName = dto.preferredName,
        emailAccount = dto.emailAccount,
        phoneNumber = dto.phoneNumber,
        photoRef = dto.photoRef,
        passengerEnabled = dto.passengerEnabled,
        emergencyContactName = dto.emergencyContactName,
        emergencyContactPhone = dto.emergencyContactPhone
    )

    private fun toUserSummary(dto: UserSummaryDto) =
        UserSummary(dto.userId, dto.displayName, dto.photoRef, dto.phoneNumber, dto.averageRating, dto.ratingCount)

    private fun toVehicleSummary(dto: VehicleDto) = VehicleSummary(
        dto.vehicleId, dto.year, dto.make, dto.model, dto.variant, dto.registrationMasked, dto.fuelType, dto.seatCapacity,
        dto.vehicleType, dto.colour
    )

    private fun toRideStop(dto: RideStopDto) = RideStop(dto.stopId, dto.sequence, dto.lat, dto.lng, dto.label)

    private fun toRideSummary(dto: RideDto) = RideSummary(
        rideId = dto.rideId,
        driverId = dto.driverId,
        vehicleId = dto.vehicleId,
        originLat = dto.originLat,
        originLng = dto.originLng,
        destinationLat = dto.destinationLat,
        destinationLng = dto.destinationLng,
        distanceMeters = dto.distanceMeters,
        estimatedDurationSeconds = dto.estimatedDurationSeconds,
        departureTime = dto.departureTime,
        seatsTotal = dto.seatsTotal,
        seatsAvailable = dto.seatsAvailable,
        notes = dto.notes,
        status = dto.status,
        createdAt = dto.createdAt,
        driverEnRoute = dto.driverEnRoute,
        recurringGroupId = dto.recurringGroupId,
        stops = dto.stops.map(::toRideStop),
        driver = dto.driver?.let(::toUserSummary),
        vehicle = dto.vehicle?.let(::toVehicleSummary),
        matchScoreTotal = dto.matchScore?.total
    )

    private fun toRideRequestSummary(dto: RideRequestDto) = RideRequestSummary(
        requestId = dto.requestId,
        rideId = dto.rideId,
        passengerId = dto.passengerId,
        seatsRequested = dto.seatsRequested,
        status = dto.status,
        note = dto.note,
        createdAt = dto.createdAt,
        respondedAt = dto.respondedAt,
        originLat = dto.originLat,
        originLng = dto.originLng,
        destinationLat = dto.destinationLat,
        destinationLng = dto.destinationLng,
        isAsap = dto.isAsap,
        desiredDepartureTime = dto.desiredDepartureTime,
        repeatDaysOfWeek = dto.repeatDaysOfWeek,
        repeatWeeks = dto.repeatWeeks,
        ride = dto.ride?.let(::toRideSummary),
        passenger = dto.passenger?.let(::toUserSummary),
        matchScoreTotal = dto.matchScore?.total
    )

    private fun toMessageSummary(dto: MessageDto) = MessageSummary(dto.messageId, dto.rideId, dto.senderId, dto.body, dto.sentAt, dto.senderName)

    private fun toDashboardSummary(dto: DashboardResponseDto) = DashboardSummary(
        totalRidesCompleted = dto.totalRidesCompleted,
        totalCo2AvoidedKg = dto.totalCo2AvoidedKg,
        totalFuelSavedLitres = dto.totalFuelSavedLitres,
        monthlyTripSummary = dto.monthlyTripSummary.map { MonthlyTripSummary(it.monthLabel, it.tripsCompleted, it.co2AvoidedKg) }
    )

    private fun toLeaderboardEntry(dto: LeaderboardEntryDto) = LeaderboardEntry(dto.userId, dto.displayName, dto.totalCo2AvoidedKg, dto.rank)

    private fun toFunnelCounts(dto: RequestFunnelCountsDto) =
        RequestFunnelCounts(dto.pending, dto.accepted, dto.rejected, dto.cancelled, dto.completed, dto.expired)

    private fun toAnalyticsSummary(dto: AnalyticsResponseDto) = AnalyticsSummary(
        passengerFunnel = toFunnelCounts(dto.passengerFunnel),
        driverFunnel = toFunnelCounts(dto.driverFunnel),
        averageDriverResponseMinutes = dto.averageDriverResponseMinutes,
        hotzones = dto.hotzones.map { HotzoneEntry(it.locationName, it.count) },
        tripsByDayOfWeek = dto.tripsByDayOfWeek,
        mostUsedRoute = dto.mostUsedRoute?.let { RouteFrequency(it.originLabel, it.destinationLabel, it.count) },
        averageSeatFillRatePercent = dto.averageSeatFillRatePercent
    )

    private fun toRatingSummary(dto: RatingDto) = RatingSummary(
        dto.ratingId, dto.rideId, dto.raterId, dto.ratedUserId, dto.stars, dto.tags, dto.comment, dto.createdAt
    )

    private fun toFavoriteDriverSummary(dto: FavoriteDriverDto) = FavoriteDriverSummary(dto.driverId, dto.displayName, dto.photoRef, dto.addedAt)
}
