package com.example.ds04_monash_rideshare_application.Repository.Remote;

import java.util.List;

import com.example.ds04_monash_rideshare_application.Database.Messaging.Message;
import com.example.ds04_monash_rideshare_application.Database.Passenger.SeatRequest;
import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOffer;
import com.example.ds04_monash_rideshare_application.Database.RideShare.RouteSnapshot;
import com.example.ds04_monash_rideshare_application.Database.User.UserProfile;

//The "API" side of the Repository diagram - talks to the remote server. RideApiServiceImpl is the
//real Retrofit/OkHttp-backed implementation talking to the backend/ module; FakeRideApiService
//remains available as an offline/dev stand-in. Repository code doesn't change either way.
public interface RideApiService {
    interface Callback<T> {
        void onSuccess(T result);
        void onFailure(Exception error);
    }

    void fetchRides(Callback<List<RideOffer>> callback);
    void fetchUserProfile(String userId, Callback<UserProfile> callback);
    void fetchRouteSummaries(String rideId, Callback<List<RouteSnapshot>> callback);

    void updateUserProfile(String userId, String displayName, String preferredName, boolean passengerEnabled, Callback<UserProfile> callback);

    void searchRides(RideSearchCriteria criteria, Callback<List<RankedRide>> callback);
    void fetchRide(String rideId, Callback<RideOffer> callback);
    void createRide(CreateRideDraft draft, Callback<RideOffer> callback);
    void cancelRide(String rideId, Callback<RideOffer> callback);
    void fetchMyDriverRides(Callback<List<RideOffer>> callback);
    void fetchTripHistory(Callback<List<RideOffer>> callback);

    void requestSeat(String rideId, int seatsRequested, Callback<SeatRequest> callback);
    void fetchRequestsForMyRides(Callback<List<SeatRequest>> callback);
    void fetchMyRequests(Callback<List<SeatRequest>> callback);
    void respondToRequest(String requestId, String newStatus, Callback<SeatRequest> callback);

    void fetchMessages(String rideId, Callback<List<Message>> callback);
    void sendMessage(String rideId, String body, Callback<Message> callback);

    void fetchDashboard(Callback<DashboardSummary> callback);
}
