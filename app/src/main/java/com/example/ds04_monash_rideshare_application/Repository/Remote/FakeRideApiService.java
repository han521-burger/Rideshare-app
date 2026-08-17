package com.example.ds04_monash_rideshare_application.Repository.Remote;

import java.util.Collections;
import java.util.List;

import com.example.ds04_monash_rideshare_application.Database.Messaging.Message;
import com.example.ds04_monash_rideshare_application.Database.Passenger.SeatRequest;
import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOffer;
import com.example.ds04_monash_rideshare_application.Database.RideShare.RouteSnapshot;
import com.example.ds04_monash_rideshare_application.Database.User.UserProfile;

//Offline/dev stand-in for the real backend, kept for UI work without a running server. Everything
//"succeeds" with an empty result rather than actually persisting anything - use RideApiServiceImpl
//(backed by the backend/ module, see docs/BACKEND.md) for a working end-to-end flow.
public class FakeRideApiService implements RideApiService {
    @Override
    public void fetchRides(Callback<List<RideOffer>> callback) {
        callback.onSuccess(Collections.emptyList());
    }

    @Override
    public void fetchUserProfile(String userId, Callback<UserProfile> callback) {
        callback.onSuccess(null);
    }

    @Override
    public void fetchRouteSummaries(String rideId, Callback<List<RouteSnapshot>> callback) {
        callback.onSuccess(Collections.emptyList());
    }

    @Override
    public void updateUserProfile(String userId, String displayName, String preferredName, boolean passengerEnabled, Callback<UserProfile> callback) {
        callback.onSuccess(new UserProfile(userId, displayName, preferredName, null, null, passengerEnabled));
    }

    @Override
    public void searchRides(RideSearchCriteria criteria, Callback<List<RankedRide>> callback) {
        callback.onSuccess(Collections.emptyList());
    }

    @Override
    public void fetchRide(String rideId, Callback<RideOffer> callback) {
        callback.onFailure(new UnsupportedOperationException("No backend configured"));
    }

    @Override
    public void createRide(CreateRideDraft draft, Callback<RideOffer> callback) {
        callback.onFailure(new UnsupportedOperationException("No backend configured - run ./gradlew :backend:run and rebuild"));
    }

    @Override
    public void cancelRide(String rideId, Callback<RideOffer> callback) {
        callback.onFailure(new UnsupportedOperationException("No backend configured"));
    }

    @Override
    public void fetchMyDriverRides(Callback<List<RideOffer>> callback) {
        callback.onSuccess(Collections.emptyList());
    }

    @Override
    public void fetchTripHistory(Callback<List<RideOffer>> callback) {
        callback.onSuccess(Collections.emptyList());
    }

    @Override
    public void requestSeat(String rideId, int seatsRequested, Callback<SeatRequest> callback) {
        callback.onFailure(new UnsupportedOperationException("No backend configured"));
    }

    @Override
    public void fetchRequestsForMyRides(Callback<List<RequestWithRide>> callback) {
        callback.onSuccess(Collections.emptyList());
    }

    @Override
    public void fetchMyRequests(Callback<List<RequestWithRide>> callback) {
        callback.onSuccess(Collections.emptyList());
    }

    @Override
    public void respondToRequest(String requestId, String newStatus, Callback<SeatRequest> callback) {
        callback.onFailure(new UnsupportedOperationException("No backend configured"));
    }

    @Override
    public void fetchMessages(String rideId, Callback<List<Message>> callback) {
        callback.onSuccess(Collections.emptyList());
    }

    @Override
    public void sendMessage(String rideId, String body, Callback<Message> callback) {
        callback.onFailure(new UnsupportedOperationException("No backend configured"));
    }

    @Override
    public void fetchDashboard(Callback<DashboardSummary> callback) {
        callback.onSuccess(new DashboardSummary(0, 0.0, Collections.emptyList()));
    }
}
