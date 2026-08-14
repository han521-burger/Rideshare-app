package com.example.ds04_monash_rideshare_application.Repository;

import androidx.lifecycle.LiveData;

import java.util.List;
import java.util.concurrent.Executor;

import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOffer;
import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOfferDao;
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideApiService;

//The only class the UI should ask for ride data - never RideOfferDao directly.
//getRides()/getConfirmedRides()/getTripHistory() split the same ride_offer cache by status; all
//three always read from Room. refreshRides() is what pulls fresh data from the API into Room.
//
//Simplification: "confirmed" here means the ride offer itself is locked in (status = CONFIRMED),
//not a per-passenger seat request - a passenger-specific confirmed-rides view would join against
//SeatRequest and is a follow-up, not part of this first cache pass.
public class RideRepository {
    private final RideOfferDao rideOfferDao;
    private final RideApiService apiService;
    private final Executor ioExecutor;

    public RideRepository(RideOfferDao rideOfferDao, RideApiService apiService, Executor ioExecutor) {
        this.rideOfferDao = rideOfferDao;
        this.apiService = apiService;
        this.ioExecutor = ioExecutor;
    }

    public LiveData<List<RideOffer>> getRides() {
        return rideOfferDao.observeByStatus(RideOffer.STATUS_OPEN);
    }

    public LiveData<List<RideOffer>> getConfirmedRides() {
        return rideOfferDao.observeByStatus(RideOffer.STATUS_CONFIRMED);
    }

    public LiveData<List<RideOffer>> getTripHistory() {
        return rideOfferDao.observeByStatus(RideOffer.STATUS_COMPLETED);
    }

    public void refreshRides() {
        apiService.fetchRides(new RideApiService.Callback<List<RideOffer>>() {
            @Override
            public void onSuccess(List<RideOffer> result) {
                ioExecutor.execute(() -> rideOfferDao.upsertAll(result));
            }

            @Override
            public void onFailure(Exception error) {
                //Room keeps serving the last-known-good ride list; nothing to do here.
            }
        });
    }
}
