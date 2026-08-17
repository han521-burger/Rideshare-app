package com.example.ds04_monash_rideshare_application.Repository.Remote

import com.example.ds04_monash_rideshare_application.Auth.AuthSession
import com.example.ds04_monash_rideshare_application.Database.Messaging.Message
import com.example.ds04_monash_rideshare_application.Database.Passenger.SeatRequest
import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOffer
import com.example.ds04_monash_rideshare_application.Database.RideShare.RouteSnapshot
import com.example.ds04_monash_rideshare_application.Database.User.UserProfile
import retrofit2.Call
import retrofit2.Callback as RetrofitCallback
import retrofit2.Response

// Real backend-backed implementation of RideApiService (see docs/BACKEND.md). Every method maps
// the backend's wire DTOs onto the Room @Entity types the rest of the app already works with, and
// funnels every failure through NetworkErrorMapper so the UI only ever sees a friendly message.
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

    override fun fetchRides(callback: RideApiService.Callback<List<RideOffer>>) {
        api.getRides(true).dispatch(callback) { it.map(::toRideOffer) }
    }

    override fun fetchUserProfile(userId: String, callback: RideApiService.Callback<UserProfile>) {
        api.getMyProfile().dispatch(callback, ::toUserProfile)
    }

    override fun fetchRouteSummaries(rideId: String, callback: RideApiService.Callback<List<RouteSnapshot>>) {
        api.getRide(rideId).dispatch(callback) { dto -> listOf(toRouteSnapshot(dto)) }
    }

    override fun updateUserProfile(
        userId: String,
        displayName: String,
        preferredName: String,
        passengerEnabled: Boolean,
        callback: RideApiService.Callback<UserProfile>
    ) {
        val body = UpdateProfileRequestBody(displayName, preferredName, passengerEnabled = passengerEnabled)
        api.updateMyProfile(body).dispatch(callback, ::toUserProfile)
    }

    override fun searchRides(criteria: RideSearchCriteria, callback: RideApiService.Callback<List<RankedRide>>) {
        api.matchRides(
            originLat = criteria.originLat,
            originLng = criteria.originLng,
            destinationLat = criteria.destinationLat,
            destinationLng = criteria.destinationLng,
            departureTime = criteria.departureTime,
            seatsNeeded = criteria.seatsNeeded
        ).dispatch(callback) { response ->
            response.rides.map { dto ->
                RankedRide(toRideOffer(dto), dto.matchScore?.total ?: 0.0, response.usedFallback)
            }
        }
    }

    override fun fetchRide(rideId: String, callback: RideApiService.Callback<RideOffer>) {
        api.getRide(rideId).dispatch(callback, ::toRideOffer)
    }

    override fun createRide(draft: CreateRideDraft, callback: RideApiService.Callback<RideOffer>) {
        val body = CreateRideRequestBody(
            vehicleId = draft.vehicleId,
            originLat = draft.originLat,
            originLng = draft.originLng,
            destinationLat = draft.destinationLat,
            destinationLng = draft.destinationLng,
            distanceMeters = draft.distanceMeters,
            estimatedDurationSeconds = draft.estimatedDurationSeconds,
            departureTime = draft.departureTime,
            seatsTotal = draft.seatsTotal,
            notes = draft.notes
        )
        api.createRide(body).dispatch(callback, ::toRideOffer)
    }

    override fun cancelRide(rideId: String, callback: RideApiService.Callback<RideOffer>) {
        api.updateRide(rideId, UpdateRideRequestBody(status = "CANCELLED")).dispatch(callback, ::toRideOffer)
    }

    override fun fetchMyDriverRides(callback: RideApiService.Callback<List<RideOffer>>) {
        val userId = AuthSession.currentUserId
        if (userId == null) {
            callback.onFailure(BackendException("Please sign in again."))
            return
        }
        api.getRidesForUser(userId).dispatch(callback) { it.map(::toRideOffer) }
    }

    override fun fetchTripHistory(callback: RideApiService.Callback<List<RideOffer>>) {
        api.getTripHistory().dispatch(callback) { it.map(::toRideOffer) }
    }

    override fun requestSeat(rideId: String, seatsRequested: Int, callback: RideApiService.Callback<SeatRequest>) {
        api.requestSeat(rideId, CreateSeatRequestBody(seatsRequested)).dispatch(callback, ::toSeatRequest)
    }

    override fun fetchRequestsForMyRides(callback: RideApiService.Callback<List<RequestWithRide>>) {
        api.getRequestsForMyRides().dispatch(callback) { it.map(::toRequestWithRide) }
    }

    override fun fetchMyRequests(callback: RideApiService.Callback<List<RequestWithRide>>) {
        api.getMyRequests().dispatch(callback) { it.map(::toRequestWithRide) }
    }

    override fun respondToRequest(requestId: String, newStatus: String, callback: RideApiService.Callback<SeatRequest>) {
        api.updateRequestStatus(requestId, UpdateRequestStatusBody(newStatus)).dispatch(callback, ::toSeatRequest)
    }

    override fun fetchMessages(rideId: String, callback: RideApiService.Callback<List<Message>>) {
        api.getMessages(rideId).dispatch(callback) { it.map { dto -> toMessage(dto) } }
    }

    override fun sendMessage(rideId: String, body: String, callback: RideApiService.Callback<Message>) {
        api.sendMessage(rideId, SendMessageRequestBody(body)).dispatch(callback, ::toMessage)
    }

    override fun fetchDashboard(callback: RideApiService.Callback<DashboardSummary>) {
        api.getDashboard().dispatch(callback) { dto ->
            DashboardSummary(
                dto.totalRidesCompleted,
                dto.totalCo2AvoidedKg,
                dto.monthlyTripSummary.map { DashboardSummary.MonthlySummary(it.monthLabel, it.tripsCompleted, it.co2AvoidedKg) }
            )
        }
    }

    private fun toUserProfile(dto: UserDto) = UserProfile(
        dto.userId, dto.displayName, dto.preferredName, dto.phoneNumber, dto.photoRef, dto.passengerEnabled
    )

    private fun toRideOffer(dto: RideDto) = RideOffer(
        dto.rideId, dto.driverId, dto.vehicleId,
        dto.originLat, dto.originLng, dto.destinationLat, dto.destinationLng,
        dto.departureTime, dto.seatsAvailable, dto.status, dto.createdAt
    )

    private fun toRouteSnapshot(dto: RideDto) = RouteSnapshot(
        "route-${dto.rideId}",
        dto.rideId,
        "${dto.originLat},${dto.originLng}",
        "${dto.destinationLat},${dto.destinationLng}",
        dto.distanceMeters.toLong(),
        dto.estimatedDurationSeconds,
        "yandex",
        dto.createdAt
    )

    private fun toSeatRequest(dto: RideRequestDto) = SeatRequest(
        dto.requestId, dto.rideId, dto.passengerId, dto.seatsRequested, dto.status, dto.createdAt
    )

    private fun toRequestWithRide(dto: RideRequestDto) = RequestWithRide(toSeatRequest(dto), dto.ride?.let(::toRideOffer))

    private fun toMessage(dto: MessageDto) = Message(
        dto.messageId, dto.rideId, dto.senderId, dto.body, dto.sentAt, "SENT"
    )
}
