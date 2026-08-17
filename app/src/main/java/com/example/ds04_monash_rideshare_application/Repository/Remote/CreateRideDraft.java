package com.example.ds04_monash_rideshare_application.Repository.Remote;

//Everything a driver fills in on the Create Ride screen. driverId, rideId, status, and createdAt
//are all assigned by the backend, not the client - kept out of this draft on purpose.
public class CreateRideDraft {
    public final String vehicleId;
    public final double originLat;
    public final double originLng;
    public final double destinationLat;
    public final double destinationLng;
    public final double distanceMeters;
    public final long estimatedDurationSeconds;
    public final long departureTime;
    public final int seatsTotal;
    public final String notes;

    public CreateRideDraft(String vehicleId, double originLat, double originLng, double destinationLat,
                            double destinationLng, double distanceMeters, long estimatedDurationSeconds,
                            long departureTime, int seatsTotal, String notes) {
        this.vehicleId = vehicleId;
        this.originLat = originLat;
        this.originLng = originLng;
        this.destinationLat = destinationLat;
        this.destinationLng = destinationLng;
        this.distanceMeters = distanceMeters;
        this.estimatedDurationSeconds = estimatedDurationSeconds;
        this.departureTime = departureTime;
        this.seatsTotal = seatsTotal;
        this.notes = notes;
    }
}
