package com.example.ds04_monash_rideshare_application.Repository.Remote

// The single surface the app talks to the backend through (docs/BACKEND.md).
// RideApiServiceImpl is the real Retrofit-backed implementation; FakeRideApiService is an
// offline/dev stand-in that "succeeds" with empty results.
interface RideApiService {
    interface Callback<T> {
        fun onSuccess(result: T)
        fun onFailure(error: Exception)
    }

    fun fetchMyProfile(callback: Callback<UserAccountSummary>)
    fun updateProfile(
        displayName: String,
        preferredName: String,
        passengerEnabled: Boolean,
        emergencyContactName: String?,
        emergencyContactPhone: String?,
        callback: Callback<UserAccountSummary>
    )

    fun fetchOpenRides(callback: Callback<List<RideSummary>>)
    fun searchRides(criteria: RideSearchCriteria, callback: Callback<List<RideSummary>>)
    fun fetchRide(rideId: String, callback: Callback<RideSummary>)
    fun createRide(draft: CreateRideDraft, callback: Callback<RideSummary>)
    fun cancelRide(rideId: String, callback: Callback<RideSummary>)
    fun completeRide(rideId: String, callback: Callback<RideSummary>)
    fun setDriverEnRoute(rideId: String, enRoute: Boolean, callback: Callback<RideSummary>)
    fun fetchMyDriverRides(callback: Callback<List<RideSummary>>)
    fun fetchTripHistory(callback: Callback<List<RideSummary>>)

    fun requestSeat(
        rideId: String,
        seats: Int,
        note: String?,
        applyToRecurringGroup: Boolean,
        callback: Callback<List<RideRequestSummary>>
    )
    fun createStandaloneRequest(draft: StandaloneRequestDraft, callback: Callback<RideRequestSummary>)
    fun fetchIncomingRequests(callback: Callback<List<RideRequestSummary>>)
    fun fetchMyRequests(callback: Callback<List<RideRequestSummary>>)
    fun respondToRequest(
        requestId: String,
        newStatus: String,
        targetRideId: String?,
        newRideSeatsTotal: Int?,
        callback: Callback<RideRequestSummary>
    )

    fun fetchMessages(rideId: String, callback: Callback<List<MessageSummary>>)
    fun sendMessage(rideId: String, body: String, callback: Callback<MessageSummary>)

    fun fetchDashboard(callback: Callback<DashboardSummary>)
    fun fetchLeaderboard(limit: Int, callback: Callback<LeaderboardResult>)

    fun submitRating(rideId: String, ratedUserId: String, stars: Int, tags: List<String>, comment: String?, callback: Callback<RatingSummary>)
    fun fetchRatings(rideId: String, callback: Callback<List<RatingSummary>>)

    fun fetchFavorites(callback: Callback<List<FavoriteDriverSummary>>)
    fun addFavorite(driverId: String, callback: Callback<Boolean>)
    fun removeFavorite(driverId: String, callback: Callback<Boolean>)
}
