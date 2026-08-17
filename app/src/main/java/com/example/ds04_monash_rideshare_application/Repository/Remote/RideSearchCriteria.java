package com.example.ds04_monash_rideshare_application.Repository.Remote;

//Passenger search input for the matching pipeline (backend matching/MatchingEngine.kt).
public class RideSearchCriteria {
    public final double originLat;
    public final double originLng;
    public final double destinationLat;
    public final double destinationLng;
    public final long departureTime;
    public final int seatsNeeded;

    public RideSearchCriteria(double originLat, double originLng, double destinationLat,
                               double destinationLng, long departureTime, int seatsNeeded) {
        this.originLat = originLat;
        this.originLng = originLng;
        this.destinationLat = destinationLat;
        this.destinationLng = destinationLng;
        this.departureTime = departureTime;
        this.seatsNeeded = seatsNeeded;
    }
}
