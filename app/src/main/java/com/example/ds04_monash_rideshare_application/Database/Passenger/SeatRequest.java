package com.example.ds04_monash_rideshare_application.Database.Passenger;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOffer;
import com.example.ds04_monash_rideshare_application.Database.User.UserAccount;


@Entity(
        tableName = "seat_request",
        foreignKeys = {
                @ForeignKey(
                        entity = RideOffer.class,
                        parentColumns = "rideId",
                        childColumns = "rideId",
                        onDelete = ForeignKey.CASCADE
                ),
                @ForeignKey(
                        entity = UserAccount.class,
                        parentColumns = "userId",
                        childColumns = "passengerId",
                        onDelete = ForeignKey.CASCADE
                )
        },
        indices = {
                @Index(value = {"rideId", "passengerId"}, unique = true),
                @Index("passengerId")
        }
)
public class SeatRequest {
    @PrimaryKey
    @NonNull
    private String requestId;

    @NonNull
    private String rideId;

    @NonNull
    private String passengerId;

    private int seatsRequested;
    private String status;
    private long createdAt;

    public SeatRequest(@NonNull String requestId, @NonNull String rideId, @NonNull String passengerId,
                       int seatsRequested, String status, long createdAt) {
        this.requestId = requestId;
        this.rideId = rideId;
        this.passengerId = passengerId;
        this.seatsRequested = seatsRequested;
        this.status = status;
        this.createdAt = createdAt;
    }

    @NonNull public String getRequestId() { return requestId; }
    @NonNull public String getRideId() { return rideId; }
    @NonNull public String getPassengerId() { return passengerId; }
    public int getSeatsRequested() { return seatsRequested; }
    public String getStatus() { return status; }
    public long getCreatedAt() { return createdAt; }
}