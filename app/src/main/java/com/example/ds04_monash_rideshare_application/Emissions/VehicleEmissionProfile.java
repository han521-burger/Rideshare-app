package com.example.ds04_monash_rideshare_application.Emissions;

//One row of the team-prepared emissions fallback dataset (Option C - see EmissionDatasetProvider).
public class VehicleEmissionProfile {
    public final String make;
    public final String model;
    public final String fuelType;
    public final String vehicleCategory;
    public final double co2GPerKm;
    public final double fuelConsumptionL100km;
    public final String source;

    public VehicleEmissionProfile(String make, String model, String fuelType, String vehicleCategory,
                                   double co2GPerKm, double fuelConsumptionL100km, String source) {
        this.make = make;
        this.model = model;
        this.fuelType = fuelType;
        this.vehicleCategory = vehicleCategory;
        this.co2GPerKm = co2GPerKm;
        this.fuelConsumptionL100km = fuelConsumptionL100km;
        this.source = source;
    }
}
