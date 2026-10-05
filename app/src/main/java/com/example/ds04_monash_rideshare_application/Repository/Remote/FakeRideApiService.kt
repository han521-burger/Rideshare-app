package com.example.ds04_monash_rideshare_application.Repository.Remote

// Offline/dev stand-in for the real backend, kept for UI work without a running server. Mutating
// calls fail with a clear message rather than silently succeeding, so it's obvious nothing was
// actually persisted; use RideApiServiceImpl (backed by the backend/ module) for a working
// end-to-end flow.
class FakeRideApiService : RideApiService {
    private val unavailable = UnsupportedOperationException("No backend configured - run ./gradlew :backend:run and rebuild")

    override fun signUp(displayName: String, email: String, password: String, callback: RideApiService.Callback<AuthResult>) {
        callback.onFailure(unavailable)
    }

    override fun signIn(email: String, password: String, callback: RideApiService.Callback<AuthResult>) {
        callback.onFailure(unavailable)
    }

    override fun signOut(token: String, callback: RideApiService.Callback<Boolean>) {
        callback.onSuccess(true)
    }

    override fun fetchMyProfile(callback: RideApiService.Callback<UserAccountSummary>) {
        callback.onFailure(unavailable)
    }

    override fun updateProfile(
        displayName: String,
        preferredName: String,
        passengerEnabled: Boolean,
        emergencyContactName: String?,
        emergencyContactPhone: String?,
        callback: RideApiService.Callback<UserAccountSummary>
    ) {
        callback.onFailure(unavailable)
    }

    override fun fetchOpenRides(callback: RideApiService.Callback<List<RideSummary>>) {
        callback.onSuccess(emptyList())
    }

    override fun searchRides(criteria: RideSearchCriteria, callback: RideApiService.Callback<List<RideSummary>>) {
        callback.onSuccess(emptyList())
    }

    override fun fetchRide(rideId: String, callback: RideApiService.Callback<RideSummary>) {
        callback.onFailure(unavailable)
    }

    override fun createRide(draft: CreateRideDraft, callback: RideApiService.Callback<RideSummary>) {
        callback.onFailure(unavailable)
    }

    override fun cancelRide(rideId: String, callback: RideApiService.Callback<RideSummary>) {
        callback.onFailure(unavailable)
    }

    override fun completeRide(rideId: String, callback: RideApiService.Callback<RideSummary>) {
        callback.onFailure(unavailable)
    }

    override fun setDriverEnRoute(rideId: String, enRoute: Boolean, callback: RideApiService.Callback<RideSummary>) {
        callback.onFailure(unavailable)
    }

    override fun fetchMyDriverRides(callback: RideApiService.Callback<List<RideSummary>>) {
        callback.onSuccess(emptyList())
    }

    override fun fetchTripHistory(callback: RideApiService.Callback<List<RideSummary>>) {
        callback.onSuccess(emptyList())
    }

    override fun requestSeat(
        rideId: String,
        seats: Int,
        note: String?,
        applyToRecurringGroup: Boolean,
        callback: RideApiService.Callback<List<RideRequestSummary>>
    ) {
        callback.onFailure(unavailable)
    }

    override fun createStandaloneRequest(draft: StandaloneRequestDraft, callback: RideApiService.Callback<RideRequestSummary>) {
        callback.onFailure(unavailable)
    }

    override fun fetchIncomingRequests(callback: RideApiService.Callback<List<RideRequestSummary>>) {
        callback.onSuccess(emptyList())
    }

    override fun fetchMyRequests(callback: RideApiService.Callback<List<RideRequestSummary>>) {
        callback.onSuccess(emptyList())
    }

    override fun respondToRequest(
        requestId: String,
        newStatus: String,
        targetRideId: String?,
        newRideSeatsTotal: Int?,
        cancelMyConflictingRequests: Boolean,
        callback: RideApiService.Callback<RideRequestSummary>
    ) {
        callback.onFailure(unavailable)
    }

    override fun undoAccept(requestId: String, callback: RideApiService.Callback<RideRequestSummary>) {
        callback.onFailure(unavailable)
    }

    override fun fetchMessages(rideId: String, callback: RideApiService.Callback<List<MessageSummary>>) {
        callback.onSuccess(emptyList())
    }

    override fun sendMessage(rideId: String, body: String, callback: RideApiService.Callback<MessageSummary>) {
        callback.onFailure(unavailable)
    }

    override fun fetchDashboard(callback: RideApiService.Callback<DashboardSummary>) {
        callback.onSuccess(DashboardSummary(0, 0.0, 0.0, emptyList()))
    }

    override fun fetchAnalytics(callback: RideApiService.Callback<AnalyticsSummary>) {
        val emptyFunnel = RequestFunnelCounts(0, 0, 0, 0, 0, expired = 0)
        callback.onSuccess(AnalyticsSummary(emptyFunnel, emptyFunnel, null, emptyList(), List(7) { 0 }, null, null))
    }

    override fun fetchLeaderboard(limit: Int, callback: RideApiService.Callback<LeaderboardResult>) {
        callback.onSuccess(LeaderboardResult(emptyList(), null))
    }

    override fun submitRating(rideId: String, ratedUserId: String, stars: Int, tags: List<String>, comment: String?, callback: RideApiService.Callback<RatingSummary>) {
        callback.onFailure(unavailable)
    }

    override fun fetchRatings(rideId: String, callback: RideApiService.Callback<List<RatingSummary>>) {
        callback.onSuccess(emptyList())
    }

    override fun fetchFavorites(callback: RideApiService.Callback<List<FavoriteDriverSummary>>) {
        callback.onSuccess(emptyList())
    }

    override fun addFavorite(driverId: String, callback: RideApiService.Callback<Boolean>) {
        callback.onFailure(unavailable)
    }

    override fun removeFavorite(driverId: String, callback: RideApiService.Callback<Boolean>) {
        callback.onFailure(unavailable)
    }

    override fun fetchMyVehicles(callback: RideApiService.Callback<List<VehicleSummary>>) {
        callback.onSuccess(emptyList())
    }

    override fun createVehicle(draft: CreateVehicleDraft, callback: RideApiService.Callback<VehicleSummary>) {
        callback.onFailure(unavailable)
    }

    override fun updateVehicle(vehicleId: String, draft: CreateVehicleDraft, callback: RideApiService.Callback<VehicleSummary>) {
        callback.onFailure(unavailable)
    }

    override fun deleteVehicle(vehicleId: String, callback: RideApiService.Callback<Boolean>) {
        callback.onFailure(unavailable)
    }
}
