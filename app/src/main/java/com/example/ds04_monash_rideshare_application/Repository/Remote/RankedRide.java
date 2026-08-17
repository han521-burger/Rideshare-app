package com.example.ds04_monash_rideshare_application.Repository.Remote;

import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOffer;

//A ride plus its advisory match score from the backend's matching pipeline. Purely a ranking
//aid for the passenger's search results - the driver still makes the final accept/reject call.
public class RankedRide {
    public final RideOffer ride;
    public final double matchScore;
    public final boolean fromFallbackMatch;

    public RankedRide(RideOffer ride, double matchScore, boolean fromFallbackMatch) {
        this.ride = ride;
        this.matchScore = matchScore;
        this.fromFallbackMatch = fromFallbackMatch;
    }
}
