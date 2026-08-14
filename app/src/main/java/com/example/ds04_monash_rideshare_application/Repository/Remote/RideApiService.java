package com.example.ds04_monash_rideshare_application.Repository.Remote;

import java.util.List;

import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOffer;
import com.example.ds04_monash_rideshare_application.Database.RideShare.RouteSnapshot;
import com.example.ds04_monash_rideshare_application.Database.User.UserProfile;

//The "API" side of the Repository diagram - talks to the remote server. Swap FakeRideApiService for
//a real Retrofit/OkHttp-backed implementation once the backend exists; Repository code doesn't change.
public interface RideApiService {
    interface Callback<T> {
        void onSuccess(T result);
        void onFailure(Exception error);
    }

    void fetchRides(Callback<List<RideOffer>> callback);
    void fetchUserProfile(String userId, Callback<UserProfile> callback);
    void fetchRouteSummaries(String rideId, Callback<List<RouteSnapshot>> callback);
}
