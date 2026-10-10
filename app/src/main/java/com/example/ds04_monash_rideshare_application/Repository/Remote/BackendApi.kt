package com.example.ds04_monash_rideshare_application.Repository.Remote

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

// Thin 1:1 mapping onto the backend's routes (backend/.../routes/*.kt, documented in
// docs/BACKEND.md). RideApiServiceImpl is the only caller - it maps these DTOs to/from the
// domain models in RideModels.kt the rest of the app works with.
interface BackendApi {
    @POST("auth/register")
    fun register(@Body body: RegisterRequestBody): Call<AuthResponseDto>

    @POST("auth/login")
    fun login(@Body body: LoginRequestBody): Call<AuthResponseDto>

    // Token passed explicitly: the app clears its in-memory session right away on sign-out,
    // so the interceptor may no longer have it by the time this request goes out.
    @POST("auth/change-password")
    fun changePassword(@Body body: ChangePasswordRequestBody): Call<Map<String, Boolean>>

    @POST("auth/delete-account")
    fun deleteAccount(@Body body: DeleteAccountRequestBody): Call<Map<String, Boolean>>

    @POST("auth/logout")
    fun logout(@Header("Authorization") authorization: String): Call<Map<String, Boolean>>

    @GET("users/me")
    fun getMyProfile(): Call<UserDto>

    @PUT("users/me")
    fun updateMyProfile(@Body body: UpdateProfileRequestBody): Call<UserDto>

    @GET("rides")
    fun getRides(
        @Query("availableOnly") availableOnly: Boolean = true
    ): Call<List<RideDto>>

    @GET("rides/match")
    fun matchRides(
        @Query("originLat") originLat: Double,
        @Query("originLng") originLng: Double,
        @Query("destinationLat") destinationLat: Double,
        @Query("destinationLng") destinationLng: Double,
        @Query("departureTime") departureTime: Long,
        @Query("seatsNeeded") seatsNeeded: Int
    ): Call<MatchSearchResponse>

    @POST("rides")
    fun createRide(@Body body: CreateRideRequestBody): Call<RideDto>

    @GET("rides/{id}")
    fun getRide(@Path("id") rideId: String): Call<RideDto>

    @PUT("rides/{id}")
    fun updateRide(@Path("id") rideId: String, @Body body: UpdateRideRequestBody): Call<RideDto>

    @DELETE("rides/{id}")
    fun deleteRide(@Path("id") rideId: String): Call<Map<String, Boolean>>

    @GET("users/{id}/rides")
    fun getRidesForUser(@Path("id") userId: String): Call<List<RideDto>>

    @GET("users/me/trip-history")
    fun getTripHistory(): Call<List<RideDto>>

    @POST("rides/{rideId}/requests")
    fun requestSeat(@Path("rideId") rideId: String, @Body body: CreateSeatRequestBody): Call<CreateRequestsResultDto>

    @POST("requests")
    fun createStandaloneRequest(@Body body: CreateStandaloneRequestBody): Call<CreateRequestsResultDto>

    @GET("requests")
    fun getRequestsForMyRides(): Call<List<RideRequestDto>>

    @GET("requests/mine")
    fun getMyRequests(): Call<List<RideRequestDto>>

    @PUT("requests/{requestId}")
    fun updateRequestStatus(@Path("requestId") requestId: String, @Body body: UpdateRequestStatusBody): Call<RideRequestDto>

    @POST("requests/{requestId}/undo-accept")
    fun undoAccept(@Path("requestId") requestId: String): Call<RideRequestDto>

    // The signed-in user's own schedule warnings - private to them (backend ScheduleWarningRoutes.kt).
    @GET("schedule-warnings")
    fun getScheduleWarnings(): Call<List<ScheduleWarningDto>>

    @GET("rides/{rideId}/messages")
    fun getMessages(@Path("rideId") rideId: String): Call<List<MessageDto>>

    @POST("rides/{rideId}/messages")
    fun sendMessage(@Path("rideId") rideId: String, @Body body: SendMessageRequestBody): Call<MessageDto>

    @GET("users/me/dashboard")
    fun getDashboard(): Call<DashboardResponseDto>

    @GET("users/me/analytics")
    fun getAnalytics(): Call<AnalyticsResponseDto>

    @GET("leaderboard")
    fun getLeaderboard(@Query("limit") limit: Int = 10): Call<LeaderboardResponseDto>

    @POST("rides/{rideId}/ratings")
    fun submitRating(@Path("rideId") rideId: String, @Body body: SubmitRatingRequestBody): Call<RatingDto>

    @GET("rides/{rideId}/ratings")
    fun getRatings(@Path("rideId") rideId: String): Call<List<RatingDto>>

    @GET("favorites")
    fun getFavorites(): Call<List<FavoriteDriverDto>>

    @POST("favorites/{driverId}")
    fun addFavorite(@Path("driverId") driverId: String): Call<Map<String, Boolean>>

    @DELETE("favorites/{driverId}")
    fun removeFavorite(@Path("driverId") driverId: String): Call<Map<String, Boolean>>

    @GET("vehicles/mine")
    fun getMyVehicles(): Call<List<VehicleDto>>

    @POST("vehicles")
    fun createVehicle(@Body body: CreateVehicleRequestBody): Call<VehicleDto>

    @PUT("vehicles/{id}")
    fun updateVehicle(@Path("id") vehicleId: String, @Body body: UpdateVehicleRequestBody): Call<VehicleDto>

    @DELETE("vehicles/{id}")
    fun deleteVehicle(@Path("id") vehicleId: String): Call<Map<String, Boolean>>
}
