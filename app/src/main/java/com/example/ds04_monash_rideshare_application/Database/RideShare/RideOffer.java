package com.example.ds04_monash_rideshare_application.Database.RideShare;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.example.ds04_monash_rideshare_application.Database.Driver.Vehicle;
import com.example.ds04_monash_rideshare_application.Database.User.UserAccount;

@Entity(
        tableName = "ride_offer",
        foreignKeys = {
                @ForeignKey(
                        entity = UserAccount.class,
                        parentColumns = "userId",
                        childColumns = "driverId",
                        onDelete = ForeignKey.CASCADE
                ),
                @ForeignKey(
                        entity = Vehicle.class,
                        parentColumns = "vehicleId",
                        childColumns = "vehicleId",
                        onDelete = ForeignKey.SET_NULL
                )
        },
        indices = {
                @Index("driverId"),
                @Index("vehicleId"),
                @Index("status")
        }
)
public class RideOffer {
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @PrimaryKey
    @NonNull
    private String rideId;

    @NonNull
    private String driverId;

    @Nullable
    private String vehicleId;

    private double originLat;
    private double originLng;
    private double destinationLat;
    private double destinationLng;
    private long departureTime;
    private int seatsAvailable;
    private String status;
    private long createdAt;

    public RideOffer(@NonNull String rideId, @NonNull String driverId, @Nullable String vehicleId,
                     double originLat, double originLng, double destinationLat, double destinationLng,
                     long departureTime, int seatsAvailable, String status, long createdAt) {
        this.rideId = rideId;
        this.driverId = driverId;
        this.vehicleId = vehicleId;
        this.originLat = originLat;
        this.originLng = originLng;
        this.destinationLat = destinationLat;
        this.destinationLng = destinationLng;
        this.departureTime = departureTime;
        this.seatsAvailable = seatsAvailable;
        this.status = status;
        this.createdAt = createdAt;
    }

    @NonNull public String getRideId() { return rideId; }
    @NonNull public String getDriverId() { return driverId; }
    @Nullable public String getVehicleId() { return vehicleId; }
    public double getOriginLat() { return originLat; }
    public double getOriginLng() { return originLng; }
    public double getDestinationLat() { return destinationLat; }
    public double getDestinationLng() { return destinationLng; }
    public long getDepartureTime() { return departureTime; }
    public int getSeatsAvailable() { return seatsAvailable; }
    public String getStatus() { return status; }
    public long getCreatedAt() { return createdAt; }
}
