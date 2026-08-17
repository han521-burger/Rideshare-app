package com.example.ds04_monash_rideshare_application.Repository.Remote;

import androidx.annotation.Nullable;

import com.example.ds04_monash_rideshare_application.Database.Passenger.SeatRequest;
import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOffer;

//A seat request plus the ride it's for - display-only (not persisted to Room), so screens can
//show a date/place instead of a bare ride ID. The ride may be null if the backend request row
//somehow outlived its ride (shouldn't happen in practice - rides aren't hard-deleted once they
//have requests, see backend RideRoutes.kt's DELETE /rides/{id}).
public class RequestWithRide {
    public final SeatRequest request;
    @Nullable
    public final RideOffer ride;

    public RequestWithRide(SeatRequest request, @Nullable RideOffer ride) {
        this.request = request;
        this.ride = ride;
    }
}
