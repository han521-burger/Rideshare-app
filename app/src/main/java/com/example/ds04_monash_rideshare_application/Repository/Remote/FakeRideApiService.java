package com.example.ds04_monash_rideshare_application.Repository.Remote;

import java.util.Collections;
import java.util.List;

import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOffer;
import com.example.ds04_monash_rideshare_application.Database.RideShare.RouteSnapshot;
import com.example.ds04_monash_rideshare_application.Database.User.UserProfile;

//Dev-only stand-in for the real backend (not built yet). Always "succeeds" with an empty result, so
//Repository code and its Room fallback can be exercised end-to-end without a server, the same way
//FakeAuthenticationProvider stands in for Monash SSO.
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
}
