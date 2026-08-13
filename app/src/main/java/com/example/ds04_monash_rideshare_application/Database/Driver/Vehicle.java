package com.example.ds04_monash_rideshare_application.Database.Driver;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.example.ds04_monash_rideshare_application.Database.User.UserAccount;

@Entity(
        tableName = "vehicle",
        foreignKeys = @ForeignKey(
                entity = UserAccount.class,
                parentColumns = "userId",
                childColumns = "ownerId",
                onDelete = ForeignKey.CASCADE
        ),
        indices = @Index("ownerId")
)
public class Vehicle {
    @PrimaryKey
    @NonNull
    private String vehicleId;

    @NonNull
    private String ownerId;

    private int year;
    private String make;
    private String model;
    private String variant;
    private String registrationMasked;
    private int seatCapacity;
    private String fuelType;

    public Vehicle(@NonNull String vehicleId, @NonNull String ownerId, int year, String make,
                   String model, String variant, String registrationMasked, int seatCapacity, String fuelType) {
        this.vehicleId = vehicleId;
        this.ownerId = ownerId;
        this.year = year;
        this.make = make;
        this.model = model;
        this.variant = variant;
        this.registrationMasked = registrationMasked;
        this.seatCapacity = seatCapacity;
        this.fuelType = fuelType;
    }

    @NonNull public String getVehicleId() {
        return vehicleId; }
    @NonNull public String getOwnerId() {
        return ownerId; }
    public int getYear() {
        return year; }
    public String getMake() {
        return make; }
    public String getModel() {
        return model; }
    public String getVariant() {
        return variant; }
    public String getRegistrationMasked() {
        return registrationMasked; }
    public int getSeatCapacity() {
        return seatCapacity; }
    public String getFuelType() {
        return fuelType; }
}