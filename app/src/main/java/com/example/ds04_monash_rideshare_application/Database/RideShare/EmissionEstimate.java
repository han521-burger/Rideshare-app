package com.example.ds04_monash_rideshare_application.Database.RideShare;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "emission_estimate",
        foreignKeys = @ForeignKey(
                entity = RideOffer.class,
                parentColumns = "rideId",
                childColumns = "rideId",
                onDelete = ForeignKey.CASCADE
        ),
        indices = @Index("rideId")
)
public class EmissionEstimate {
    @PrimaryKey
    @NonNull
    private String estimateId;

    @NonNull
    private String rideId;

    private boolean vehicleMatch;
    private double co2GPerKm;
    private double totalCo2G;
    private double passengerShareG;
    private String source;
    private long sourceDate;

    public EmissionEstimate(@NonNull String estimateId, @NonNull String rideId, boolean vehicleMatch,
                            double co2GPerKm, double totalCo2G, double passengerShareG,
                            String source, long sourceDate) {
        this.estimateId = estimateId;
        this.rideId = rideId;
        this.vehicleMatch = vehicleMatch;
        this.co2GPerKm = co2GPerKm;
        this.totalCo2G = totalCo2G;
        this.passengerShareG = passengerShareG;
        this.source = source;
        this.sourceDate = sourceDate;
    }

    @NonNull public String getEstimateId() { return estimateId; }
    @NonNull public String getRideId() { return rideId; }
    public boolean isVehicleMatch() { return vehicleMatch; }
    public double getCo2GPerKm() { return co2GPerKm; }
    public double getTotalCo2G() { return totalCo2G; }
    public double getPassengerShareG() { return passengerShareG; }
    public String getSource() { return source; }
    public long getSourceDate() { return sourceDate; }
}